package uk.gov.hmcts.cp.courtregister.adapter.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.application.RegisterSubmission;
import uk.gov.hmcts.cp.courtregister.application.SubmissionReceipt;
import uk.gov.hmcts.cp.courtregister.config.JacksonConfig;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterAddress;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.courtregister.domain.FailureClassification;
import uk.gov.hmcts.cp.courtregister.domain.ProcessedOutputClaim;
import uk.gov.hmcts.cp.courtregister.domain.ReasonCode;
import uk.gov.hmcts.cp.courtregister.domain.RunClaim;
import uk.gov.hmcts.cp.courtregister.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.courtregister.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.courtregister.persistence.ProcessedOutputRepository;
import uk.gov.hmcts.cp.courtregister.support.CapturedLog;

/**
 * The order the submission leg does things in, which is the whole of its safety — the P1 twin,
 * repaired.
 *
 * <p>{@code ProcessOutboundCourtRegister.test.js} is one case and three defects. It stubs
 * {@code axios.post} with a promise that is constructed and never returned, so the mock resolves
 * {@code undefined} and the test observes no status at all; it asserts the {@code CJSCPPUID} header
 * as the literal {@code undefined}, because the fixture supplies no {@code cjscppuid}; and its
 * fixture still records a {@code .csv} file name the code has not produced since it became a PDF.
 * What ports from it is the wire contract — the path, the vendor media type, the identity header and
 * "the body is the aggregation" — and that half is settled against a socket in
 * {@link ProgressionCommandGatewayTest}. What is settled here is everything the legacy has no
 * concept of: the row that is written before the POST, and the outcome that is written after it.
 *
 * <p>{@code add-court-register} is not idempotent — every POST appends an event and a
 * {@code court_register_request} row. Three properties keep an at-least-once delivery safe to submit
 * from, and each is asserted rather than argued for in a comment.
 *
 * <ul>
 *   <li><strong>The row is claimed before the POST.</strong> A POST whose outcome is never learned —
 *       a timeout, a dropped connection — must still leave evidence that it was attempted, what was
 *       in it, and what the register was assembled without. Writing the row afterwards would lose
 *       exactly the case the evidence is for.</li>
 *   <li><strong>A register already POSTED is skipped.</strong> A replay of a request whose register
 *       went must not send a second one, and the claim statement is what decides that — in the
 *       database, in one statement, because two deliveries can be in flight at once.</li>
 *   <li><strong>A failure is recorded before it is rethrown</strong>, carrying the status
 *       progression answered. This is defect fix C1 made durable: the legacy catches the POST's
 *       errors, records nothing, and reports the run a success.</li>
 * </ul>
 *
 * <p>The digest is asserted against the bytes that actually went out, not against a re-serialisation
 * of the document. It exists for reconciliation and replay diffing, and a digest of something other
 * than what was sent is worse than none.
 *
 * <p>The transport is mocked here on purpose, and so is the repository. What the wire looks like is
 * settled against a real socket next door; what the statements do is settled against a real Postgres
 * in {@code ProcessedOutputRepositoryIT}; what is settled here is <em>ordering</em>, which neither of
 * those can show. That the three agree with each other and with the database is what
 * {@code SubmissionRedeliveryIT} exists for.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> row C1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Progression register submission client")
class ProgressionRegisterSubmissionClientTest {

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private static final String SOURCE = "RESULTS";
    private static final String COURT_CENTRE_ID = "853b1ff8-fc2a-44d1-a621-0cd16419f54a";
    private static final String OU_CODE = "B01LY00";
    private static final LocalDate REGISTER_DAY = LocalDate.of(2020, 6, 1);
    private static final String HEARING_ID = "1828f356-f746-4f2d-932b-79ef2df95c80";
    private static final String FILE_NAME =
            "court-register_2020-06-01_B01LY00_" + HEARING_ID + ".pdf";

    /** A child's name, which is what a register carries and what no log line may. */
    private static final String DEFENDANT_NAME = "SMITH, John";
    private static final String DEFENDANT_ADDRESS_LINE = "1 High Street";

    private static final int ACCEPTED = 202;
    private static final int REFUSED = 400;

    /** The run's caller, resolved once by the pipeline and passed through unchanged. */
    private static final CallerIdentity CALLER = new CallerIdentity(
            Optional.of(UUID.fromString("6e2f0a1c-9d4b-4f38-8a52-1c7b3e5d9f04")));

    @Mock
    private ProcessedOutputRepository outputs;

    @Mock
    private ProgressionCommandGateway gateway;

    private final RunClaim claim = new RunClaim(
            SOURCE, UUID.fromString("9f1b8e2a-5c34-4a7d-9b1e-2f6a0d3c5e71"),
            "runner-1", UUID.randomUUID(), "msg-1");

    private ProgressionRegisterSubmissionClient client() {
        return new ProgressionRegisterSubmissionClient(outputs, gateway, MAPPER);
    }

    private RegisterSubmission submission() {
        return submission(Map.of());
    }

    private RegisterSubmission submission(final Map<TransformationAnomaly, Integer> anomalies) {
        return new RegisterSubmission(
                claim, document(), OU_CODE, REGISTER_DAY, CALLER, anomalies);
    }

    private void claimGranted() {
        when(outputs.claimPending(eq(claim), any(ProcessedOutputClaim.class))).thenReturn(true);
    }

    @Nested
    @DisplayName("the happy path, in order")
    class Accepted {

        @Test
        @DisplayName("claims the row before the POST and marks it posted after")
        void the_row_is_claimed_before_the_post_and_marked_posted_after() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);

            client().submit(submission());

            final InOrder order = inOrder(outputs, gateway);
            order.verify(outputs).claimPending(eq(claim), any(ProcessedOutputClaim.class));
            order.verify(gateway).post(any(byte[].class), any(CallerIdentity.class));
            order.verify(outputs).recordPosted(claim, ACCEPTED);
            order.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("answers a receipt carrying the status, and saying this delivery sent it")
        void the_receipt_says_this_delivery_sent_it() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);

            final SubmissionReceipt receipt = client().submit(submission());

            assertThat(receipt).isEqualTo(new SubmissionReceipt(ACCEPTED, true));
        }

        @Test
        @DisplayName("posts the bytes this service produced from the document")
        void the_bytes_posted_are_the_document_this_service_produced() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);
            final ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);

            client().submit(submission());

            verify(gateway).post(sent.capture(), any(CallerIdentity.class));
            assertThat(new String(sent.getValue(), StandardCharsets.UTF_8))
                    .isEqualTo(MAPPER.writeValueAsString(document()));
        }

        /**
         * The adapter resolves no identity of its own. The run decided who it is made as, once, and
         * this leg passes it through — which is what {@code index.js:21} does with
         * {@code this.input.cjscppuid}, and what its one test asserts as {@code undefined}.
         */
        @Test
        @DisplayName("posts as the caller the run resolved")
        void the_caller_posted_as_is_the_one_the_run_resolved() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);
            final ArgumentCaptor<CallerIdentity> caller =
                    ArgumentCaptor.forClass(CallerIdentity.class);

            client().submit(submission());

            verify(gateway).post(any(byte[].class), caller.capture());
            assertThat(caller.getValue()).isEqualTo(CALLER);
        }

        @Test
        @DisplayName("writes the digest of exactly the bytes that went out")
        void the_digest_written_is_the_sha_256_of_the_bytes_that_went_out() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);
            final ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);

            client().submit(submission());

            verify(gateway).post(sent.capture(), any(CallerIdentity.class));
            assertThat(claimed().requestDigest()).isEqualTo(sha256(sent.getValue()));
        }

        @Test
        @DisplayName("names the register the row is about, before it is sent")
        void the_row_written_before_the_post_names_the_register_it_is_about() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);

            client().submit(submission());

            final ProcessedOutputClaim written = claimed();
            assertThat(written.outputId()).isNotNull();
            assertThat(written.courtCentreId()).isEqualTo(UUID.fromString(COURT_CENTRE_ID));
            assertThat(written.courtCentreOuCode())
                    .as("the OU code an operator searches the row by; the document has no field "
                            + "for it, so it travels beside it")
                    .isEqualTo(OU_CODE);
            assertThat(written.registerDate())
                    .as("the day the recipients were read for, not a second derivation of it (C12)")
                    .isEqualTo(REGISTER_DAY);
            assertThat(written.fileName()).isEqualTo(FILE_NAME);
        }

        /**
         * C19, C20 and C27. A register assembled with a part missing is still sent, and what was
         * skipped to send it is written down in the same statement as the digest — before the POST,
         * because the counts are worth more after a failure than after a success.
         */
        @Test
        @DisplayName("writes the counts of what the register survived, before it is sent")
        void the_counts_of_what_the_register_survived_are_written_before_it_is_sent() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);

            client().submit(submission(Map.of(
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED, 2,
                    TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT, 1)));

            assertThat(claimed().anomalies()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED, 2,
                    TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT, 1));
        }
    }

    @Nested
    @DisplayName("a register that has already gone")
    class AlreadyPosted {

        @Test
        @DisplayName("skips the POST entirely when the claim is refused")
        void a_refused_claim_skips_the_post_entirely() {
            when(outputs.claimPending(eq(claim), any(ProcessedOutputClaim.class)))
                    .thenReturn(false);

            client().submit(submission());

            verify(gateway, never()).post(any(byte[].class), any(CallerIdentity.class));
            verify(outputs, never()).recordPosted(any(RunClaim.class), org.mockito.ArgumentMatchers
                    .anyInt());
            verify(outputs, never()).recordFailed(any(RunClaim.class), any());
        }

        /**
         * The run still completes {@code submitted}, because the register has gone; the receipt says
         * this delivery is not what sent it, so nothing reports a POST that never left the pod.
         */
        @Test
        @DisplayName("answers a receipt that says it sent nothing")
        void a_skipped_post_answers_a_receipt_that_says_it_sent_nothing() {
            when(outputs.claimPending(eq(claim), any(ProcessedOutputClaim.class)))
                    .thenReturn(false);

            final SubmissionReceipt receipt = client().submit(submission());

            assertThat(receipt.sentByThisDelivery()).isFalse();
        }
    }

    @Nested
    @DisplayName("a submission that did not go — defect fix C1")
    class Failed {

        /**
         * The register row for C1. A 400 is the C29 shape reaching progression, and the legacy's
         * catch makes it indistinguishable from a delivered register: no status recorded, no row
         * moved, and a run that reports success. Here it is FAILED, carrying the status, before the
         * exception continues.
         */
        @Test
        @DisplayName("a_400_is_a_recorded_failure_never_silence")
        void a_400_is_a_recorded_failure_never_silence() {
            claimGranted();
            when(outputs.recordFailed(claim, REFUSED)).thenReturn(true);
            doThrow(new SubmissionFailedException(FailureClassification.NON_TRANSIENT,
                    ReasonCode.SUBMISSION_REJECTED, REFUSED))
                    .when(gateway).post(any(byte[].class), any(CallerIdentity.class));

            assertThatThrownBy(() -> client().submit(submission()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .extracting(failure -> ((SubmissionFailedException) failure).reason())
                    .isEqualTo(ReasonCode.SUBMISSION_REJECTED);

            final InOrder order = inOrder(gateway, outputs);
            order.verify(gateway).post(any(byte[].class), any(CallerIdentity.class));
            order.verify(outputs).recordFailed(claim, REFUSED);
            verify(outputs, never()).recordPosted(any(RunClaim.class),
                    org.mockito.ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("a transient failure is recorded before it is rethrown")
        void a_transient_failure_is_recorded_before_it_is_rethrown() {
            claimGranted();
            when(outputs.recordFailed(claim, 503)).thenReturn(true);
            doThrow(new SubmissionFailedException(FailureClassification.TRANSIENT,
                    ReasonCode.SUBMISSION_TRANSIENT, 503))
                    .when(gateway).post(any(byte[].class), any(CallerIdentity.class));

            assertThatThrownBy(() -> client().submit(submission()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .extracting(failure -> ((SubmissionFailedException) failure).classification())
                    .isEqualTo(FailureClassification.TRANSIENT);

            verify(outputs).recordFailed(claim, 503);
        }

        /**
         * A connect failure or a timeout has no status line, and the row says so rather than
         * carrying an invented one: a PENDING-turned-FAILED row with no {@code response_code} is
         * exactly the state that warns a duplicate is possible.
         */
        @Test
        @DisplayName("records no status where progression never answered")
        void a_failure_with_no_answer_records_no_status() {
            claimGranted();
            when(outputs.recordFailed(claim, null)).thenReturn(true);
            doThrow(new SubmissionFailedException(FailureClassification.TRANSIENT,
                    ReasonCode.SUBMISSION_TRANSIENT))
                    .when(gateway).post(any(byte[].class), any(CallerIdentity.class));

            assertThatThrownBy(() -> client().submit(submission()))
                    .isInstanceOf(SubmissionFailedException.class);

            verify(outputs).recordFailed(claim, null);
        }
    }

    /**
     * The outcome write is the durable half of a submission, and it is checked rather than assumed.
     *
     * <p>A claim was granted moments before, so the only way one of these statements can affect no
     * row is that a delivery this one overlapped with reached the row first and POSTED it — POSTED
     * being terminal in the log — or that this runner's claim was reclaimed while it worked. Either
     * means two runners were working the same request, which is worth an ERROR: what this runner
     * believes happened is not what the log durably says.
     */
    @Nested
    @DisplayName("an outcome write that lands on nothing")
    class OutcomeNotRecorded {

        @Test
        @DisplayName("a POST that recorded nothing is reported rather than assumed written")
        void a_post_that_recorded_nothing_is_reported_rather_than_assumed_written() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(false);

            try (CapturedLog log =
                         CapturedLog.capturing(ProgressionRegisterSubmissionClient.class)) {
                client().submit(submission());

                assertThat(log.renderings())
                        .anyMatch(line -> line.contains("Outcome write affected no row"));
            }
        }

        @Test
        @DisplayName("a failure that recorded nothing still reaches the pipeline")
        void a_failure_that_recorded_nothing_still_reaches_the_pipeline() {
            claimGranted();
            when(outputs.recordFailed(claim, REFUSED)).thenReturn(false);
            doThrow(new SubmissionFailedException(FailureClassification.NON_TRANSIENT,
                    ReasonCode.SUBMISSION_REJECTED, REFUSED))
                    .when(gateway).post(any(byte[].class), any(CallerIdentity.class));

            try (CapturedLog log =
                         CapturedLog.capturing(ProgressionRegisterSubmissionClient.class)) {
                assertThatThrownBy(() -> client().submit(submission()))
                        .isInstanceOf(SubmissionFailedException.class);

                assertThat(log.renderings())
                        .as("a failure the log could not record is still a failure")
                        .anyMatch(line -> line.contains("Outcome write affected no row"));
            }
        }
    }

    @Nested
    @DisplayName("what the log is allowed to say")
    class Privacy {

        @Test
        @DisplayName("no line carries the document or anyone named in it")
        void no_line_carries_the_document_or_anyone_named_in_it() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);

            try (CapturedLog log =
                         CapturedLog.capturing(ProgressionRegisterSubmissionClient.class)) {
                client().submit(submission());

                assertThat(log.renderings())
                        .as("every defendant on this register is a child")
                        .noneMatch(line -> line.contains(DEFENDANT_NAME))
                        .noneMatch(line -> line.contains(DEFENDANT_ADDRESS_LINE));
            }
        }

        @Test
        @DisplayName("the caller never reaches a log line")
        void the_identity_never_reaches_a_log_line() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);

            try (CapturedLog log =
                         CapturedLog.capturing(ProgressionRegisterSubmissionClient.class)) {
                client().submit(submission());

                assertThat(log.renderings()).noneMatch(
                        line -> line.contains(CALLER.userId().orElseThrow().toString()));
            }
        }
    }

    /** The claim the adapter wrote, which is the only place several of these facts appear. */
    private ProcessedOutputClaim claimed() {
        final ArgumentCaptor<ProcessedOutputClaim> written =
                ArgumentCaptor.forClass(ProcessedOutputClaim.class);
        verify(outputs).claimPending(eq(claim), written.capture());
        return written.getValue();
    }

    private static String sha256(final byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A register with a child on it, so the privacy cases have something real to look for. */
    private static CourtRegisterDocument document() {
        final CourtRegisterDefendant defendant = new CourtRegisterDefendant(
                "b2b3f5a1-6c9d-4e21-8a7f-3d5c1e9b0426", DEFENDANT_NAME, "2008-04-11",
                new CourtRegisterAddress(
                        DEFENDANT_ADDRESS_LINE, null, null, null, null, "BS1 1AA"),
                null, null, "MALE", "Not Applicable", null, null,
                List.of(), List.of(), List.of(), List.of());
        final CourtRegisterRecipient recipient = new CourtRegisterRecipient(
                "Youth Offending Team", "yot@example.gov.uk", null, "cr_standard");
        return new CourtRegisterDocument(
                "2020-06-01T10:00:00Z",
                "2020-01-20T00:00:00Z",
                HEARING_ID,
                COURT_CENTRE_ID,
                FILE_NAME,
                null,
                List.of(recipient),
                List.of(defendant));
    }
}
