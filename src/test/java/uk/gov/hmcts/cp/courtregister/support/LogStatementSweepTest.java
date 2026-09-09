package uk.gov.hmcts.cp.courtregister.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The scan behind the privacy sweep, put in front of source written to defeat it.
 *
 * <p>A sweep over every line in the repository is a large claim, and the claim is worth exactly
 * what the scan behind it is worth. The first version of this one was defeated twice by the same
 * review, in ways nothing in the repository happened to exhibit: it gathered catch names for a
 * whole file rather than lexically, so a later, safer catch that reused a name excused an earlier
 * attachment, and it asked whether the caught type contained an allowed name, so a multi-catch
 * naming one allowed type passed with anything beside it.
 *
 * <p>Neither shape existed in {@code src/main/java}, so no run over the real sources could have
 * found either. That is what this suite is for: the scan is handed source that has them.
 */
@DisplayName("the sweep that refuses an attached exception")
class LogStatementSweepTest {

    private static final String FILE = "Somewhere.java";

    /**
     * The plain case, so the two below are read against something that works.
     */
    @Test
    void should_report_a_statement_that_attaches_somebody_elses_exception() {
        assertThat(LogStatement.attachmentsIn("""
                class Somewhere {
                    void run() {
                        try {
                            call();
                        } catch (RuntimeException failed) {
                            LOG.warn("it did not work. cause={}", failed.getClass(), failed);
                        }
                    }
                }
                """, FILE))
                .as("the shape the whole sweep exists to refuse")
                .containsExactly("Somewhere.java:6 catches RuntimeException");
    }

    /**
     * And what may still be attached, so the sweep is not simply refusing everything.
     */
    @Test
    void should_allow_an_exception_that_cannot_carry_a_cause() {
        assertThat(LogStatement.attachmentsIn("""
                class Somewhere {
                    void run() {
                        try {
                            call();
                        } catch (GenerationFailedException failed) {
                            LOG.warn("the render failed. cause={}", failed.getClass(), failed);
                        }
                    }
                }
                """, FILE))
                .as("no constructor of it takes a Throwable, so nothing of anybody else's can be "
                        + "underneath it and its message was composed in this repository")
                .isEmpty();
    }

    @Nested
    @DisplayName("source written to defeat it")
    class TheShapesThatDefeatedTheFirstVersion {

        @Test
        void a_name_reused_by_a_later_safer_catch_should_not_excuse_the_earlier_one() {
            assertThat(LogStatement.attachmentsIn("""
                    class Somewhere {
                        void first() {
                            try {
                                call();
                            } catch (RuntimeException failed) {
                                LOG.error("the first. cause={}", failed.getClass(), failed);
                            }
                        }

                        void second() {
                            try {
                                call();
                            } catch (GenerationFailedException failed) {
                                LOG.warn("the second. cause={}", failed.getClass(), failed);
                            }
                        }
                    }
                    """, FILE))
                    .as("both catches bind the name `failed`, and the safe one is written second: "
                            + "a scan that gathered names for the whole file would answer the "
                            + "second catch's type for the first statement and report nothing")
                    .containsExactly("Somewhere.java:6 catches RuntimeException");
        }

        @Test
        void a_multi_catch_naming_one_safe_type_should_not_pass_on_the_strength_of_it() {
            assertThat(LogStatement.attachmentsIn("""
                    class Somewhere {
                        void run() {
                            try {
                                call();
                            } catch (GenerationFailedException | RuntimeException failed) {
                                LOG.warn("either of them. cause={}", failed.getClass(), failed);
                            }
                        }
                    }
                    """, FILE))
                    .as("the type that renders is whichever was thrown, so a multi-catch is only "
                            + "as safe as its least safe arm")
                    .containsExactly(
                            "Somewhere.java:6 catches GenerationFailedException | RuntimeException");
        }

        @Test
        void a_statement_outside_any_catch_should_not_be_read_as_attaching_anything() {
            assertThat(LogStatement.attachmentsIn("""
                    class Somewhere {
                        void run() {
                            LOG.warn("nothing failed here. batchId={}", batchId);
                            try {
                                call();
                            } catch (RuntimeException failed) {
                                handle(failed);
                            }
                        }
                    }
                    """, FILE))
                    .as("a line before the catch binds no exception, and a catch that logs nothing "
                            + "is not this defect either")
                    .isEmpty();
        }
    }
}
