package uk.gov.hmcts.cp.courtregister.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.FlagDecision;
import uk.gov.hmcts.cp.courtregister.domain.NotificationStatus;

/**
 * The instrument surface of the downstream half, declared in one place.
 *
 * <p>The counterpart of {@link ProcessingMetrics}, under the same two rules. Names and label sets
 * are fixed so the tests assert on them and the alert rules written later have a stable surface to
 * fire on; labels are low-cardinality enumerations only, so a batch id, a court centre id or a
 * recipient's address is never a series - that is both a cardinality explosion and, on a register
 * whose every defendant is a youth, a privacy breach.
 *
 * <p>Two of these answer questions nothing else in the flow can. A skipped run is counted by the
 * reason it was skipped, because "the flag is off" and "the flag could not be read" look identical
 * from outside and are not the same night; and a reconciled completion is counted separately from an
 * ordinary one, because a run whose outcomes all arrive by reconciliation is a broker to look at
 * rather than a renderer.
 *
 * <p>The four gauges are the state a nightly flow cannot be understood without between runs: how
 * old the oldest unbatched record is, how long the oldest batch has been waiting for a document,
 * how many batches the run deadline left behind, and whether the flag was readable at all. Like
 * {@link ProcessingMetrics}'s two, they are registered from construction, because a dashboard must
 * be able to read them from a pod that has not yet run.
 *
 * <p><strong>Seam.</strong> Every recording method below is completed by T017, whose green run is
 * {@code GenerationMetricsTest} (T010) - which is also where the instrument names and tags are
 * pinned. Until then each of them records nothing, so every counter and timer this class documents
 * is absent from the registry and the four gauges never leave the reading they were registered
 * with - which is what T010 fails on. A recorder that refused instead would fail T010 as an error
 * rather than as the absent meter its task text names, and refusing is in any case the wrong
 * production shape: telemetry that throws would end the run it was only supposed to describe.
 */
public class GenerationMetrics {

    public static final String BATCHES = "courtregister_batches_total";
    public static final String GENERATION_REQUEST = "courtregister_generation_request_total";
    public static final String GENERATION_LATENCY = "courtregister_generation_latency";
    public static final String GENERATION_RECONCILED = "courtregister_generation_reconciled_total";
    public static final String GENERATION_SKIPPED = "courtregister_generation_skipped_total";
    public static final String NOTIFICATIONS = "courtregister_notifications_total";
    public static final String OLDEST_RECORDED_UNBATCHED_AGE =
            "courtregister_oldest_recorded_unbatched_age";
    public static final String OLDEST_GENERATING_AGE = "courtregister_oldest_generating_age";
    public static final String PENDING_AFTER_DEADLINE = "courtregister_pending_after_deadline";
    public static final String FLAG_READ_OK = "courtregister_flag_read_ok";

    public static final String OUTCOME_TAG = "outcome";
    public static final String RESPONSE_CODE_TAG = "response_code";
    public static final String REASON_TAG = "reason";
    public static final String STATUS_TAG = "status";

    /**
     * The {@code response_code} label of an attempt nothing answered at all.
     *
     * <p>A bounded code rather than an absent label, so a connection that never got a status line
     * and a status line this service did not expect are two readings of the same series instead of
     * two series shapes.
     */
    public static final String NO_RESPONSE = "none";

    private static final int READABLE = 1;

    /**
     * Gauge state, held here rather than read from a collaborator so the four gauges exist from
     * construction: a nightly flow is read between runs as much as during one, and a gauge that
     * only appears after the first run is not an alerting surface.
     */
    private final AtomicLong oldestRecordedUnbatchedSeconds = new AtomicLong();
    private final AtomicLong oldestGeneratingSeconds = new AtomicLong();
    private final AtomicInteger pendingAfterDeadlineBatches = new AtomicInteger();

    /**
     * Whether the flag was readable, up until a read says otherwise - the honest starting position
     * for a pod that has not asked yet, and the same one {@code courtregister_servicebus_up} takes.
     */
    private final AtomicInteger flagReadable = new AtomicInteger(READABLE);

