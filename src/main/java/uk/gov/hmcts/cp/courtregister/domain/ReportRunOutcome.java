package uk.gov.hmcts.cp.courtregister.domain;

import java.util.List;

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
    FAILED;

    /**
     * How one run went, folded from what each sink it asked answered.
     *
     * <p>Here rather than in the job and again in the command. The two held a copy each until
     * review gate 6, and a fold written twice is a morning the counter and the terminal can come to
     * disagree about - which is the same class of defect as the two words for an absent sink that
     * {@link DeliveryWord} ends.
     *
     * <p>A run that asked nobody - which is a run that could not build a report at all, or one on a
     * context holding no sink - is {@link #FAILED}, because nothing was told. A sink that delivered
     * to some of its recipients has not taken the report: the rest is a resend, and that nuance is
     * expressed here as {@link #PARTIAL} rather than hidden inside a sink's own answer.
     *
     * @param delivered one outcome per sink asked, in the order they were asked
     * @return the bounded outcome
     */
    public static ReportRunOutcome of(final List<DeliveryOutcome> delivered) {
        throw new UnsupportedOperationException(
                "the run's own three-state fold lands next; this is its red run");
    }
}
