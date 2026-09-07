package uk.gov.hmcts.cp.courtregister.application;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.courtregister.adapter.http.RetryPause;
import uk.gov.hmcts.cp.courtregister.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.courtregister.batch.RecipientSet;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.courtregister.domain.FailureClassification;
import uk.gov.hmcts.cp.courtregister.domain.NotificationFailedException;
import uk.gov.hmcts.cp.courtregister.domain.NotificationStatus;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterNotification;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterNotificationRepository;

/**
 * One generated batch, from its recipients to the e-mails they are told by.
 *
 * <p>The order is the discipline the rest of the service keeps: the batch's recipients are the
 * de-duplicated union across its records ({@link RecipientSet}, defect fix P4), a
 * {@code register_notification} row is minted PENDING for each of them with the
 * {@code notificationId} its POST will be made under, and only then is anything sent. An id used in
 * a call and written down afterwards is an e-mail this service cannot show it asked for, and a retry
 * under a fresh id is a second e-mail to the same Youth Offending Team (research §10).
 *
 * <p>Each recipient is settled on its own: ACCEPTED where notificationnotify answered 202, FAILED
 * with the status otherwise, and the next recipient is asked either way, because one team's refusal
 * says nothing about another team's e-mail. The batch is then settled from the tally alone - all
 * accepted is NOTIFIED, some not is PARTIALLY_NOTIFIED, and no recipients at all is
 * NOTIFIED_NOBODY.
 *
 * <p><strong>A refusal that may answer differently is asked again first.</strong> A 503, a 429 or a
 * read timeout is a moment in notificationnotify's night and not a verdict about the e-mail, so the
 * POST is repeated up to {@code courtregister.endpoints.max-attempts} with the shared
 * {@link RetryPolicy}'s back-off between attempts, under the same notification id, and the row is
 * settled FAILED with the last status only once that budget is spent. A NON_TRANSIENT refusal is
 * not repeated at all: the same command under the same identity will be declined again, and waiting
 * to prove it costs the teams after this one. The loop is here rather than in the client for the
 * reason the generation leg's is - the client classifies one attempt and knows nothing about the
 * budget - and it is the same policy object both legs are given, so the taxonomy is stated once
 * (defect fix C3).
 *
 * <p><strong>Defect fix P1: no recipients is a terminal state, not a wait.</strong> The progression
 * leg leaves a batch nobody subscribes to sitting generated for ever, waiting on an event nobody
 * publishes. NOTIFIED_NOBODY says plainly that the document was rendered and there was nobody to
 * send it to, and it is counted rather than inferred from an absence.
 *
 * <p><strong>Defect fix P9's run-time half is here too.</strong> The template is resolved once at
 * wiring and a per-e-mail failure is a FAILED row carrying the status that came back, so a batch
 * with any recipient it could not tell ends PARTIALLY_NOTIFIED rather than reporting the state it
 * would have reported had everybody been e-mailed.
 *
 * <p>{@link #resendFailed} is the second half of that honesty: a PARTIALLY_NOTIFIED batch keeps its
 * unsettled rows under the identities they were first attempted with, so a resend re-requests those
 * rows and no others - the teams that were told are not told twice - and the batch reaches NOTIFIED
 * when the last of them is accepted.
 *
 * <p><strong>Unsettled means FAILED or PENDING, and both halves of this class are re-entrant.</strong>
 * A run that stopped between the 202 and the settlement - the pod died, the store blipped on the
 * update, the listener's session rolled the delivery back after the mark had already committed -
 * leaves a row that cannot say whether the e-mail was asked for. An ambiguous downstream outcome is
 * retried (constitution's Idempotency bullet), and it is safe to retry because the retry goes out
 * under the id the row already holds. So {@link #resendFailed} re-requests the PENDING rows beside
 * the FAILED ones, and {@link #notify} mints only for the addresses this batch holds no row for,
 * which is what makes a second notify possible at all: {@code UNIQUE (batch_id, email_address)}
 * refuses a second row per address, so a notify that minted for every recipient could be run
 * exactly once - and the run it was run in is the one that may have stopped half way.
 *
 * <p><strong>The tally is read off the table rather than counted in flight</strong>, and the same
 * read serves both methods: a resend's verdict is about the batch as it now stands, in which the
 * team that was told last night still counts as told, and a fresh notify's rows are exactly the
 * rows it has just settled. One rule instead of two that could disagree about the same batch.
 *
 * <p><strong>Notification is asked once per batch, because the mark that precedes it is.</strong>
 * {@code markGenerated} is a compare-and-set, so of two mechanisms racing to move one batch to
 * GENERATED only one wins and only that one goes on to notify. A batch whose notification could not
 * be made at all - the store went away mid-run - is left standing at GENERATED with the failure
 * reported by whoever drove the outcome, which is what {@code notify-register --batch} exists for:
 * a redelivered {@code document-available} is recognised rather than re-applied, so it is not a
 * second chance to send. That batch is visible while it stands there -
 * {@code courtregister_oldest_generated_age} is the reading that says a batch has held its document
 * since before anybody was worried - and either entry point recovers it, because both are
 * re-entrant.
 *
 * <p>Every line this service writes carries ids, counts and bounded codes. A recipient's address and
 * name never reach a log at INFO or above and never a metric label (constitution Principle VII);
 * they live in the notification row, which is the only place that may hold them.
 */