    /**
     * Registers the downstream half's gauges against the given registry.
     *
     * @param registry the registry every instrument is registered against
     */
    public GenerationMetrics(final MeterRegistry registry) {
        Gauge.builder(OLDEST_RECORDED_UNBATCHED_AGE, oldestRecordedUnbatchedSeconds,
                        AtomicLong::doubleValue)
                .description("Age in seconds of the oldest record still waiting to be batched")
                .register(registry);
        Gauge.builder(OLDEST_GENERATING_AGE, oldestGeneratingSeconds, AtomicLong::doubleValue)
                .description("Age in seconds of the oldest batch still waiting for a document")
                .register(registry);
        Gauge.builder(PENDING_AFTER_DEADLINE, pendingAfterDeadlineBatches,
                        AtomicInteger::doubleValue)
                .description("Batches a run ended without asking the renderer for")
                .register(registry);
        Gauge.builder(FLAG_READ_OK, flagReadable, AtomicInteger::doubleValue)
                .description("1 while the CourtRegisterService flag is readable, 0 while it is not")
                .register(registry);
    }

    /**
     * Counts a batch that reached a terminal state.
     *
     * @param outcome the state it ended in
     */
    public void batchCompleted(final BatchStatus outcome) {
        // Seam: T017 counts this on BATCHES, labelled with the outcome's bounded code.
    }

    /**
     * Counts a render request by what systemdocgenerator answered.
     *
     * @param responseCode the status line, which is a bounded dimension and the only one
     */
    public void generationRequested(final int responseCode) {
        // Seam: T017 counts this on GENERATION_REQUEST, labelled with the status line.
    }

    /**
     * Records how long a batch took from render request to outcome.
     *
     * @param latency request to outcome, however the outcome arrived
     */
    public void generationLatency(final Duration latency) {
        // Seam: T017 records this on the GENERATION_LATENCY timer.
    }

    /**
     * Counts an outcome the grace-period reconciler had to fetch rather than receive.
     */
    public void reconciled() {
        // Seam: T017 counts this on GENERATION_RECONCILED, which carries no label.
    }

    /**
     * Counts a run that read the flag and did not generate.
     *
     * @param decision what the flag said, whose bounded code is the reason label
     */
    public void runSkipped(final FlagDecision decision) {
        // Seam: T017 counts this on GENERATION_SKIPPED, labelled with the decision's code.
    }

    /**
     * Counts one recipient's e-mail by how it was settled.
     *
     * @param status       ACCEPTED or FAILED
     * @param responseCode the status notificationnotify answered with, or {@code null} where nothing
     *                     answered at all
     */
    public void notificationSettled(final NotificationStatus status, final Integer responseCode) {
        // Seam: T017 counts this on NOTIFICATIONS, labelled with the status and the status line.
    }

    /**
     * Reports how old the oldest record still waiting to be batched is.
     *
     * <p>The reading that says a night was missed. A record that is never batched is invisible in
     * every counter here, because nothing happened to it.
     *
     * @param age the age of the oldest active, unbatched record, or {@link Duration#ZERO} where
     *            there is none
     */
    public void oldestRecordedUnbatchedAge(final Duration age) {
        // Seam: T017 moves the OLDEST_RECORDED_UNBATCHED_AGE gauge, which reads in seconds.
    }

    /**
     * Reports how long the oldest batch has been waiting for a document.
     *
     * @param age the age of the oldest batch still GENERATING, or {@link Duration#ZERO} where there
     *            is none
     */
    public void oldestGeneratingAge(final Duration age) {
        // Seam: T017 moves the OLDEST_GENERATING_AGE gauge, which reads in seconds.
    }

    /**
     * Reports how many batches the run deadline left unrequested.
     *
     * @param batches the number of batches a run ended without asking the renderer for
     */
    public void pendingAfterDeadline(final int batches) {
        // Seam: T017 moves the PENDING_AFTER_DEADLINE gauge.
    }

    /**
     * Reports whether the last flag read produced an answer.
     *
     * <p>Separate from the skipped counter, which says how a run ended: this says whether App
     * Configuration is answering at all, and an off flag is an answer.
     *
     * @param decision what the last read produced
     */
    public void flagRead(final FlagDecision decision) {
        // Seam: T017 moves the FLAG_READ_OK gauge, which is 0 only for an unreadable decision.
    }
}
