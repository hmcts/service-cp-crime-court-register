package uk.gov.hmcts.cp.courtregister.application;

import java.util.Map;
import java.util.function.Consumer;
import uk.gov.hmcts.cp.courtregister.domain.TransformationAnomaly;

/**
 * What one run skipped, counted as it goes — the seam.
 *
 * <p>Bodies land with the accumulator itself; this exists so the suite that specifies it compiles
 * and fails on its assertions rather than on a missing class.
 */
public final class RunAnomalies implements Consumer<TransformationAnomaly> {

    @Override
    public void accept(final TransformationAnomaly anomaly) {
        throw new UnsupportedOperationException("the run-scoped anomaly accumulator");
    }

    /**
     * The counts, as they stand.
     *
     * @return the counts
     */
    public Map<TransformationAnomaly, Integer> counts() {
        throw new UnsupportedOperationException("the run-scoped anomaly accumulator");
    }

    /**
     * Whether anything was skipped.
     *
     * @return whether nothing was counted
     */
    public boolean isEmpty() {
        throw new UnsupportedOperationException("the run-scoped anomaly accumulator");
    }

    /**
     * The bounded codes and their counts, as one line safe to log.
     *
     * @return the summary
     */
    public String summary() {
        throw new UnsupportedOperationException("the run-scoped anomaly accumulator");
    }
}
