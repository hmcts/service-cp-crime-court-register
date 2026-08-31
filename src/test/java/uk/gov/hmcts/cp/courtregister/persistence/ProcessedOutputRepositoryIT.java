package uk.gov.hmcts.cp.courtregister.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.ProcessedOutputClaim;
import uk.gov.hmcts.cp.courtregister.domain.RequestFingerprint;
import uk.gov.hmcts.cp.courtregister.domain.RunClaim;
import uk.gov.hmcts.cp.courtregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.courtregister.support.ProcessedLogTestSupport;

/**
 * The submission half of the processed log, against a real Postgres.
 *
 * <p>These statements are what makes an at-least-once delivery safe to submit from. The property
 * under test throughout is the affected-row count, exactly as it is for the request-level
 * repository: a claim that affects no rows means this hearing's register has already gone and must
 * not go again, and no amount of reading the row afterwards would make that decision safe under two
 * concurrent deliveries.
 *
 * <p>Where the informant service fans a request out across prosecuting authorities, the court
 * register produces exactly one document per hearing. There is no fan-out dimension to key on, so
 * the request itself is the key and the database enforces it: {@code UNIQUE (source, request_id)},
 * asserted here as the thing that would refuse a second register for one hearing rather than as a
 * line in a migration nobody re-reads.
 *
 * <p>Every case seeds its own {@code processed_request} parent, because {@code processed_output}
 * carries a foreign key to it. That is the schema saying what the design rules say: an output row is
 * evidence about a request, and evidence with nothing to be about is not evidence.
 */
@DisplayName("processed_output repository")
class ProcessedOutputRepositoryIT {

    private static final Duration LEASE = Duration.ofMinutes(5);

    private static final UUID COURT_CENTRE = UUID.fromString("2f4a1c66-9d1e-4d3b-9a55-7c1a0f6b8e21");
    private static final String OU_CODE = "B01LY";
    private static final LocalDate REGISTER_DATE = LocalDate.of(2026, 8, 20);
    private static final String FILE_NAME = "court-register-B01LY-20260820.pdf";

    private static final String DIGEST =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    private static final String OTHER_DIGEST =
            "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";

    /** Bounded reason-code counts, exactly as C19/C20/C27 write them: codes and numbers, no text. */
    private static final String ANOMALIES =
            "unresolvable-youth-defendant:1,letter-delivery-dropped:2";

    private static final int ACCEPTED = 202;
    private static final int REFUSED = 400;

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    private static ProcessedOutputRepository repository() {
        return new ProcessedOutputRepository(ProcessedLogTestSupport.jdbcClient());
    }

    /** Seeds a request row so the output row has the parent its foreign key requires. */
    private static DistributionCommand seededRequest() {
        final DistributionCommand command = ProcessedLogTestSupport.command();
        final RunClaim claim = new RunClaim(
                command.source(), command.requestId(), "runner-1", UUID.randomUUID(), "msg-1");
        ProcessedLogTestSupport.repository(LEASE)
                .insertNew(command, RequestFingerprint.of(command), claim);
        return command;
    }

    private static ProcessedOutputClaim claimFor(
            final DistributionCommand command, final UUID outputId, final String digest) {
        return claimFor(command, outputId, digest, null);
    }

    private static ProcessedOutputClaim claimFor(
            final DistributionCommand command,
            final UUID outputId,
            final String digest,
            final String anomalySummary) {
        return new ProcessedOutputClaim(outputId, command.source(), command.requestId(),
                COURT_CENTRE, OU_CODE, REGISTER_DATE, FILE_NAME, digest, anomalySummary);
    }

    @Nested
    @DisplayName("claiming the request before the POST")
    class Claiming {

        @Test
        void claiming_a_fresh_request_should_write_a_pending_row_carrying_the_digest() {
            final DistributionCommand command = seededRequest();
            final UUID outputId = UUID.randomUUID();

            final boolean claimed = repository().claimPending(claimFor(command, outputId, DIGEST));

            assertThat(claimed).isTrue();
            final Row row = requireRow(command);
            assertThat(row.outputId()).isEqualTo(outputId);
            assertThat(row.status()).isEqualTo("PENDING");
            assertThat(row.requestDigest())
                    .as("the digest of the bytes about to be sent is written before the POST, so an "
                            + "ambiguous outcome still leaves evidence of what was attempted")
                    .isEqualTo(DIGEST);
            assertThat(row.responseCode())
                    .as("nothing has answered yet")
                    .isNull();
        }

