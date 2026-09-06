package uk.gov.hmcts.cp.courtregister.inbound;

import java.time.Clock;
import java.util.concurrent.Executor;
import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.domain.FlagStateSnapshot;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;

/**
 * What the intake side labels an arriving command with, without ever waiting to find out.
 *
 * <p>A recorded row says which implementation was meant to be generating when it arrived, because a
 * command still on the queue after the producer stopped publishing may belong to a hearing the
 * resumed legacy also processed, and batching it automatically would send one child's register
 * twice (research §12). The label is the flag as it was last read, and the reading is only allowed
 * to speak for a command that arrived inside its window.
 *
 * <p><strong>The read is never on the delivery's thread.</strong> A command arriving on a stale or
 * absent reading hands a refresh to the executor and is labelled from what is already known, which
 * for an absent reading is {@link RecordedFlagState#UNKNOWN}. The alternative is a register whose
 * recording waits on App Configuration: an outage there would then stall the queue behind a label,
 * and a register that has been built is worth more than the flag state it carries. The refresh is
 * for the commands behind this one.
 *
 * <p>It is a collaborator of {@link CourtRegisterMessageListener} rather than a part of it. The
 * listener's rule is one delivery in, exactly one settlement out; holding a flag reader, a clock and
 * an executor inside it would put a second concern on the class whose single concern is the whole of
 * its correctness (constitution Principle V).
 *
 * <p><strong>Seam.</strong> {@link #current()} is T030's, together with
 * {@link FlagStateSnapshot#stateFor}, and its green run is {@code RecordedFlagStateTest} (T028).
 */
public class RecordedFlagStateSource {

    /** The task that replaces the refusal below with the reading, the window and the refresh. */
    private static final String PENDING_TASK =
            "T030 implements the inbound flag-state attachment; RecordedFlagStateTest (T028) "
                    + "guards it";

    /** The same reader the nightly job uses, which is what makes the flag one lever. */
    @SuppressWarnings("PMD.UnusedPrivateField")
    private final FeatureFlagReader reader;

    /** Where a refresh is handed to, so that no delivery thread is ever inside a read. */
    @SuppressWarnings("PMD.UnusedPrivateField")
    private final Executor refreshes;

    /** What a reading's age is measured against. */
    @SuppressWarnings("PMD.UnusedPrivateField")
    private final Clock clock;

    /**
     * Creates the source; the reading it hands out is its own and is shared by every delivery.
     *
     * @param reader   the flag reader the nightly job also uses
     * @param refreshes where a read is run, which is never the caller's thread
     * @param clock    what the age of a reading is measured against
     */
    public RecordedFlagStateSource(
            final FeatureFlagReader reader, final Executor refreshes, final Clock clock) {
        this.reader = reader;
        this.refreshes = refreshes;
        this.clock = clock;
    }

    /**
     * What a command arriving now is labelled with, answered from what is already known.
     *
     * @return the state the last reading stands for now, or {@link RecordedFlagState#UNKNOWN} where
     *         there is no reading or it has aged out of its window
     */
    public RecordedFlagState current() {
        throw new UnsupportedOperationException(PENDING_TASK);
    }
}
