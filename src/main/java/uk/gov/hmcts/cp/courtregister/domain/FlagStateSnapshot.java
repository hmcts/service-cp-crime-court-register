package uk.gov.hmcts.cp.courtregister.domain;

import java.time.Instant;

/**
 * The last thing the intake side learned about the one lever, and when it learned it.
 *
 * <p>What a recording stamps {@code processed_output.recorded_flag_state} from (research §12). The
 * listener reads the flag with the same reader the nightly job uses when a command arrives and the
 * last read has aged past its window, and hands the answer down with the command; the recording
 * itself never waits on a read, because a register that has been built is worth more than the flag
 * state it is labelled with, and a read nobody made is exactly what {@link RecordedFlagState#UNKNOWN}
 * says.
 *
 * <p>A snapshot is a reading with an age, which is why the instant is carried beside the decision
 * rather than the state being computed when the read happened: a reading taken during a command
 * that is still being processed may have gone stale before the row is written, and the row must say
 * what was true when it was written.
 *
 * <p><strong>Seam.</strong> {@link #stateFor(Instant)} is T030's, and its green run is
 * {@code RecordedFlagStateTest} (T028), which holds it to the window and to the three answers.
 *
 * @param decision what the flag said when it was last read
 * @param readAt   when that read happened, against which the reading's age is measured
 */
public record FlagStateSnapshot(FlagDecision decision, Instant readAt) {

    /** The task that replaces the refusal below with the window and the mapping. */
    private static final String PENDING_TASK =
            "T030 implements FlagStateSnapshot.stateFor; RecordedFlagStateTest (T028) guards it";

    /**
     * What a row recorded now says about the flag.
     *
     * @param now the instant the recording is happening at, which is what the reading's age is
     *            measured against
     * @return {@code ON} or {@code OFF} where the reading is fresh enough to stand for it, and
     *         {@code UNKNOWN} where it is not or where the flag could not be read at all
     */
    public RecordedFlagState stateFor(final Instant now) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }
}
