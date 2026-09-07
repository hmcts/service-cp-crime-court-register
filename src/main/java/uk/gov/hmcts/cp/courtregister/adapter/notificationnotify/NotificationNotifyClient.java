package uk.gov.hmcts.cp.courtregister.adapter.notificationnotify;

import java.util.UUID;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.application.NotificationOutcome;
import uk.gov.hmcts.cp.courtregister.application.RegisterNotifier;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.NotificationFailedException;
import uk.gov.hmcts.cp.courtregister.domain.RegisterNotification;

/**
 * The notifier port, wired to notificationnotify's command API.
 *
 * <p>{@code POST {NN}/notificationnotify-command-api/command/api/rest/notificationnotify/
 * notifications/{notificationId}} with media type
 * {@code application/vnd.notificationnotify.email+json}, carrying the caller identity as
 * {@code CJSCPPUID} and classifying its one attempt through the shared
 * {@code adapter/http/RetryPolicy}, so this client cannot hold a different opinion about what is
 * worth asking again than the clients 001 and the renderer already built (defect fix C3).
 *
 * <p><strong>The identity is in the path and not in the body.</strong> The API-side schema
 * {@code notificationnotify.email.json} is {@code additionalProperties: false} and declares no
 * {@code notificationId}: the framework lifts it off the path and adds it before the internal
 * {@code notificationnotify.send-email-notification} command reaches its handler. A body carrying
 * one of its own would be a 400 rather than a field notificationnotify ignored.
 *
 * <p>The body is therefore four fields: {@code templateId} as the row records it,
 * {@code sendToAddress} the recipient's own address, {@code fileId} the rendered document's
 * file-service id - the register travels by reference, so a document about children is never carried
 * through this service twice - and {@code personalisation.yotsName} the recipient's name, which is
 * what the template greets.
 *
 * <p><strong>202 and nothing else is success.</strong> A 2xx that is not 202 means something other
 * than the command endpoint answered - a proxy, or a route that no longer reaches it - and calling it
 * success would settle a row ACCEPTED for an e-mail nobody was asked to send. It is non-transient,
 * and the recipient's row is failed with the status that made it one.
 *
 * <p><strong>A retry reuses the same {@code notificationId}, which is why it is in the path.</strong>
 * notificationnotify keys its {@code Notification} aggregate on that id, so a second POST under the
 * id the row was minted with reaches the attempt it is retrying; a fresh one would send a second
 * e-mail to the same Youth Offending Team (research §10).
 *
 * <p>One attempt per call, classified and handed back, exactly as the renderer's client does it: the
 * waiting and the counting belong to the object that holds the run's budget, and a client that
 * retried underneath it would spend a budget it cannot see.
 *
 * <p>Nothing that identifies a recipient is logged. The address and the recipient name are the two
 * components that never reach a line at INFO or above (constitution Principle VII), so a line here
 * carries the notification id, the batch id and a status.
 *
 * <p><strong>Seam only.</strong> The client lands with T058; until then {@code send} throws, so that
 * {@code NotificationNotifyClientTest} records a failing assertion rather than a compile error. The
 * constructor is the real one from the start, because it is the surface the WireMock suite builds
 * the client on and the surface {@code config/LiveNotificationConfig} wires the endpoint and the
 * identity through.
 */
// PMD.UnusedPrivateField: the three collaborators are held from the seam onwards so that the test
// and the configuration are written against the constructor they will keep, and `send` is what
// reads them - which is T058. The suppression goes with the throw.
@SuppressWarnings("PMD.UnusedPrivateField")
public class NotificationNotifyClient implements RegisterNotifier {

    /**
     * The command's path under the notificationnotify context, exactly as its RAML declares it.
     *
     * <p>The notification's own identity is the path parameter, and the whole of what makes a retry
     * idempotent on the other side.
     */
    public static final String COMMAND_PATH =
            "/notificationnotify-command-api/command/api/rest/notificationnotify/notifications/"
                    + "{notificationId}";

    /** The command's vendor media type; the framework routes on it, so it is not a formality. */
    public static final String EMAIL_MEDIA_TYPE = "application/vnd.notificationnotify.email+json";

    /**
     * The CPP identity header. Its value is never logged - it is either a secret or a user
     * identifier, and neither belongs in a log index.
     */
    public static final String IDENTITY_HEADER = "CJSCPPUID";

    /** The one status the contract calls success. */
    public static final int ACCEPTED = 202;

    /** The client, carrying the notificationnotify base URL and its timeouts. */
    private final RestClient restClient;

    /** The {@code CJSCPPUID} identity for a run naming no user; a secret, never logged. */
    private final String systemUserId;

    /** The shared mapper, so a body is written exactly as every other JSON in this service is. */
    private final ObjectMapper objectMapper;

    /**
     * Builds the client over an already-configured HTTP client.
     *
     * @param restClient   the client, carrying the notificationnotify base URL and its timeouts
     * @param systemUserId the {@code CJSCPPUID} identity for a run naming no user; a secret, never
     *                     logged
     * @param objectMapper the shared mapper, so the command body is written exactly as every other
     *                     JSON in this service is
     */
    public NotificationNotifyClient(final RestClient restClient, final String systemUserId,
            final ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.systemUserId = systemUserId;
        this.objectMapper = objectMapper;
    }

    @Override
    public NotificationOutcome send(final RegisterNotification notification,
            final UUID documentFileId, final CallerIdentity caller)
            throws NotificationFailedException {
        throw new UnsupportedOperationException("T058");
    }
}
