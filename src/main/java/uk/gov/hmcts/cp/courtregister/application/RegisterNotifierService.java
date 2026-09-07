package uk.gov.hmcts.cp.courtregister.application;

import java.time.Clock;
import java.util.UUID;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterNotificationRepository;

/**
 * One generated batch, from its recipients to the e-mails they are told by.
 *
 * <p>The order is the discipline the rest of the service keeps: the batch's recipients are the
 * de-duplicated union across its records ({@code batch/RecipientSet}, defect fix P4), a
 * {@code register_notification} row is minted PENDING for each of them with the
 * {@code notificationId} its POST will be made under, and only then is anything sent. An id used in
 * a call and written down afterwards is an e-mail this service cannot show it asked for, and a retry
 * under a fresh id is a second e-mail to the same Youth Offending Team (research §10).
 *
 * <p>Each recipient is settled on its own: ACCEPTED where notificationnotify answered 202, FAILED
 * with the status otherwise, and the next recipient is asked either way, because one team's refusal
 * says nothing about another team's e-mail. The batch is then settled from the tally alone - all
 * accepted is NOTIFIED, some failed is PARTIALLY_NOTIFIED, and no recipients at all is
 * NOTIFIED_NOBODY.
 *
 * <p><strong>Defect fix P1: no recipients is a terminal state, not a wait.</strong> The progression
 * leg leaves a batch nobody subscribes to sitting generated for ever, waiting on an event nobody
 * publishes. NOTIFIED_NOBODY says plainly that the document was rendered and there was nobody to
 * send it to, and it is counted rather than inferred from an absence.
 *
 * <p>{@link #resendFailed} is the second half of that honesty: a PARTIALLY_NOTIFIED batch keeps its
 * FAILED rows under the identities they were first attempted with, so a resend re-requests those
 * rows and no others - the teams that were told are not told twice - and the batch reaches NOTIFIED
 * when the last of them is accepted.
 *
 * <p>Every line this service writes carries ids and bounded codes. A recipient's address and name
 * never reach a log at INFO or above and never a metric label (constitution Principle VII); they
 * live in the notification row, which is the only place that may hold them.
 *
 * <p><strong>Seam only.</strong> The service lands with T059; until then both methods throw, so that
 * {@code RegisterNotifierServiceTest} records a failing assertion rather than a compile error. The
 * constructor is the real one from the start, for the reason
 * {@code adapter/notificationnotify/NotificationNotifyClient}'s is: it is the surface the unit suite
 * builds the service on and the surface {@code config/GenerationConfig} will wire the template id
 * through, so landing it with the test is what makes T056 writable before T059 exists.
 */
// PMD.UnusedPrivateField: the seven collaborators are held from the seam onwards so that the test
// and the configuration are written against the constructor they will keep, and the two methods
// below are what read them - which is T059. The suppression goes with the throws.
@SuppressWarnings("PMD.UnusedPrivateField")
public class RegisterNotifierService {

    /**
     * The logical template name written to {@code register_notification.template_name}.
     *
     * <p>The name the environment configures the id under, in
     * {@code courtregister.email.templates.cr_standard}, and the value data-model.md gives the
     * column: the row says which template an e-mail was sent under and not only which UUID.
     */
    public static final String TEMPLATE_NAME = "cr_standard";

    /** Where the batch's registers are read from and where the batch is settled. */
    private final RegisterStore store;

    /** The {@code register_batch} read that gives the generated document's file-service id. */
    private final RegisterBatchRepository batches;

    /** The {@code register_notification} rows: minted before a POST, settled after one. */
    private final RegisterNotificationRepository notifications;

    /** notificationnotify, behind the port that names neither HTTP nor a template body. */
    private final RegisterNotifier notifier;

    /** Where each recipient and each terminal batch state is counted. */
    private final GenerationMetrics metrics;

    /**
     * The {@code cr_standard} template id, validated for shape at startup (defect fix P9).
     *
     * <p>Resolved once at wiring rather than per recipient, which is the whole of what P9 is about:
     * the legacy resolved it per e-mail and, finding it blank, logged a line and moved on.
     */
    private final UUID templateId;

    /** This pod's reading of now, which is what {@code sent_at} records. */
    private final Clock clock;

    /**
     * Creates the service over the one store, the two tables it reads and the one notifier.
     *
     * @param registerStore         where the batch's registers are read from and where the batch is
     *                              settled on its tally
     * @param registerBatches       the {@code register_batch} read that gives the generated
     *                              document's file-service id
     * @param registerNotifications the {@code register_notification} rows, minted before a POST and
     *                              settled after one
     * @param registerNotifier      notificationnotify, behind the port
     * @param generationMetrics     where each recipient and each terminal batch state is counted
     * @param crStandardTemplateId  the {@code cr_standard} template id, resolved once at wiring
     * @param runClock              this pod's reading of now, which is what {@code sent_at} records
     */
    public RegisterNotifierService(final RegisterStore registerStore,
            final RegisterBatchRepository registerBatches,
            final RegisterNotificationRepository registerNotifications,
            final RegisterNotifier registerNotifier,
            final GenerationMetrics generationMetrics,
            final UUID crStandardTemplateId,
            final Clock runClock) {
        this.store = registerStore;
        this.batches = registerBatches;
        this.notifications = registerNotifications;
        this.notifier = registerNotifier;
        this.metrics = generationMetrics;
        this.templateId = crStandardTemplateId;
        this.clock = runClock;
    }

    /**
     * Tells every recipient of one generated batch, and settles the batch on the tally.
     *
     * @param batchId the batch whose document has been generated
     * @return how many recipients were accepted, how many failed, and the terminal state the batch
     *     is settled in
     */
    public NotificationSummary notify(final UUID batchId) {
        throw new UnsupportedOperationException("T059");
    }

    /**
     * Re-requests only the recipients whose e-mail ended FAILED, under the identities they already
     * hold.
     *
     * @param batchId the batch to resend for
     * @return the tally over the whole batch as it now stands, and the terminal state that produces
     */
    public NotificationSummary resendFailed(final UUID batchId) {
        throw new UnsupportedOperationException("T059");
    }
}
