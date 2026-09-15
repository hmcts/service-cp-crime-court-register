package uk.gov.hmcts.cp.courtregister.adapter.notificationnotify;

import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.application.ReportMailer;
import uk.gov.hmcts.cp.courtregister.domain.MailOutcome;
import uk.gov.hmcts.cp.courtregister.domain.ReportMail;

/**
 * The report's own send, wired to the same notificationnotify command the register leg uses.
 *
 * <p>Beside {@link NotificationNotifyClient} and not inside it: that client's argument is a batch's
 * notification row and its body carries {@code personalisation.yotsName}, and bending it around a
 * report would put an "unless it is a report" branch on the one path in this service that e-mails
 * Youth Offending Teams about real registers (research §4).
 *
 * <p>What the two share is the <strong>shape</strong> - the URI with the notification id as its
 * path parameter, the {@code application/vnd.notificationnotify.email+json} media type, the
 * {@code CJSCPPUID} identity header and the 202-and-nothing-else rule - and they share it through a
 * package-private request builder both call, so "the same shape" is a fact rather than a claim.
 *
 * <p><strong>The send is not written yet.</strong> This is the seam the body, the media type, the
 * identity and the classification are asserted against.
 */
// PMD.UnusedPrivateField: the seam holds what the body is going to hold, so the suite that owns it
// is written against the constructor it will keep. The suppression goes when the body lands.
@SuppressWarnings("PMD.UnusedPrivateField")
public class NotificationNotifyReportMailer implements ReportMailer {

    /** The client, carrying the notificationnotify base URL and its timeouts. */
    private final RestClient restClient;

    /** The {@code CJSCPPUID} identity a run naming no user is made under; never logged. */
    private final String systemUserId;

    /** The shared mapper, so this body is written exactly as every other JSON in this service. */
    private final ObjectMapper objectMapper;

    /**
     * Builds the mailer over an already-configured HTTP client.
     *
     * @param restClient   the client, carrying the notificationnotify base URL and its timeouts
     * @param systemUserId the identity the POST is made under; a secret, never logged
     * @param objectMapper the shared mapper
     */
    public NotificationNotifyReportMailer(final RestClient restClient, final String systemUserId,
            final ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.systemUserId = systemUserId;
        this.objectMapper = objectMapper;
    }

    @Override
    public MailOutcome send(final ReportMail mail) {
        throw new UnsupportedOperationException("the report's send is not written yet");
    }
}
