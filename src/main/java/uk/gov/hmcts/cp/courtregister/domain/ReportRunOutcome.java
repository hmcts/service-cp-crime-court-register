package uk.gov.hmcts.cp.courtregister.domain;

/**
 * How one run of the exception report ended, as the bounded {@code outcome} label.
 *
 * <p>An enum rather than a string for the reason review gate 3 raised against the first shape of
 * {@code ProcessingMetrics.exceptionReportRun}: a label a caller spells is a label a caller can
 * mistype, and a mistyped label is a new series that no alert fires on and nobody notices. Three
 * words, the compiler holds the caller to them, and the rendering is the same
 * {@code name().toLowerCase(...)} every other bounded label in this service is published under.
 *
 * <p>The three are the same three the run line carries, deliberately: a dashboard filtered on the
 * counter and a query over the run lines must partition a morning the same way.
 */
public enum ReportRunOutcome {

    /** Every sink the run was configured with took the report. */
    DELIVERED,

    /** At least one sink took it and at least one did not; the rest is resendable. */
    PARTIAL,

    /** No sink took it, so the morning's exceptions were not told to anybody. */
    FAILED
}
