package uk.gov.hmcts.cp.courtregister.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.NotificationStatus;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterNotification;
import uk.gov.hmcts.cp.courtregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.courtregister.support.ProcessedLogTestSupport;

/**
 * The {@code register_notification} statements, against a real Postgres.
 *
 * <p>These four statements are the whole of what this service can honestly say about an e-mail it
 * sent. The row is minted PENDING <em>before</em> the POST and settled after it, so an attempt that
 * was never answered is a row saying so rather than an absence indistinguishable from one that was
 * never made - the same discipline 001 applies to {@code processed_output.request_digest}, and the
 * reason the identity in the row is the one that goes in the path of
 * {@code POST /notifications/{notificationId}}: a retry under a fresh identity would send a Youth
 * Offending Team a second copy of a register about children.
 *
 * <p>Nothing else exercises them. The notification service and the resend CLI mock the repository,
 * so a column dropped from the insert would first be noticed by a resend that could not tell which
 * recipients had already been told.
 *
 * <p><strong>The typed nulls.</strong> {@code response_code} and {@code sent_at} are empty on a row
 * minted before its POST and on a row whose POST was never answered at all - a connect failure has
 * no status line and no settlement instant - so both statements are exercised with them absent as
 * well as present.
 *
 * <p>{@code SchemaMigrationV2IT} pins {@code UNIQUE (batch_id, email_address)} as a fact about the
 * table. It is asserted again here, through the repository's own insert, because what matters to a
 * caller is that the refusal reaches it: a second attempt at an address is a second e-mail, and a
 * repository that absorbed the refusal would turn defect fix P4 into a duplicate nobody sees.
 *
 * <p>Every case mints its own batch, so the suites sharing one container share no rows.
 */
@DisplayName("register_notification repository")
class RegisterNotificationRepositoryIT {

