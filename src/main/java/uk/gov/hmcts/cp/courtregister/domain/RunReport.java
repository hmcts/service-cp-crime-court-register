package uk.gov.hmcts.cp.courtregister.domain;

import java.time.Duration;
import java.util.Map;

/**
 * What one nightly run did, told in one line.
 *
 * <p>A value rather than a table. The per-batch facts are already durable in {@code register_batch};
 * what a run adds is the shape of the night - what the flag said, how many batches ended each way,
 * how many outcomes had to be reconciled, and how long the requesting half took against its deadline
 * - and all four are questions asked of a dashboard rather than of a database.
 *
 * <p><strong>A skipped run still produces one.</strong> A run that read OFF and did nothing is the
 * expected state for every night before cutover, and a report that only appeared when work happened
 * would make "the flag is off" and "the job did not fire" the same silence.
 *
 * @param flagDecision what the flag said, which is the first thing a run does and the reason a
 *                     skipped run is a success
 * @param outcomes     how many batches ended in each state; empty for a skipped run
 * @param reconciled   how many outcomes the grace-period reconciler had to fetch rather than
 *                     receive, which is the broker's health seen from here
 * @param duration     how long the run took
 */
public record RunReport(
        FlagDecision flagDecision,
        Map<BatchStatus, Integer> outcomes,
        int reconciled,
        Duration duration) {

    /**
     * Freezes the outcome counts, and settles absent and empty as one statement.
     *
     * <p>A copy because the run accumulates these as it goes: a report holding that same map would
     * describe whatever the run did next rather than what it had done when the report was made.
     */
    public RunReport {
        outcomes = outcomes == null ? Map.of() : Map.copyOf(outcomes);
    }
}
