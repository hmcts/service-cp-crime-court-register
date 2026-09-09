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
     * And this service's own exception is refused with the rest, which is the point of the rule.
     *
     * <p>Two softer rules were tried before this one and both were defeated in a single review: a
     * list of this service's own types let three wrappers through, and deriving the answer from
     * the constructors lasted no longer, because every exception inherits {@code initCause} and a
     * type with no cause constructor can still be handed one after it is built. What survives is
     * the rule with no exceptions in it.
     */
    @Test
    void should_refuse_this_services_own_exception_too() {
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
                .as("it declares no cause constructor and can still be given a cause through "
                        + "initCause, so a rule that let it through would be a rule about the "
                        + "shape of a constructor rather than about what reaches the log")
                .containsExactly("Somewhere.java:6 catches GenerationFailedException");
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
                    .as("both catches bind the name `failed`, and each statement is reported "
                            + "under the type of its own catch: a scan that gathered names for the "
                            + "whole file would answer one catch's type for both statements, and "
                            + "under the rule this replaced - where the second type was allowed - "
                            + "it would have reported neither")
                    .containsExactly("Somewhere.java:6 catches RuntimeException",
                            "Somewhere.java:14 catches GenerationFailedException");
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
                    .as("both arms are reported, because both are refused: a multi-catch is "
                            + "not a way to attach one of them")
                    .containsExactly(
                            "Somewhere.java:6 catches GenerationFailedException | RuntimeException");
        }

        @Test
        void a_brace_in_a_comment_should_not_end_the_block_the_statement_is_in() {
            assertThat(LogStatement.attachmentsIn("""
                    class Somewhere {
                        void run() {
                            try {
                                call();
                            } catch (RuntimeException failed) {
                                // the block this comment is in ends with }
                                LOG.warn("it did not work. cause={}", failed.getClass(), failed);
                            }
                        }
                    }
                    """, FILE))
                    .as("a matcher that counted every brace would close the catch at the one in "
                            + "the comment, leaving the statement below it inside no block and "
                            + "swept up by nothing - one line, and the whole claim is gone")
                    .containsExactly("Somewhere.java:7 catches RuntimeException");
        }

        @Test
        void a_brace_in_a_string_should_not_end_it_either() {
            assertThat(LogStatement.attachmentsIn("""
                    class Somewhere {
                        void run() {
                            try {
                                call();
                            } catch (RuntimeException failed) {
                                render("a payload looks like {} and ends with }");
                                LOG.warn("it did not work. cause={}", failed.getClass(), failed);
                            }
                        }
                    }
                    """, FILE))
                    .as("the same hole through a literal rather than a comment, and this service "
                            + "writes braces in strings all day: every log pattern has one")
                    .containsExactly("Somewhere.java:7 catches RuntimeException");
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