public class RegisterNotifierService {

    /**
     * The logical template name written to {@code register_notification.template_name}.
     *
     * <p>The name the environment configures the id under, in
     * {@code courtregister.email.templates.cr_standard}, and the value data-model.md gives the
     * column: the row says which template an e-mail was sent under and not only which UUID.
     *
     * <p>Written from here rather than copied off the recipient. One template is configured, so one
     * template is what every register goes out under, and a row naming the recipient's own
     * {@code emailTemplateName} beside the {@code cr_standard} id would be a row that described a
     * message nobody sent. The recipient mapper already defaults that field to this same name
     * wherever a subscription names none (C29).
     */
    public static final String TEMPLATE_NAME = "cr_standard";

    /** A minted row has been posted for nothing yet, which is what the column's default says. */
    private static final int NO_ATTEMPTS_YET = 0;

    private static final Logger LOG = LoggerFactory.getLogger(RegisterNotifierService.class);

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

    /**
     * The shared retry policy: the attempt budget and the back-off between two of them.
     *
     * <p>The same object the generation leg spends, from the same five settings (defect fix C3):
     * one taxonomy, one back-off, and the loop belonging to whoever holds the budget.
     */
    private final RetryPolicy retryPolicy;

    /** How a wait between two attempts at one recipient is taken. */
    private final RetryPause pause;

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
     * @param policy                the shared retry policy: attempts, back-off, and what one
     *                              attempt is worth asking again for
     * @param retryPause            how a wait between two attempts at one recipient is taken
     * @param runClock              this pod's reading of now, which is what {@code sent_at} records
     */
    public RegisterNotifierService(final RegisterStore registerStore,
            final RegisterBatchRepository registerBatches,
            final RegisterNotificationRepository registerNotifications,
            final RegisterNotifier registerNotifier,
            final GenerationMetrics generationMetrics,
            final UUID crStandardTemplateId,
            final RetryPolicy policy,
            final RetryPause retryPause,
            final Clock runClock) {
        this.store = registerStore;
        this.batches = registerBatches;
        this.notifications = registerNotifications;
        this.notifier = registerNotifier;
        this.metrics = generationMetrics;
        this.templateId = crStandardTemplateId;
        this.retryPolicy = policy;
        this.pause = retryPause;
        this.clock = runClock;
    }

