package uk.gov.hmcts.cp.courtregister.config;

/**
 * Which now-subscriptions source the service runs with.
 *
 * <p>A setting rather than a Spring profile, for the same reason {@link PayloadSourceMode} is one:
 * the choice has to be stated, not inherited.
 */
public enum SubscriptionsSourceMode {

    /** The reference-data query-API adapter. The deployed value, and the default. */
    LIVE,

    /**
     * The refusing stub, which answers no query and reports that it cannot.
     *
     * <p>For local runs and for the container suites whose subject is settlement and the processed
     * log rather than the register's recipients. Deliberately a refusal and not an empty answer: an
     * empty answer is a legitimate business outcome — {@code no-subscriptions} — so a stub that gave
     * one would let a real hearing record a court register addressed to nobody as COMPLETED.
     */
    STUB
}
