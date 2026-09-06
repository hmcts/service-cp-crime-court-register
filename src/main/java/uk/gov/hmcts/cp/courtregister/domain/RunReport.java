package uk.gov.hmcts.cp.courtregister.domain;

import java.time.Duration;
import java.util.Map;

/**
 * What one nightly run did, told in one line.
 *
 * <p>A value rather than a table. The per-batch facts are already durable in {@code register_batch};
 * what a run adds is the shape of the night - what the flag said, how many batches ended each way,
 * how many court-centre days it had to pass over, how many outcomes had to be reconciled, and how
 * long the requesting half took against its deadline - and all five are questions asked of a
 * dashboard rather than of a database.
 *
 * <p><strong>A skipped run still produces one.</strong> A run that read OFF and did nothing is the
 * expected state for every night before cutover, and a report that only appeared when work happened
 * would make "the flag is off" and "the job did not fire" the same silence.
 *
 * <p><strong>The keys it passed over are counted, not named.</strong>
 * {@link BatchAssembly#deferred()} is what a run knowingly left for the next one - a court centre
 * and day whose earlier batch is still in flight - and a night of them is a night that produced no
 * document for those courts. Without a count here that fact appears in no total the report carries:
 * a deferred key is in none of the {@link #outcomes}, because no batch was assembled for it. It is
 * a number rather than the keys themselves because a court centre and a date are what the registers
 * behind them are addressed by, and a report is read from a log index (constitution Principle VII);
 * which keys they were is answered from {@code register_batch} and
 * {@code courtregister_oldest_recorded_unbatched_age} says how long the oldest has waited.
 *
 * <p><strong>What the gate decided, not what the store answered.</strong> The report says what the
 * run knows, and what a run knows about the flag is the {@link GateDecision} it was given: a
 * {@link FlagDecision} here could say neither that an operator overrode a flag that had not said ON
 * - which is the one night in this flow most worth reading a report for - nor which of the six
 * unreadable causes stopped a run, since the gate does not pass the cause on. Those six keep their
 * own series on {@code courtregister_generation_skipped_total}, which is where
 * {@link uk.gov.hmcts.cp.courtregister.batch.FeatureFlagGate} already puts them.
 *
 * @param gateDecision what the gate decided from its one read of the flag, which is the first thing
 *                     a run does and the reason a skipped run is a success
 * @param outcomes     how many batches ended in each state; empty for a skipped run
 * @param deferredKeys how many court-centre days the assembler passed over because a batch of
 *                     theirs is still in flight ({@link BatchAssembly#deferred()}); zero for a
 *                     skipped run, and the count of the registers this run knowingly left for the
 *                     next one
 * @param reconciled   how many outcomes the grace-period reconciler had to fetch rather than
 *                     receive, which is the broker's health seen from here
 * @param duration     how long the run took
 */
public record RunReport(
        GateDecision gateDecision,
        Map<BatchStatus, Integer> outcomes,
        int deferredKeys,
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
