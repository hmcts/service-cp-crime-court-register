package uk.gov.hmcts.cp.courtregister.domain;

/**
 * How one sink's delivery of one report went.
 *
 * <p>Answered rather than thrown: a report that reached one of its two audiences is a partially
 * delivered report and not a failed one, so a sink says how it went and the service carries on to
 * the next (FR-007).
 *
 * <p>{@code accepted} and {@code refused} are per-recipient counts for {@link ReportSinkName#EMAIL}
 * - three recipients, one refused, is {@link DeliveryStatus#PARTIALLY_DELIVERED} with 2 and 1 - and
 * are one and none for a delivered {@link ReportSinkName#LOG}, which has a single audience.
 *
 * @param sink     which sink this is about
 * @param status   how completely it delivered
 * @param reason   the bounded reason, which is {@link ReportDeliveryReason#NONE} on a success
 * @param accepted how many recipients took it
 * @param refused  how many did not
 */
public record DeliveryOutcome(
        ReportSinkName sink,
        DeliveryStatus status,
        ReportDeliveryReason reason,
        int accepted,
        int refused) {

    /**
     * The outcome of a sink with one audience that took the report.
     *
     * <p>Stated once here rather than spelled out at each of its call sites: "delivered" has a
     * shape - DELIVERED, no reason, one accepted, none refused - and three copies of that shape are
     * three places for it to drift.
     *
     * @param sink the sink that delivered
     * @return its outcome
     */
    public static DeliveryOutcome delivered(final ReportSinkName sink) {
        throw new UnsupportedOperationException("the delivered outcome is not composed yet");
    }
}
