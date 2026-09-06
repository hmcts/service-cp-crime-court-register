package uk.gov.hmcts.cp.courtregister.domain;

/**
 * Which of the two completion mechanisms learned a batch's outcome.
 *
 * <p>Recorded rather than inferred, and it is what the {@code reconciled} metric counts: a run whose
 * outcomes all arrive by RECONCILER is a broker or a subscription to look at, and nothing else in
 * the flow would say so.
 *
 * <p>Its own type rather than a nested one because it is an argument before it is a column. Both
 * completion mechanisms carry it through {@code DocumentOutcomeSink} into the store's {@code mark}
 * calls, and a batch state change is a compare-and-set: there is no moment between the transition
 * and the transition's own statement in which this could be written separately.
 */
public enum CompletedBy {

    /** The {@code public.event} listener, which is the platform pattern and the default. */
    EVENT,

    /** The grace-period reconciler asking systemdocgenerator's query API. */
    RECONCILER
}