    /** The single row a write of one notification is expected to change. */
    private static final int ONE_ROW = 1;

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);
    private static final Instant ASSEMBLED_AT = Instant.parse("2026-08-24T17:00:00Z");
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-24T17:00:04Z");
    private static final Instant GENERATED_AT = Instant.parse("2026-08-24T17:04:11Z");

    private static final UUID PAYLOAD_FILE_ID =
            UUID.fromString("5e08b6d1-92a7-4c33-8f10-6b4d3e79a281");
    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("3a7f1c92-6d84-4b05-9e73-1c2b8a4e07d5");
    private static final Instant SENT_AT = Instant.parse("2026-08-24T17:04:22Z");

    private static final UUID TEMPLATE_ID =
            UUID.fromString("8f2d1a05-4b6c-4e37-9d18-52a7c0e3b964");

    private static final String TEMPLATE_NAME = "cr_standard";
    private static final String OU_CODE = "B01LY00";
    private static final String COURT_HOUSE = "Lavender Hill Youth Court";

    private static final String WANDSWORTH = "wandsworth.yot@example.gov.uk";
    private static final String LAMBETH = "lambeth.yot@example.gov.uk";

    private static final int ACCEPTED = 202;

    private final UUID courtCentre = UUID.randomUUID();

    private final RegisterBatchRepository batches =
            new RegisterBatchRepository(ProcessedLogTestSupport.jdbcClient());

    private final RegisterNotificationRepository repository =
            new RegisterNotificationRepository(ProcessedLogTestSupport.jdbcClient());

    /** This case's batch: the parent the foreign key requires, minted per test. */
    private final UUID batchId = UUID.randomUUID();

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    @Nested
    @DisplayName("minting a recipient's row before its POST")
    class Minting {

        @Test
        void minting_a_recipient_should_read_back_the_row_the_post_will_go_out_under() {
            seededBatch();
            final RegisterNotification pending = pending(WANDSWORTH, "Wandsworth YOT");

            repository.insert(pending);

            assertThat(repository.findByBatchId(batchId))
                    .as("what was attempted is the evidence: the identity, the address and the "
                            + "template are on record before anything is sent, and the two "
                            + "settlement columns are empty because nothing has answered yet")
                    .containsExactly(pending);
        }

        @Test
        void minting_a_recipient_without_a_name_should_keep_the_row_the_subscription_offered() {
            seededBatch();
            final RegisterNotification anonymous = pending(WANDSWORTH, null);

            repository.insert(anonymous);

            assertThat(repository.findByBatchId(batchId))
                    .as("a subscription may carry an address and nothing else, and an e-mail with "
                            + "no yotsName is still an e-mail that has to be recorded")
                    .containsExactly(anonymous);
        }

        @Test
        void minting_a_second_row_for_an_address_already_told_should_be_refused() {
            seededBatch();
            repository.insert(pending(WANDSWORTH, "Wandsworth YOT"));

            assertThatThrownBy(() -> repository.insert(pending(WANDSWORTH, "Wandsworth YOT")))
                    .as("the persistence half of defect fix P4: the union is computed once at "
                            + "assembly, and the database refuses a second attempt at the same "
                            + "address for the same batch rather than sending it twice")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("register_notification_unique_recipient");
        }
    }

    @Nested
    @DisplayName("reading a batch's recipients")
    class Reading {

        @Test
        void reading_a_batchs_recipients_should_answer_in_the_order_the_addresses_read() {
            seededBatch();
            final RegisterNotification wandsworth = pending(WANDSWORTH, "Wandsworth YOT");
            final RegisterNotification lambeth = pending(LAMBETH, "Lambeth YOT");
            repository.insert(wandsworth);
            repository.insert(lambeth);

            assertThat(repository.findByBatchId(batchId))
                    .as("ordered by address rather than by insertion, so a run report and a resend "
                            + "list a person compares by eye come back the same way twice")
                    .containsExactly(lambeth, wandsworth);
        }

        @Test
        void reading_the_resendable_recipients_should_answer_with_only_the_failed_ones() {
            seededBatch();
            final RegisterNotification accepted = pending(WANDSWORTH, "Wandsworth YOT");
            final RegisterNotification refused = pending(LAMBETH, "Lambeth YOT");
            repository.insert(accepted);
            repository.insert(refused);
            repository.update(settled(accepted, NotificationStatus.ACCEPTED, ACCEPTED, SENT_AT));
            final RegisterNotification failed =
                    settled(refused, NotificationStatus.FAILED, null, null);
            repository.update(failed);

            assertThat(repository.findFailedByBatchId(batchId))
                    .as("a resend attempts the recipients that were not told and nobody else; "
                            + "re-sending an accepted one would deliver the register twice")
                    .containsExactly(failed);
        }

        @Test
        void reading_a_batch_nobody_subscribes_to_should_answer_with_nothing() {
            seededBatch();

            assertThat(repository.findByBatchId(batchId))
                    .as("no recipients is an answer rather than an error - it is what settles the "
                            + "batch NOTIFIED_NOBODY, which is defect fix P1")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("settling a recipient on what notificationnotify answered")
    class Settling {

        @Test
        void settling_an_accepted_recipient_should_write_the_status_line_and_the_instant() {
            seededBatch();
            final RegisterNotification pending = pending(WANDSWORTH, "Wandsworth YOT");
            repository.insert(pending);
            final RegisterNotification accepted =
                    settled(pending, NotificationStatus.ACCEPTED, ACCEPTED, SENT_AT);

            assertThat(repository.update(accepted))
                    .as("the affected-row count is the decision, and never a read-back")
                    .isEqualTo(ONE_ROW);
            assertThat(repository.findByBatchId(batchId))
                    .as("202 and nothing else is acceptance, and the row says which attempt under "
                            + "this identity it was")
                    .containsExactly(accepted);
        }

        @Test
        void settling_a_recipient_nothing_answered_for_should_record_no_status_and_no_instant() {
            seededBatch();
            final RegisterNotification pending = pending(WANDSWORTH, "Wandsworth YOT");
            repository.insert(pending);
            final RegisterNotification failed =
                    settled(pending, NotificationStatus.FAILED, null, null);

            assertThat(repository.update(failed)).isEqualTo(ONE_ROW);

            assertThat(repository.findByBatchId(batchId))
                    .as("a connect failure has no status line and no settlement instant, and a row "
                            + "carrying an invented one would say an attempt was answered when "
                            + "nothing answered at all")
                    .containsExactly(failed);
        }

        @Test
        void settling_a_recipient_this_service_never_minted_should_change_nothing() {
            seededBatch();
            final RegisterNotification absent = pending(WANDSWORTH, "Wandsworth YOT");

            assertThat(repository.update(absent))
                    .as("nought rows changed is an identity this service never sent under, which "
                            + "is a caller settling an attempt that was never made")
                    .isZero();
        }
    }

    /**
     * The batch the notification rows hang off, walked to GENERATED through its own repository.
     *
     * <p>Walked rather than inserted there. A batch enters {@code register_batch} at PENDING and is
     * moved from PENDING to GENERATING to GENERATED by compare-and-set through the state machine,
     * and a fixture that wrote the end state directly would be the one caller in the codebase for
     * which those rules did not hold - and would go on passing after a change that made them
     * unreachable for everybody else. GENERATED is the state a notification run reads its batch in,
     * which is why these cases want it.
     */
    private void seededBatch() {
        batches.insert(batchAt(BatchStatus.PENDING, null, null));
        batches.compareAndSet(batchAt(BatchStatus.GENERATING, PAYLOAD_FILE_ID, REQUESTED_AT),
                BatchStatus.PENDING);
        batches.compareAndSet(batchAt(BatchStatus.GENERATED, PAYLOAD_FILE_ID, REQUESTED_AT),
                BatchStatus.GENERATING);
    }

    /** This suite's batch in one of the three states the walk above passes through. */
    private RegisterBatch batchAt(final BatchStatus status, final UUID payloadFileId,
            final Instant requestedAt) {
        return new RegisterBatch(batchId, courtCentre, OU_CODE, COURT_HOUSE, MONDAY,
                "court-register_" + MONDAY + '_' + OU_CODE + ".pdf", payloadFileId,
                status == BatchStatus.GENERATED ? DOCUMENT_FILE_ID : null,
                status, null, null, true, null, ASSEMBLED_AT, requestedAt,
                status == BatchStatus.GENERATED ? GENERATED_AT : null, null, null,
                status == BatchStatus.PENDING ? 0 : 1);
    }

    /** One recipient's row as it stands the moment before its POST is made. */
    private RegisterNotification pending(final String emailAddress, final String recipientName) {
        return new RegisterNotification(UUID.randomUUID(), batchId, emailAddress, recipientName,
                TEMPLATE_NAME, TEMPLATE_ID, NotificationStatus.PENDING, null, null, 0);
    }

    /** The same row after the attempt ended, under the identity it was first attempted with. */
    private static RegisterNotification settled(final RegisterNotification notification,
            final NotificationStatus status, final Integer responseCode, final Instant sentAt) {
        return new RegisterNotification(notification.notificationId(), notification.batchId(),
                notification.emailAddress(), notification.recipientName(),
                notification.templateName(), notification.templateId(), status, responseCode,
                sentAt, notification.attempts() + 1);
    }
}