        @Test
        void claiming_should_record_what_the_register_was_assembled_for() {
            final DistributionCommand command = seededRequest();

            repository().claimPending(claimFor(command, UUID.randomUUID(), DIGEST));

            final Row row = requireRow(command);
            assertThat(row.courtCentreId()).isEqualTo(COURT_CENTRE);
            assertThat(row.courtCentreOuCode()).isEqualTo(OU_CODE);
            assertThat(row.registerDate()).isEqualTo(REGISTER_DATE);
            assertThat(row.fileName()).isEqualTo(FILE_NAME);
        }

        /**
         * The hearing payload may carry no {@code hearing.courtCentre.code}, and a register is still
         * produced for it. The column is nullable for that reason and the claim has to be able to
         * say so, rather than inventing a placeholder support would later read as a real OU code.
         */
        @Test
        void claiming_should_accept_a_court_centre_with_no_ou_code() {
            final DistributionCommand command = seededRequest();

            final boolean claimed = repository().claimPending(new ProcessedOutputClaim(
                    UUID.randomUUID(), command.source(), command.requestId(), COURT_CENTRE, null,
                    REGISTER_DATE, FILE_NAME, DIGEST, null));

            assertThat(claimed).isTrue();
            assertThat(requireRow(command).courtCentreOuCode()).isNull();
        }

        /**
         * The replay skip, stated as a predicate rather than as a branch in Java.
         *
         * <p>{@code add-court-register} appends an event and a row on every POST, so a redelivery or
         * a support replay that re-sent an accepted register would leave two registers for one
         * hearing. Progression's generation sweep absorbs the duplicate, but the extra row persists,
         * so the log refuses the second send outright.
         */
        @Test
        void claiming_should_be_refused_once_the_register_is_posted() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(claimFor(command, UUID.randomUUID(), DIGEST));
            repository.recordPosted(command.source(), command.requestId(), ACCEPTED);

            final boolean claimed =
                    repository.claimPending(claimFor(command, UUID.randomUUID(), OTHER_DIGEST));

            assertThat(claimed).isFalse();
            final Row row = requireRow(command);
            assertThat(row.status()).isEqualTo("POSTED");
            assertThat(row.requestDigest())
                    .as("a refused claim must not overwrite what was actually sent")
                    .isEqualTo(DIGEST);
            assertThat(row.responseCode()).isEqualTo(ACCEPTED);
        }

        @Test
        void claiming_should_be_admitted_again_after_a_failure_so_the_failed_work_repeats() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            final UUID firstId = UUID.randomUUID();
            repository.claimPending(claimFor(command, firstId, DIGEST));
            repository.recordFailed(command.source(), command.requestId(), REFUSED);

            final boolean claimed =
                    repository.claimPending(claimFor(command, UUID.randomUUID(), OTHER_DIGEST));