    /**
     * Tells every recipient of one generated batch, and settles the batch on the tally.
     *
     * <p>The rows come first, all of them, and the document id is read before the first of them is
     * minted: a batch that turned out to carry no document would otherwise leave a table full of
     * PENDING rows for e-mails nothing was ever going to ask for.
     *
     * @param batchId the batch whose document has been generated
     * @return how many recipients were accepted, how many failed, and the terminal state the batch
     *     is settled in
     */
    public NotificationSummary notify(final UUID batchId) {
        final RegisterBatch batch = batchOf(batchId);
        final List<CourtRegisterRecipient> recipients =
                RecipientSet.unionOf(store.batched(batchId));

        if (recipients.isEmpty()) {
            LOG.info("Batch {} has a document and no recipients at all, so there is nobody to tell "
                    + "and nothing to record an attempt against; it ends here rather than waiting "
                    + "on an e-mail nobody is owed.", batchId);
        } else {
            final UUID documentFileId = documentOf(batch);
            LOG.info("Batch {} is addressed to {} recipients, each with a row minted before "
                    + "anything is asked of notificationnotify.", batchId, recipients.size());
            tell(owedRows(batchId, recipients), documentFileId);
        }
        return settle(batch);
    }

    /**
     * Re-requests the recipients whose e-mail was never accepted, under the identities they hold.
     *
     * <p>FAILED and PENDING alike, because the two are the same debt to the same team: a row minted
     * by a run that stopped before it could settle cannot say whether the e-mail was asked for, and
     * an ambiguous outcome is retried rather than left standing. The retry is safe for the reason
     * every retry here is safe - it goes out under the notification id the row already holds, so it
     * reaches notificationnotify's own aggregate instead of asking for a second e-mail.
     *
     * @param batchId the batch to resend for
     * @return the tally over the whole batch as it now stands, and the terminal state that produces
     */
    public NotificationSummary resendFailed(final UUID batchId) {
        final RegisterBatch batch = batchOf(batchId);
        final List<RegisterNotification> owed = notifications.findUnsettledByBatchId(batchId);

        if (owed.isEmpty()) {
            LOG.info("Batch {} has no unsettled recipient to re-request, so nothing is sent and "
                    + "the batch is settled on the rows it already holds.", batchId);
        } else {
            LOG.info("Batch {} is owed {} e-mails, each re-requested under the identity its row "
                    + "was minted with so that it reaches the attempt it is retrying.",
                    batchId, owed.size());
            tell(owed, documentOf(batch));
        }
        return settle(batch);
    }

    /**
     * The rows this run has to post for: one per recipient, minted where the batch holds none yet.
     *
     * <p>The identity is minted here and persisted here, and it is the whole reason this happens
     * before any POST: it is the path parameter of {@code POST /notifications/{notificationId}} and
     * the key of notificationnotify's own aggregate, so a row written after the call would be
     * evidence of an e-mail this service could no longer name.
     *
     * <p><strong>And it is minted only where there is no row already</strong>, which is what makes
     * a second {@code notify} over the same batch possible at all. {@code UNIQUE (batch_id,
     * email_address)} refuses a second row for an address, so a notify that minted for every
     * recipient could be run exactly once - and the run it was run in is the one that may have
     * stopped half way, leaving rows PENDING that nothing would ever ask about again. A recipient
     * that already has a row keeps it: what was sent, and to whom, was decided when that row was
     * written.
     *
     * <p>A row already ACCEPTED is not returned. The team it belongs to has been told, and telling
     * it again would be a second register about the same children.
     *
     * @param batchId    the batch these recipients are being told about
     * @param recipients the batch's recipients, de-duplicated by address
     * @return the rows still owed an e-mail, in the order the addresses were first seen
     */
    private List<RegisterNotification> owedRows(final UUID batchId,
            final List<CourtRegisterRecipient> recipients) {

        final Map<String, RegisterNotification> held = new LinkedHashMap<>();
        for (final RegisterNotification row : notifications.findByBatchId(batchId)) {
            held.put(row.emailAddress(), row);
        }
        final List<RegisterNotification> owed = new ArrayList<>(recipients.size());

        for (final CourtRegisterRecipient recipient : recipients) {
            final RegisterNotification row = held.computeIfAbsent(recipient.emailAddress1(),
                    address -> mint(batchId, address, recipient.recipientName()));
            if (row.status() != NotificationStatus.ACCEPTED) {
                owed.add(row);
            }
        }
        return owed;
    }

