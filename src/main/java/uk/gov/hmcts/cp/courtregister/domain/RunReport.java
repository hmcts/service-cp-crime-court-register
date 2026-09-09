package uk.gov.hmcts.cp.courtregister.domain;

import java.time.Duration;
import java.util.Map;

/**
 * What one nightly run did, told in one line.
 *
 * <p>A value rather than a table. The per-batch facts are already durable in {@code register_batch};
 * what a run adds is the shape of the night - what the flag said, how many batches it asked the
 * renderer for, how many ended each way, how many registers were inside them, how many
 * court-centre days it had to pass over and how many registers are waiting under those, how many
 * outcomes had to be reconciled, and how long the requesting half took against its deadline - and
 * every one of them is a question asked of a dashboard rather than of a database.
 *
 * <p><strong>Batches and registers are two different accounts of the same night.</strong> A batch
 * is one document and one e-mail; a register is one hearing's youth defendants. One batch left for
 * the next run is one court centre, and whether that matters tonight is decided by how many
 * registers are inside it - which no count of batches can answer. So the run keeps both, and both
 * add up to the night: {@link #outcomes} totals the batches the run accounted for and
 * {@link #rows()} totals the registers, each of them counted exactly once, in the batch it was
 * stamped into or under the day the assembler passed over.
 *
 * <p><strong>What the run asked for is not what came of it, and this reports the first.</strong>
 * {@link #requested} counts the batches whose payload was written and whose render was asked for,
 * which is where the nightly job's leg ends: the outcome of a render arrives afterwards, on the
 * public-event topic or from the grace-period reconciler, so at the moment a run reports there is
 * no generated or notified count of tonight's batches to carry - it would be zero by construction
 * on every run, and a field that can only ever be zero says less than no field at all. What
 * <em>this</em> run settled about earlier nights' batches is {@link #reconciled}; what tonight's
 * batches came to is answered by {@code courtregister_batches_total} by outcome and by the
 * oldest-generating and oldest-generated gauges, which is where FR-017 puts it.
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
 * @param requested    how many batches this run asked systemdocgenerator to render - the payload
 *                     written and the request away - whatever the renderer then answered; zero for
 *                     a skipped run
 * @param rowOutcomes  how many registers this run stamped into a batch, under the state that
 *                     batch's requesting leg ended in; empty for a skipped run
 * @param deferredKeys how many court-centre days the assembler passed over because a batch of
 *                     theirs is still in flight ({@link BatchAssembly#deferred()}); zero for a
 *                     skipped run, and the count of the court centres this run knowingly left for
 *                     the next one
 * @param deferredRows how many registers are waiting under those days, which is what the deferral
 *                     costs measured in hearings rather than in court centres
 * @param reconciled   how many outcomes the grace-period reconciler had to fetch rather than
 *                     receive, which is the broker's health seen from here
 * @param duration     how long the run took
 */
public record RunReport(
        GateDecision gateDecision,
        Map<BatchStatus, Integer> outcomes,
        int requested,
        Map<BatchStatus, Integer> rowOutcomes,
        int deferredKeys,
        int deferredRows,
        int reconciled,
        Duration duration) {

    /**
     * Freezes both sets of counts, and settles absent and empty as one statement.
     *
     * <p>A copy because the run accumulates these as it goes: a report holding those same maps
     * would describe whatever the run did next rather than what it had done when the report was
     * made.
     */
    public RunReport {
        outcomes = outcomes == null ? Map.of() : Map.copyOf(outcomes);
        rowOutcomes = rowOutcomes == null ? Map.of() : Map.copyOf(rowOutcomes);
    }

    /**
     * Every register this run accounted for, batched or left waiting.
     *
     * <p>The total the row counts have to add up to. Derived rather than carried, because a total
     * held beside its parts is a second place for them to disagree - and the disagreement would be
     * a register the night reported and could not say what happened to.
     *
     * @return how many registers the run saw
     */
    public int rows() {
        return rowOutcomes.values().stream().mapToInt(Integer::intValue).sum() + deferredRows;
    }
}
