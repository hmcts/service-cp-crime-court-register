package uk.gov.hmcts.cp.courtregister.domain;

import java.time.Duration;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision.Proceed;

/**
 * What a night's value says, and the one thing it deliberately does not claim.
 *
 * <p>The report is the only place a night is described, and the three numbers its first act
 * contributes are the ones most easily misread: a reader of a line of totals assumes every number
 * on it belongs to one of the totals. These do not. The registers the pass gave back are re-batched
 * by the same run, so they are already inside {@link RunReport#rows()}, and a report that added
 * them would count the same hearings twice (FR-009).
 *
 * <p><strong>[A]</strong> - these are characterisations of the record as T017's seam leaves it.
 * They are written here rather than left to the job's own suite because the arithmetic claim is the
 * record's and not the log line's: the line can be re-ordered, but a total that silently grew would
 * be wrong wherever it was read.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("what one nightly run did")
class RunReportTest {

    /** A night's registers, partitioned so that the row total has something to be wrong about. */
    private static final int GENERATING_ROWS = 3;

    private static final int DEFERRED_ROWS = 5;

    /** What the pass gave back that night, which is inside the rows above and not beside them. */
    private static final int RELEASED_BATCHES = 4;

    private static final int RELEASED_REGISTERS = 7;

    private static final int CONTENDED = 6;

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * A night that assembled one batch, passed over one day and released what the pass answered.
     *
     * @param batches   how many batches the pass failed and released
     * @param registers how many registers came back with them
     * @param contended how many batches it could not give back
     * @return that night's report
     */
    private static RunReport aNightThatReleased(final int batches, final int registers,
            final int contended) {

        return new RunReport(new Proceed(false),
                Map.of(BatchStatus.GENERATING, 1), 1,
                Map.of(BatchStatus.GENERATING, GENERATING_ROWS), 1, DEFERRED_ROWS,
                RunReport.Settled.NOTHING_ASSEMBLED, batches, registers, contended,
                Duration.ofMinutes(3));
    }

    /**
     * The three numbers the run's first act contributes.
     */
    @Nested
    @DisplayName("what the run's first act gave back")
    class TheReleasedNumbers {

        @Test
        void a_report_should_carry_the_two_released_numbers_and_the_contended_one() {
            final RunReport report =
                    aNightThatReleased(RELEASED_BATCHES, RELEASED_REGISTERS, CONTENDED);

            softly.assertThat(report.releasedBatches())
                    .as("a batch is one document and one e-mail, so the count of them is how much "
                            + "of the estate a lost outcome cost")
                    .isEqualTo(RELEASED_BATCHES);
            softly.assertThat(report.releasedRegisters())
                    .as("and a register is one hearing's youth defendants, which no count of "
                            + "batches can answer for")
                    .isEqualTo(RELEASED_REGISTERS);
            softly.assertThat(report.contended())
                    .as("and what the pass left exactly as it found it, which is a night's undone "
                            + "work and not a silence")
                    .isEqualTo(CONTENDED);
        }

        @Test
        void a_night_that_released_nothing_should_carry_noughts_rather_than_nothing() {
            final RunReport report = aNightThatReleased(0, 0, 0);

            softly.assertThat(report.releasedBatches()).isZero();
            softly.assertThat(report.releasedRegisters()).isZero();
            softly.assertThat(report.contended())
                    .as("a night that released nothing and a night that did not report are "
                            + "different nights, and the report says which this was")
                    .isZero();
        }

        @Test
        void the_released_registers_should_not_be_added_to_the_rows_total() {
            final RunReport released =
                    aNightThatReleased(RELEASED_BATCHES, RELEASED_REGISTERS, CONTENDED);
            final RunReport releasedNothing = aNightThatReleased(0, 0, 0);

            softly.assertThat(released.rows())
                    .as("every register the run accounted for, batched or left waiting - and the "
                            + "released ones are inside that already, because this same run "
                            + "re-batches them (FR-009)")
                    .isEqualTo(GENERATING_ROWS + DEFERRED_ROWS);
            softly.assertThat(released.rows())
                    .as("so two nights that differ only in what the pass gave back have the same "
                            + "total: the released numbers are a diagnostic and not a third sum")
                    .isEqualTo(releasedNothing.rows());
        }
    }
}