    /**
     * Mints one recipient's PENDING row and writes it down, before anything is asked for it.
     *
     * @param batchId       the batch this recipient is being told about
     * @param emailAddress  the address the e-mail goes to
     * @param recipientName the name the template greets, where the subscription named one
     * @return the row as it was written, carrying the identity its POST will be made under
     */
    private RegisterNotification mint(final UUID batchId, final String emailAddress,
            final String recipientName) {

        final RegisterNotification row = new RegisterNotification(UUID.randomUUID(), batchId,
                emailAddress, recipientName, TEMPLATE_NAME, templateId, NotificationStatus.PENDING,
                null, null, NO_ATTEMPTS_YET);
        notifications.insert(row);
        return row;
    }

    /**
     * Asks notificationnotify for each row's e-mail and settles that row on the answer.
     *
     * <p>One row at a time, and the next row is asked whichever way this one went: an exception that
     * ended the batch would turn one bad address into a night's silence for a whole court centre.
     *
     * <p>The counter moves once per row and not once per POST. What
     * {@code courtregister_notifications_total} answers is how many e-mails a night asked for and
     * got, and a transient refusal that cleared on the next attempt is not an e-mail that failed -
     * how many attempts it took is on the row's {@code attempts}.
     *
     * @param rows           the rows to post for, each already persisted under its own identity
     * @param documentFileId the rendered document's file-service id, attached by reference
     */
    private void tell(final List<RegisterNotification> rows, final UUID documentFileId) {
        for (final RegisterNotification row : rows) {
            final Attempted attempted = attempt(row, documentFileId);
            final NotificationOutcome outcome = attempted.outcome();
            metrics.notificationSettled(outcome.status(), outcome.responseCode());
            notifications.update(settledAs(row, outcome, attempted.posts()));
        }
    }

    /**
     * One recipient's POST, asked again for as long as the answer may change and the budget holds.
     *
     * <p>The refusal is caught here rather than left to end the batch, and it is not absorbed: the
     * status that made the last attempt one becomes the row's {@code response_code} and its own
     * series on {@code courtregister_notifications_total}, and the transport detail was already
     * reported by the client that saw it. The line here carries the notification id, the batch id,
     * the status and the bounded classification, and none of those is about a person.
     *
     * <p><strong>The loop is here rather than in the client</strong>, which is exactly where the
     * generation leg puts it and for the same reason: the client classifies one attempt through the
     * shared {@link RetryPolicy} and is told nothing about what is left of the budget, so a loop
     * inside it would spend one it cannot see. The branch is on the classification and never on the
     * type of what was thrown - NON_TRANSIENT means the same command under the same identity will
     * be declined again, and waiting to prove it costs the teams after this one.
     *
     * <p><strong>And the retry is the same POST.</strong> The row arrives persisted, so the second
     * attempt goes out under the notification id the first did: notificationnotify keys its
     * aggregate on that id, so the retry reaches the attempt it is retrying instead of asking for a
     * second register about the same children (research §10).
     *
     * <p>No deadline is checked, and that is the one place this differs from the generation leg.
     * That leg runs inside a claim the nightly run holds and measures each attempt against what is
     * left of it; a notification is driven by the outcome sink on a public-event delivery or by an
     * operator's resend, and holds no claim at all. What bounds it is therefore the attempt budget
     * and {@code max-backoff}, which is what bounds every wait this policy hands out.
     *
     * @param row            the persisted row the POST is made under
     * @param documentFileId the rendered document's file-service id
     * @return ACCEPTED with the status notificationnotify answered, or FAILED with what the last
     *     attempt answered instead - which is nothing at all where it reached no verdict - and how
     *     many POSTs this call made either way
     */
    // PMD.OnlyOneReturn: the accepted attempt answers where it happened. A single exit would mean
    // carrying an outcome past the branch that decides whether to ask again, and the whole subject
    // of the loop is that an accepted POST stops it.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private Attempted attempt(final RegisterNotification row, final UUID documentFileId) {
        final int maxAttempts = retryPolicy.maxAttempts();
        Integer responseCode = null;
        int posts = 0;

