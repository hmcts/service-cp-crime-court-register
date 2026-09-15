package uk.gov.hmcts.cp.courtregister.adapter.report;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import uk.gov.hmcts.cp.courtregister.application.ExceptionReportSink;
import uk.gov.hmcts.cp.courtregister.application.PayloadFileStore;
import uk.gov.hmcts.cp.courtregister.application.ReportMailer;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionReport;
import uk.gov.hmcts.cp.courtregister.domain.ReportSinkName;

/**
 * The report, written as a CSV into the file service and e-mailed to support by id.
 *
 * <p>The detail travels as the attachment and the summary travels in the body: Notify's body has a
 * length limit a bad morning's list would exceed, renders no table, and a truncated list of
 * exceptions is the silence this service exists to end (research §4).
 *
 * <p>It holds two ports and no HTTP type of its own, which is why its own suite is a unit one.
 *
 * <p><strong>The body is not written yet.</strong> This is the seam the CSV's composition and the
 * one-send-per-recipient fold are asserted against; until they land it refuses rather than
 * pretending, because a sink that answered "delivered" while sending nothing is the exact reading
 * this feature exists to make impossible.
 */
// PMD.UnusedPrivateField: the seam holds what the body is going to hold, so the two suites that
// own it are written against the constructor it will keep rather than against a narrower one that
// would have to change under them. The suppression goes when the body lands.
@SuppressWarnings("PMD.UnusedPrivateField")
public class EmailReportSink implements ExceptionReportSink {

    /** Where the CSV goes, so that notificationnotify can attach what is under the id. */
    private final PayloadFileStore files;

    /** Who sends it, one call per recipient. */
    private final ReportMailer mailer;

    /** The notificationnotify template the report is sent under, from configuration. */
    private final UUID templateId;

    /** The addresses it goes to, resolved from configuration and never a value in this repo. */
    private final List<String> recipients;

    /**
     * Holds the two ports and the two settings that say what is sent and to whom.
     *
     * @param files      the file service, written to through the port
     * @param mailer     the report's own send
     * @param templateId the template the report is sent under
     * @param recipients the addresses it goes to, one send each
     */
    public EmailReportSink(final PayloadFileStore files, final ReportMailer mailer,
            final UUID templateId, final List<String> recipients) {
        this.files = Objects.requireNonNull(files, "the file store is required");
        this.mailer = Objects.requireNonNull(mailer, "the mailer is required");
        this.templateId = templateId;
        this.recipients = List.copyOf(recipients);
    }

    @Override
    public ReportSinkName name() {
        return ReportSinkName.EMAIL;
    }

    @Override
    public DeliveryOutcome deliver(final ExceptionReport report) {
        throw new UnsupportedOperationException("the report's e-mail is not written yet");
    }
}