            assertThat(claimed).isTrue();
            final Row row = requireRow(command);
            assertThat(row.status()).isEqualTo("PENDING");
            assertThat(row.outputId())
                    .as("the row keeps the identity it was first written under")
                    .isEqualTo(firstId);
            assertThat(row.requestDigest())
                    .as("the digest describes the body about to be sent, so a re-claim replaces it")
                    .isEqualTo(OTHER_DIGEST);
        }
    }

    /**
     * The anomaly summary: a register that survived with a part missing, and said which part.
     *
     * <p>The legacy pipeline either threw and lost the whole hearing (C19, C20) or dropped a
     * recipient without a word (C27). The fixes keep the register and record the skip as a bounded
     * reason-code count — codes and numbers only, because every defendant on this document is a
     * child and free text is how a name reaches a support query.
     */
    @Nested
    @DisplayName("the anomaly summary")
    class Anomalies {

        @Test
        void a_bounded_reason_code_count_should_round_trip_unchanged() {
            final DistributionCommand command = seededRequest();

            repository().claimPending(claimFor(command, UUID.randomUUID(), DIGEST, ANOMALIES));

            assertThat(requireRow(command).anomalySummary()).isEqualTo(ANOMALIES);
        }

        @Test
        void a_register_with_nothing_skipped_should_leave_the_summary_empty() {
            final DistributionCommand command = seededRequest();

            repository().claimPending(claimFor(command, UUID.randomUUID(), DIGEST));

            assertThat(requireRow(command).anomalySummary())
                    .as("no anomalies is an absent summary, not the string 'none'")
                    .isNull();
        }

        @Test
        void a_re_claim_should_replace_the_summary_with_the_one_it_is_about_to_send() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(claimFor(command, UUID.randomUUID(), DIGEST, ANOMALIES));
            repository.recordFailed(command.source(), command.requestId(), REFUSED);

            repository.claimPending(claimFor(command, UUID.randomUUID(), OTHER_DIGEST,
                    "recipient-missing-email:1"));

            assertThat(requireRow(command).anomalySummary()).isEqualTo("recipient-missing-email:1");
        }

        @Test
        void a_failed_post_should_keep_the_summary_of_what_it_tried_to_send() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(claimFor(command, UUID.randomUUID(), DIGEST, ANOMALIES));

            repository.recordFailed(command.source(), command.requestId(), REFUSED);

            assertThat(requireRow(command).anomalySummary()).isEqualTo(ANOMALIES);
        }
    }

    @Nested
    @DisplayName("recording the outcome of the POST")
    class Recording {

        @Test
        void recording_a_post_should_move_the_row_to_posted_with_the_status_progression_answered() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(claimFor(command, UUID.randomUUID(), DIGEST));
            ageUpdatedAt(command);
            final Instant aged = requireRow(command).updatedAt();

            final boolean recorded =
                    repository.recordPosted(command.source(), command.requestId(), ACCEPTED);

            assertThat(recorded).isTrue();
            final Row row = requireRow(command);
            assertThat(row.status()).isEqualTo("POSTED");
            assertThat(row.responseCode())
                    .as("202 and nothing else is success, and the number is what support reads")
                    .isEqualTo(ACCEPTED);
            assertThat(row.updatedAt()).isAfter(aged);
        }

        @Test
        void recording_a_failure_should_move_the_row_to_failed_and_keep_the_digest() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(claimFor(command, UUID.randomUUID(), DIGEST));

            final boolean recorded =
                    repository.recordFailed(command.source(), command.requestId(), REFUSED);

            assertThat(recorded).isTrue();
            final Row row = requireRow(command);
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.responseCode()).isEqualTo(REFUSED);
            assertThat(row.requestDigest())
                    .as("what was attempted is still the reconciliation evidence — and it is worth "
                            + "more after a failure than after a success")
                    .isEqualTo(DIGEST);
        }

        /**
         * A connect failure or a timeout has no status line to record. The row still has to move,
         * because the alternative is a PENDING row nobody can distinguish from a run still in
         * flight — and an ambiguous POST is retried, so the evidence of the attempt is the only
         * thing that says a duplicate is possible.
         */
        @Test
        void recording_a_failure_with_no_answer_should_leave_the_response_code_empty() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(claimFor(command, UUID.randomUUID(), DIGEST));

            final boolean recorded =
                    repository.recordFailed(command.source(), command.requestId(), null);

            assertThat(recorded).isTrue();
            final Row row = requireRow(command);
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.responseCode()).isNull();
            assertThat(row.requestDigest()).isEqualTo(DIGEST);
        }

        /**
         * The case that makes the predicate worth having.
         *
         * <p>Two deliveries of a request can overlap: a runner whose claim was reclaimed while it
         * worked is still running, and its POST can finish after the winner's. If its late failure
         * could move a POSTED register back to FAILED, the next delivery would re-claim it and POST
         * a second, non-idempotent {@code add-court-register} — a duplicate register created by the
         * log that exists to prevent one.
         */
        @Test
        void a_late_failure_should_never_move_a_register_out_of_posted() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(claimFor(command, UUID.randomUUID(), DIGEST));
            repository.recordPosted(command.source(), command.requestId(), ACCEPTED);

            final boolean recorded =
                    repository.recordFailed(command.source(), command.requestId(), REFUSED);

            assertThat(recorded)
                    .as("the loser of an overlap is told its write affected nothing")
                    .isFalse();
            final Row row = requireRow(command);
            assertThat(row.status()).isEqualTo("POSTED");
            assertThat(row.responseCode()).isEqualTo(ACCEPTED);
        }

        @Test
        void recording_a_post_twice_should_leave_the_row_posted_and_affect_nothing_the_second_time() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(claimFor(command, UUID.randomUUID(), DIGEST));
            repository.recordPosted(command.source(), command.requestId(), ACCEPTED);

            final boolean again =
                    repository.recordPosted(command.source(), command.requestId(), ACCEPTED);

            assertThat(again).isFalse();
            assertThat(requireRow(command).status()).isEqualTo("POSTED");
        }

        @Test
        void recording_a_post_after_a_failure_should_be_admitted_so_the_success_is_the_last_word() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(claimFor(command, UUID.randomUUID(), DIGEST));
            repository.recordFailed(command.source(), command.requestId(), REFUSED);

            final boolean recorded =
                    repository.recordPosted(command.source(), command.requestId(), ACCEPTED);

            assertThat(recorded)
                    .as("a register that did go must end POSTED, or it would be sent again")
                    .isTrue();
            assertThat(requireRow(command).status()).isEqualTo("POSTED");
        }

        @Test
        void recording_an_outcome_for_an_unclaimed_request_should_affect_nothing() {
            final DistributionCommand command = seededRequest();

            final boolean recorded =
                    repository().recordPosted(command.source(), command.requestId(), ACCEPTED);

            assertThat(recorded).isFalse();
            assertThat(row(command)).isEmpty();
        }
    }

    /**
     * One register per hearing, enforced by the database rather than by the code that writes it.
     *
     * <p>The repository's conditional upsert could never attempt a second row, which is exactly why
     * the constraint is worth asserting directly: it is what would refuse the row if some later
     * caller — a support fix, a migration, a second submission path — tried to write one.
     */
    @Nested
    @DisplayName("one output per request")
    class Uniqueness {

        @Test
        void a_second_output_for_one_request_should_be_refused_by_the_database() {
            final DistributionCommand command = seededRequest();
            repository().claimPending(claimFor(command, UUID.randomUUID(), DIGEST));

            assertThatThrownBy(() -> insertSecondOutput(command))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("processed_output_unique_request");
        }

        private void insertSecondOutput(final DistributionCommand command) {
            ProcessedLogTestSupport.jdbcClient()
                    .sql("""
                            INSERT INTO processed_output (
                                output_id, source, request_id, court_centre_id, register_date,
                                file_name, status)
                            VALUES (
                                :outputId, :source, :requestId, :courtCentreId, :registerDate,
                                :fileName, 'PENDING')
                            """)
                    .param("outputId", UUID.randomUUID())
                    .param("source", command.source())
                    .param("requestId", command.requestId())
                    .param("courtCentreId", COURT_CENTRE)
                    .param("registerDate", REGISTER_DATE)
                    .param("fileName", FILE_NAME)
                    .update();
        }
    }

    private record Row(
            UUID outputId,
            UUID courtCentreId,
            String courtCentreOuCode,
            LocalDate registerDate,
            String fileName,
            String status,
            Integer responseCode,
            String requestDigest,
            String anomalySummary,
            Instant createdAt,
            Instant updatedAt) {
    }

    private static Optional<Row> row(final DistributionCommand command) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT output_id, court_centre_id, court_centre_ou_code, register_date,
                               file_name, status, response_code, request_digest, anomaly_summary,
                               created_at, updated_at
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .query((rs, rowNumber) -> new Row(
                        rs.getObject("output_id", UUID.class),
                        rs.getObject("court_centre_id", UUID.class),
                        rs.getString("court_centre_ou_code"),
                        rs.getObject("register_date", LocalDate.class),
                        rs.getString("file_name"),
                        rs.getString("status"),
                        rs.getObject("response_code", Integer.class),
                        rs.getString("request_digest"),
                        rs.getString("anomaly_summary"),
                        instant(rs.getObject("created_at", OffsetDateTime.class)),
                        instant(rs.getObject("updated_at", OffsetDateTime.class))))
                .optional();
    }

    private static Row requireRow(final DistributionCommand command) {
        return row(command).orElseThrow(() -> new IllegalStateException(
                "no processed_output row for " + command.source() + "/" + command.requestId()));
    }

    /**
     * Seeds {@code updated_at} into the past by the database's own clock, so "the write moved the
     * timestamp on" can be asserted strictly rather than against a value written moments earlier.
     */
    private static void ageUpdatedAt(final DistributionCommand command) {
        final int aged = ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        UPDATE processed_output
                           SET updated_at = now() - interval '1 hour'
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .update();
        if (aged != 1) {
            throw new IllegalStateException("expected one output row to age, aged " + aged);
        }
    }

    private static Instant instant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
