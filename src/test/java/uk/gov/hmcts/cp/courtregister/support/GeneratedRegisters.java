package uk.gov.hmcts.cp.courtregister.support;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.GuardDecision;
import uk.gov.hmcts.cp.courtregister.domain.ReasonCode;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.domain.RequestFingerprint;
import uk.gov.hmcts.cp.courtregister.domain.RunClaim;

/**
 * A night's registers, seeded through the running service's own store, and read straight back out.
 *
 * <p>The two end-to-end generation suites start where the intake half has finished: the pipeline
 * that turns a hearing into a register has its own suites and its own stack, and standing the whole
 * of it up to produce a row would make every assertion about the nightly run depend on the twelve
 * mappers in front of it. So the registers are recorded through {@link RegisterStore} - the same
 * port and the same statement the pipeline's last stage uses - and everything after that is the
 * service's own.
 *
 * <p>Each instance owns one court centre, minted by the suite per case, so a case reads only its
 * own rows and no case has to empty a table another is using.
 */
public final class GeneratedRegisters {

    /** The zone the register day is the date part of. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /** The claim lease the seeded request is written under; nothing here reclaims one. */
    private static final Duration LEASE = Duration.ofMinutes(5);

    /** The court centre's OU code, which the batch copies off the register's own row. */
    private static final String OU_CODE = "B01LY00";

    /** The side of a court application this suite's defendants are on. */
    private static final String APPLICANT = "Applicant";

    /** The hearing date every seeded register carries; the batch groups by the register day. */
    private static final Instant HEARING_DATE = Instant.parse("2026-08-19T00:00:00Z");

    /**
     * The one subscribed Youth Offending Team a register carries unless a case says otherwise.
     *
     * <p>A case that is about who a batch is addressed to names its own recipients; every other case
     * gets this one, which is the ordinary night - a court centre with a single subscription.
     */
    public static final CourtRegisterRecipient SUBSCRIBED_TEAM = new CourtRegisterRecipient(
            "Wandsworth Youth Offending Team", "yot@example.gov.uk", null, "cr_standard");

    /** The completion the store writes beside the recording, admitted and writing nothing else. */
    private static final java.util.function.Supplier<GuardDecision> COMPLETED =
            () -> new GuardDecision.Complete(ReasonCode.RUN_COMPLETED);

    private final RegisterStore store;

    private final UUID courtCentre;

    /**
     * Binds the fixture to a running service and one court centre.
     *
     * @param service     the running context, for the store bean the pipeline records through
     * @param courtCentreId this case's court centre
     */
    public GeneratedRegisters(final ApplicationContext service, final UUID courtCentreId) {
        this.store = service.getBean(RegisterStore.class);
        this.courtCentre = courtCentreId;
    }

    /**
     * The file name every register this fixture records carries, and therefore the batch's.
     *
     * @param registerDate the day the register falls on
     * @return the name the day's document is filed under
     */
    public static String fileNameFor(final LocalDate registerDate) {
        return "court-register_" + registerDate + '_' + OU_CODE + ".pdf";
    }

    /**
     * Records one hearing's register, RECORDED and recorded while the flag said ON.
     *
     * @param hearingId    the hearing the register is about
     * @param registerDate the day it falls on, which is the batch key
     * @param registerTime the instant the results were shared, which orders two re-shares
     */
    public void record(final UUID hearingId, final LocalDate registerDate,
            final Instant registerTime) {

        record(hearingId, registerDate, registerTime, List.of(SUBSCRIBED_TEAM));
    }

    /**
     * Records one hearing's register for a named set of matched Youth Offending Teams.
     *
     * <p>What a batch is addressed to is the union across its records (defect fix P4), so a case
     * about the notifying leg has to be able to say which teams each of the day's hearings matched -
     * including a record that matched nobody, which is a hearing with no subscription and not an
     * error.
     *
     * @param hearingId    the hearing the register is about
     * @param registerDate the day it falls on, which is the batch key
     * @param registerTime the instant the results were shared, which orders two re-shares
     * @param recipients   the teams this hearing matched, in the order the register carries them
     */
    public void record(final UUID hearingId, final LocalDate registerDate,
            final Instant registerTime, final List<CourtRegisterRecipient> recipients) {

        final DistributionCommand command = new DistributionCommand(
                ProcessedLogTestSupport.SOURCE, UUID.randomUUID(), hearingId,
                LocalDate.ofInstant(registerTime, LONDON), registerTime, "Hearing_Resulted");
        ProcessedLogTestSupport.repository(LEASE).insertNew(
                command,
                RequestFingerprint.of(command),
                new RunClaim(command.source(), command.requestId(), "e2e-runner",
                        UUID.randomUUID(), "e2e-msg"));
        store.recordAndComplete(command, document(hearingId, registerTime, recipients), OU_CODE,
                APPLICANT, RecordedFlagState.ON, COMPLETED);
    }