        while (posts < maxAttempts) {
            posts++;
            try {
                return new Attempted(
                        notifier.send(row, documentFileId, CallerIdentity.SYSTEM), posts);
            } catch (NotificationFailedException refused) {
                final OptionalInt answered = refused.responseCode();
                responseCode = answered.isPresent() ? answered.getAsInt() : null;
                LOG.warn("notificationnotify did not accept a register e-mail on attempt {} of {}. "
                        + "notificationId={} batchId={} responseCode={} classification={}",
                        posts, maxAttempts, row.notificationId(), row.batchId(), responseCode,
                        refused.classification(), refused);
                if (refused.classification() != FailureClassification.TRANSIENT
                        || posts == maxAttempts
                        || !waitFor(row, retryPolicy.waitAfter(posts, Optional.empty()))) {
                    break;
                }
            }
        }
        LOG.warn("The register e-mail for one recipient of batch {} was not accepted, so its row "
                + "records the attempts and the batch carries on to the recipients after it. "
                + "notificationId={} attempts={} responseCode={}", row.batchId(),
                row.notificationId(), posts, responseCode);
        return new Attempted(new NotificationOutcome(NotificationStatus.FAILED, responseCode),
                posts);
    }

    /**
     * Takes the wait between two attempts at one recipient.
     *
     * <p>An interrupt is restored and the recipient given up on rather than swallowed: the thread
     * has been asked to stop, and a notification that carried on asking would outlive the shutdown
     * that ended it. The row stays unsettled-or-FAILED under its own identity, so the resend that
     * follows reaches the same attempt.
     *
     * @param row  the row being asked about
     * @param wait how long the shared policy said to wait
     * @return whether the wait completed and another attempt may be made
     */
    private boolean waitFor(final RegisterNotification row, final Duration wait) {
        boolean waited = true;
        try {
            pause.pause(wait);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while waiting to ask for a register e-mail again, so no further "
                    + "attempt is made. notificationId={} batchId={}", row.notificationId(),
                    row.batchId());
            waited = false;
        }
        return waited;
    }

    /**
     * The row as this attempt leaves it.
     *
     * <p>The identity, the batch, the address, the name and the template are carried through
     * unchanged: what was sent, and to whom, was decided when the row was minted and an attempt may
     * only say how it ended. {@code sent_at} is this pod's reading of now for a failure as much as
     * for an acceptance, because what it records is when the attempt was settled.
     *
     * @param row     the row as it stood before this attempt
     * @param outcome what notificationnotify answered, or did not
     * @param posts   how many POSTs this call made for the row, which is what {@code attempts}
     *                accumulates: the column counts the times an e-mail was asked for and not the
     *                times a run asked
     * @return the row as it should now stand
     */
    private RegisterNotification settledAs(final RegisterNotification row,
            final NotificationOutcome outcome, final int posts) {

        return new RegisterNotification(row.notificationId(), row.batchId(), row.emailAddress(),
                row.recipientName(), row.templateName(), row.templateId(), outcome.status(),
                outcome.responseCode(), clock.instant(), row.attempts() + posts);
    }

    /**
     * Settles the batch on the tally of its rows, through the store's own compare-and-set.
     *
     * <p>The three branches are the sink's, for the sink's reasons. A batch already standing where
     * the tally would put it has nothing left to record - a resend refused a second time is the same
     * state, not a worse one - and a move the state machine does not draw leaves the batch where it
     * is and is reported here rather than attempted. Only the middle branch writes, so the terminal
     * state is counted exactly where it is recorded.
     *
     * @param batch the batch as it stood when this run read it
     * @return the tally the batch was settled on
     */
    private NotificationSummary settle(final RegisterBatch batch) {
        final NotificationSummary summary = tally(notifications.findByBatchId(batch.batchId()));

        if (batch.status() == summary.outcome()) {
            LOG.debug("Batch {} already stands at {}, so the tally that has just been taken for it "
                    + "again is recognised rather than re-written.", batch.batchId(),
                    summary.outcome());
        } else if (batch.status().canTransitionTo(summary.outcome())) {
            store.markNotified(batch.batchId(), summary);
            metrics.batchCompleted(summary.outcome());
            LOG.info("Batch {} is settled {}: {} recipients accepted and {} failed.",
                    batch.batchId(), summary.outcome(), summary.accepted(), summary.failed());
        } else {
            LOG.warn("Batch {} stands at {} and its recipients tally to {}, which the state machine "
                    + "does not draw; the batch is left where it is and the tally is reported here "
                    + "rather than written.", batch.batchId(), batch.status(), summary.outcome());
        }
        return summary;
    }

    /**
     * What the batch's rows add up to, and the terminal state that produces.
     *
     * <p>The verdict is not derivable from the two counts alone, which is why
     * {@link NotificationSummary} carries all three: no rows at all is the batch that had nobody to
     * tell (defect fix P1) and is a state no count of failures can produce, while a row that is
     * neither accepted nor failed is an attempt this run could not finish - it is not a team that
     * was told, so the batch it belongs to has not told everybody.
     *
     * @param rows every {@code register_notification} row of the batch, as they now stand
     * @return the accepted and failed counts, and the terminal state they settle the batch in
     */
    private static NotificationSummary tally(final List<RegisterNotification> rows) {
        final int accepted = (int) rows.stream()
                .filter(row -> row.status() == NotificationStatus.ACCEPTED)
                .count();
        final int failed = (int) rows.stream()
                .filter(row -> row.status() == NotificationStatus.FAILED)
                .count();
        return new NotificationSummary(accepted, failed, outcomeOf(rows.size(), accepted));
    }

    /**
     * The terminal state a batch's rows put it in.
     *
     * @param recipients how many rows the batch holds, which is how many teams it is addressed to
     * @param accepted   how many of them notificationnotify accepted
     * @return NOTIFIED_NOBODY, NOTIFIED or PARTIALLY_NOTIFIED
     */
    private static BatchStatus outcomeOf(final int recipients, final int accepted) {
        final BatchStatus outcome;
        if (recipients == 0) {
            outcome = BatchStatus.NOTIFIED_NOBODY;
        } else if (accepted == recipients) {
            outcome = BatchStatus.NOTIFIED;
        } else {
            outcome = BatchStatus.PARTIALLY_NOTIFIED;
        }
        return outcome;
    }

    /**
     * What one recipient's attempts came to, and how many of them there were.
     *
     * @param outcome ACCEPTED with the status notificationnotify answered, or FAILED with what the
     *                last attempt answered instead
     * @param posts   how many POSTs this call made for the row, which is what its {@code attempts}
     *                accumulates
     */
    private record Attempted(NotificationOutcome outcome, int posts) {
    }

    /**
     * The batch a notification is about.
     *
     * @param batchId the batch identity the caller named
     * @return the batch row
     * @throws IllegalStateException where this store holds no such batch, which is a caller naming
     *     an identity nothing was ever assembled under rather than a batch with nothing to send
     */
    private RegisterBatch batchOf(final UUID batchId) {
        return batches.findById(batchId).orElseThrow(() -> new IllegalStateException(
                "no register batch " + batchId + " to tell the recipients of"));
    }

    /**
     * The document every e-mail of the batch attaches, by file-service id.
     *
     * @param batch the batch the e-mails are about
     * @return the rendered document's file-service id
     * @throws IllegalStateException where the batch carries none, which is a batch that has not
     *     been generated and so has nothing any recipient could be sent
     */
    private static UUID documentOf(final RegisterBatch batch) {
        if (batch.documentFileId() == null) {
            throw new IllegalStateException("register batch " + batch.batchId() + " stands at "
                    + batch.status() + " and carries no document, so there is nothing to attach");
        }
        return batch.documentFileId();
    }
}
