package uk.gov.hmcts.cp.courtregister.domain;

import java.time.Duration;
import java.util.Map;

/**
 * What one nightly run did, told in one line.
 *
 * <p>A value rather than a table. The per-batch facts are already durable in {@code register_batch};
 * what a run adds is the shape of the night - what the flag said, how many batches it asked the
 * renderer for, how many ended each way, how many registers were inside them, how many
 * court-centre days it had to pass over and how many registers are waiting under those, how much of
 * the night had come back by the time it reported, how many outcomes had to be reconciled, and how
 * long the requesting half took against its deadline - and every one of them is a question asked of
 * a dashboard rather than of a database.
 *
 * <p><strong>Batches and registers are two different accounts of the same night.</strong> A batch
 * is one document and one e-mail; a register is one hearing's youth defendants. One batch left for
 * the next run is one court centre, and whether that matters tonight is decided by how many
 * registers are inside it - which no count of batches can answer. So the run keeps both, and both
 * add up to the night: {@link #outcomes} totals the batches the run accounted for and
 * {@link #rows()} totals the registers, each of them counted exactly once, in the batch it was
 * stamped into or under the day the assembler passed over.
 *
 * <p><strong>What the run asked for is not what came of it, and this reports both.</strong>
 * {@link #requested} counts the batches whose payload was written and whose render was asked for,
 * which is where the nightly job's leg ends: the outcome of a render arrives afterwards, on the
 * public-event topic or from the grace-period reconciler. It is counted at the call rather than off
 * the verdict, so a batch whose render left and whose mark the store then refused is still one
 * render here - and is in none of the {@link #outcomes}, which is a divergence to be read rather
 * than a total that does not add up. {@link #settled} is the other side of
 * that, and it is read rather than reasoned about - see {@link Settled}, which says exactly what
 * its four counts are and, as importantly, what they are not. What <em>this</em> run settled about
 * earlier nights' batches is {@link #reconciled}.
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
 *                     written and the request away - whatever the renderer then answered and
 *                     whatever the run afterwards managed to write down about it; zero for a
 *                     skipped run
 * @param rowOutcomes  how many registers this run stamped into a batch, under the state that
 *                     batch's requesting leg ended in; empty for a skipped run
 * @param deferredKeys how many court-centre days the assembler passed over because a batch of
 *                     theirs is still in flight ({@link BatchAssembly#deferred()}); zero for a
 *                     skipped run, and the count of the court centres this run knowingly left for
 *                     the next one
 * @param deferredRows how many registers are waiting under those days, which is what the deferral
 *                     costs measured in hearings rather than in court centres
 * @param settled      what the store said tonight's batches had come to at the moment this report
 *                     was made, which is a snapshot of a night that may still be settling and not
 *                     a final tally ({@link Settled})
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
        Settled settled,
        int reconciled,
        Duration duration) {

    /**
     * Freezes both sets of counts, and settles absent and empty as one statement.
     *
     * <p>A copy because the run accumulates these as it goes: a report holding those same maps
     * would describe whatever the run did next rather than what it had done when the report was
     * made.
     *
     * <p>An absent snapshot is {@link Settled#UNREAD} rather than four zeroes, because a caller
     * that said nothing about it has not measured a night that settled nothing.
     */
    public RunReport {
        outcomes = outcomes == null ? Map.of() : Map.copyOf(outcomes);
        rowOutcomes = rowOutcomes == null ? Map.of() : Map.copyOf(rowOutcomes);
        settled = settled == null ? Settled.UNREAD : settled;
    }

    /**
     * How far tonight's batches had got by the time the run wrote its line.
     *
     * <p><strong>A snapshot, and it says so in the name.</strong> The requesting leg ends when
     * systemdocgenerator has been asked (FR-008) and the outcome of a render is applied afterwards
     * by the event listener or by the grace-period reconciler (FR-009), so these four counts are
     * read back out of {@code register_batch} at the moment the line is written rather than known
     * by the leg that did the requesting. What they describe is therefore a night that may still be
     * settling: a render accepted at 18:04 and marked at 18:04:30 is counted here and one accepted
     * at 18:59 is not, and the same run reported a minute later would count more. It is not a final
     * tally of the night and must not be read as one - what a night came to in the end is the rows
     * themselves, and {@code courtregister_batches_total} by outcome is the estate's cumulative
     * count across every night rather than an answer about this one.
     *
     * <p><strong>It is worth taking even so</strong>, which is what the reading it replaced got
     * wrong. That reading was that these counts are zero by construction, the requesting leg ending
     * where it does; but the completion legs run while the run does, so a court centre whose render
     * comes back in seconds is marked and notified while the run is still asking about the court
     * centres behind it. Nothing else can be asked how much of <em>one</em> night had come back:
     * the cumulative counter cannot be scoped to a run and the oldest-generating and
     * oldest-generated gauges answer about ages rather than about a night.
     *
     * <p><strong>The arithmetic, and the sum that is deliberately not claimed.</strong>
     * {@link #generated} counts the batches whose document exists by now - GENERATED and every
     * state past it, because a batch that has been notified was generated first and a count that
     * went down as the night progressed would be unreadable. {@link #notified} counts the batches
     * the notifying leg has finished with, which is the three endings it can produce: everybody was
     * told, some were and the rest are resendable, and there was nobody to tell (defect fix P1).
     * Which of the three a batch reached is a distinction this service keeps everywhere it matters -
     * the row, and {@code courtregister_batches_total} by outcome - and one a count of batches
     * cannot carry, so it is not collapsed here so much as not asked. So
     * {@code notified <= generated}, and both sit <em>inside</em> the requesting leg's own account
     * rather than partitioning it: every batch counted here was counted GENERATING by
     * {@link RunReport#outcomes} on a run that finished its requesting, and neither count is
     * subtracted from that one. The two sums the report does claim are unchanged and still hold -
     * the batch outcomes total the night's batches and {@link RunReport#rows()} totals its
     * registers - and there is no third sum over these four: adding them to either total would
     * count the same batches and the same registers twice.
     *
     * <p><strong>A run that stopped part way is the one case even {@code notified <= generated
     * <= generating} can come apart on</strong>, and it is a reading rather than a defect: a batch
     * whose render was accepted and whose verdict was lost with the run is in none of the
     * requesting leg's three counts and can still be settled by the time the line is written. A
     * snapshot larger than the account beside it is then exactly the divergence worth seeing.
     *
     * <p><strong>And the read is not allowed to cost the night.</strong> The batches are stamped and
     * the renders are away by the time it is taken, so a store that will not answer is not a reason
     * to fail a run - it is a reason to say the counts are missing. {@link #UNREAD} is that
     * statement, and the run writes it on the line as a word rather than as four zeroes, because a
     * night that settled nothing and a night nobody could read are otherwise the same line.
     *
     * @param read          whether the four counts beside this are what the store said; false where
     *                      the read was refused and they are zeroes the run did not earn
     * @param generated     how many of the batches this run assembled have a document by now,
     *                      whether or not anybody has been told about them yet
     * @param notified      how many of them the notifying leg has finished with, under any of its
     *                      three endings
     * @param generatedRows how many registers are inside the batches counted by {@link #generated},
     *                      as this run stamped them
     * @param notifiedRows  how many are inside the batches counted by {@link #notified}
     */
    public record Settled(boolean read, int generated, int notified, int generatedRows,
            int notifiedRows) {

        /**
         * The read was refused, so nothing is claimed and the line says which.
         *
         * <p>Zeroes rather than a negative or an absent value, because the line an operator reads
         * is a set of counts and the word beside them is what says these four are not measurements.
         */
        public static final Settled UNREAD = new Settled(false, 0, 0, 0, 0);

        /**
         * The run assembled no batch, so there is nothing to ask about and the empty answer is
         * exact.
         *
         * <p>Distinct from {@link #UNREAD} and deliberately not the same statement: a skipped night
         * and a night with nothing waiting have settled nothing because there was nothing to settle,
         * which is a fact about the night, and no statement is issued against the store to learn it.
         */
        public static final Settled NOTHING_ASSEMBLED = new Settled(true, 0, 0, 0, 0);

        /**
         * What the store said, counted.
         *
         * @param generated     batches with a document by now
         * @param notified      batches the notifying leg has finished with
         * @param generatedRows registers inside the first
         * @param notifiedRows  registers inside the second
         * @return that snapshot, marked as one the store answered
         */
        public static Settled taken(final int generated, final int notified, final int generatedRows,
                final int notifiedRows) {
            return new Settled(true, generated, notified, generatedRows, notifiedRows);
        }
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