    /** The statuses this court centre's registers now hold, oldest first. */
    public List<String> statuses() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT status
                          FROM processed_output
                         WHERE court_centre_id = :courtCentre
                         ORDER BY register_time, output_id
                        """)
                .param("courtCentre", courtCentre)
                .query(String.class)
                .list();
    }

    /** The batches assembled for this court centre, oldest first. */
    public List<UUID> batches() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT batch_id
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre
                         ORDER BY assembled_at, batch_id
                        """)
                .param("courtCentre", courtCentre)
                .query(UUID.class)
                .list();
    }

    /** The states those batches are in, in the same order. */
    public List<String> batchStatuses() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT status
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre
                         ORDER BY assembled_at, batch_id
                        """)
                .param("courtCentre", courtCentre)
                .query(String.class)
                .list();
    }

    /** The payload id this court centre's only batch was given, where it has one. */
    public Optional<UUID> payloadFileId() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT payload_file_id
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre
                        """)
                .param("courtCentre", courtCentre)
                .query(UUID.class)
                .optional();
    }

    /** The document id this court centre's only batch ended with, where it has one. */
    public Optional<UUID> documentFileId() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT document_file_id
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre
                        """)
                .param("courtCentre", courtCentre)
                .query(UUID.class)
                .optional();
    }

    /** Which mechanism this court centre's only batch was completed by, where it is complete. */
    public Optional<String> completedBy() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT completed_by
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre
                        """)
                .param("courtCentre", courtCentre)
                .query(String.class)
                .optional();
    }

    /** The bounded reason this court centre's only batch was failed under, where it failed. */
    public Optional<String> failureReason() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT failure_reason
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre
                        """)
                .param("courtCentre", courtCentre)
                .query(String.class)
                .optional();
    }

    /** systemdocgenerator's own words about the refusal, where it gave any. */
    public Optional<String> sdgReason() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT sdg_reason
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre
                        """)
                .param("courtCentre", courtCentre)
                .query(String.class)
                .optional();
    }

    /**
     * The {@code register_notification} rows this court centre's batches hold, by address.
     *
     * <p>Ordered the way {@code RegisterNotificationRepository} orders its own read, so what a case
     * asserts and what a resend or a run report would list come back the same way.
     *
     * @return one row per distinct recipient of this court centre's batches
     */
    public List<Notified> notifications() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT n.notification_id, n.email_address, n.recipient_name,
                               n.template_name, n.status, n.response_code, n.attempts
                          FROM register_notification n
                          JOIN register_batch b ON b.batch_id = n.batch_id
                         WHERE b.court_centre_id = :courtCentre
                         ORDER BY n.email_address
                        """)
                .param("courtCentre", courtCentre)
                .query((rs, rowNumber) -> new Notified(
                        rs.getObject("notification_id", UUID.class),
                        rs.getString("email_address"),
                        rs.getString("recipient_name"),
                        rs.getString("template_name"),
                        rs.getString("status"),
                        rs.getObject("response_code", Integer.class),
                        rs.getInt("attempts")))
                .list();
    }

    /**
     * Puts this court centre's batches' render request further into the past than it was.
     *
     * <p>The only way a suite can reach the grace-period reconciler without shortening the grace
     * period itself, and it is the honest one: the rule is "how long ago was the render asked for",
     * so a batch that has been waiting longer than the grace allows is the input, and moving the
     * stamp is how a test states that without waiting ten real minutes. Shortening the configured
     * grace instead would make every other suite's in-flight batch overdue as well, on a store all
     * of them share.
     *
     * @param waited how long ago the render should look as though it was asked for
     */
    public void hasBeenWaitingFor(final Duration waited) {
        ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        UPDATE register_batch
                           SET requested_at = now() - CAST(:waited AS interval)
                         WHERE court_centre_id = :courtCentre
                        """)
                .param("waited", waited.toSeconds() + " seconds")
                .param("courtCentre", courtCentre)
                .update();
    }

    /**
     * One recipient's row, as the notifying leg left it.
     *
     * @param notificationId the identity the row was minted with, which is the POST's path
     * @param emailAddress   the address the e-mail was addressed to
     * @param recipientName  what the template greets that recipient by
     * @param templateName   the logical template the e-mail was sent under
     * @param status         PENDING, ACCEPTED or FAILED
     * @param responseCode   what notificationnotify answered, or {@code null} where nothing did
     * @param attempts       how many times this row has been posted for
     */
    public record Notified(UUID notificationId, String emailAddress, String recipientName,
            String templateName, String status, Integer responseCode, int attempts) {
    }

    /**
     * One hearing's register for this fixture's court centre, as the pipeline would hand it over.
     *
     * <p>The smallest document the frozen contract admits and the payload mapper renders: a venue,
     * the recipients the caller named and one youth defendant with nothing on the charge sheet. What
     * these suites are about is what happens to a batch, and a document with more in it would make
     * the assertions depend on the twelve mappers that built it.
     */
    private CourtRegisterDocument document(final UUID hearingId, final Instant registerTime,
            final List<CourtRegisterRecipient> recipients) {

        return new CourtRegisterDocument(
                registerTime.toString(),
                HEARING_DATE.toString(),
                hearingId.toString(),
                courtCentre.toString(),
                fileNameFor(LocalDate.ofInstant(registerTime, LONDON)),
                null,
                new CourtRegisterHearingVenue("Lavender Hill LJA", "Lavender Hill Youth Court",
                        null),
                recipients,
                List.of(new CourtRegisterDefendant(
                        "b2b3f5a1-6c9d-4e21-8a7f-3d5c1e9b0426", "SMITH, John", "2008-04-11",
                        null, null, null, "MALE", "Not Applicable", null, null,
                        List.of(), List.of(), List.of(), List.of())));
    }
}
