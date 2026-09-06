package uk.gov.hmcts.cp.courtregister.batch;

import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision;

/**
 * The first thing a generation run does, and the only thing that lets it do anything else.
 *
 * <p>One read of {@code CourtRegisterService} per run, with no cache, and fail-closed: ON proceeds,
 * OFF and unreadable skip the run and count the skip under its bounded reason (constitution Cutover
 * Rule, research §3). A flag that was on an hour ago says nothing about a cutover that was rolled
 * back ten minutes ago, which is why the answer is never held between runs.
 *
 * <p>The CLI asks the same question through the same gate, and {@code --ignore-flag} is the one way
 * past it: an operator regenerating a batch by hand may need to do so while the flag is off, and the
 * override is stated in the answer so the run report can say a run went ahead that the flag would
 * have stopped.
 *
 * <p>Both the reading and the decision are counted here rather than by the caller: the
 * {@code flag_read_ok} gauge is what says whether the App Configuration store is answering at all,
 * and it must move on the read that failed as well as on the one that succeeded.
 *
 * <p><strong>Seam.</strong> T030 replaces the refusal below with the read, the mapping and the two
 * instruments; its green run is {@code FeatureFlagGateTest} (T027).
 */
public class FeatureFlagGate {

    /** The task that replaces the refusal in this class with the read and the mapping. */
    private static final String PENDING_TASK =
            "T030 implements FeatureFlagGate; FeatureFlagGateTest (T027) guards it";

    // Both are read by T030, which replaces the refusal below with the read it counts.
    @SuppressWarnings("PMD.UnusedPrivateField")
    private final FeatureFlagReader reader;

    @SuppressWarnings("PMD.UnusedPrivateField")
    private final GenerationMetrics metrics;

    /**
     * Creates the gate over the flag reader and the instruments its answer moves.
     *
     * @param reader  the one lever's reader, which never throws
     * @param metrics the instrument surface the skip and the reading are counted on
     */
    public FeatureFlagGate(final FeatureFlagReader reader, final GenerationMetrics metrics) {
        this.reader = reader;
        this.metrics = metrics;
    }

    /**
     * Reads the flag and says whether this run may generate.
     *
     * @param ignoreFlag whether the caller has deliberately overridden the flag, which only the CLI
     *                   may do and only by being asked to
     * @return {@code Proceed}, or {@code Skipped} carrying the bounded reason it was skipped under
     */
    public GateDecision decide(final boolean ignoreFlag) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }
}
