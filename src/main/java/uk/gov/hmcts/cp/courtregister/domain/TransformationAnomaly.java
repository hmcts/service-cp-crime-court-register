package uk.gov.hmcts.cp.courtregister.domain;

/**
 * A guarded, non-fatal anomaly met while transforming a hearing into a court register.
 *
 * <p>Each of these is a place the legacy pipeline threw, swallowed the exception and lost the whole
 * hearing's register, or dropped a recipient without leaving so much as a log line. The fixes keep
 * the register: the unresolvable part is skipped and the run completes. What must not be kept is the
 * silence, so every skip is counted here and written to {@code processed_output.anomaly_summary} as
 * a bounded reason-code count.
 *
 * <p>Deliberately not failures. {@code TRANSFORMATION_FAILED} is reserved for a transformation that
 * cannot produce a document at all; these produce one that is missing a part, which is a different
 * thing to tell an operator and a different thing to act on.
 */
public enum TransformationAnomaly {

    /** A youth-flagged defendant whose person details could not be resolved (C19). */
    UNRESOLVABLE_YOUTH_DEFENDANT("unresolvable-youth-defendant"),

    /** A court application that the hearing's applications do not contain (C20). */
    UNRESOLVABLE_APPLICATION("unresolvable-application"),

    /** A subscription asking for first- or second-class letter delivery, which is email-only (C27). */
    LETTER_DELIVERY_DROPPED("letter-delivery-dropped"),

    /** A recipient carrying no first email address to send to (C27). */
    RECIPIENT_MISSING_EMAIL("recipient-missing-email"),

    /** A recipient whose subscription is not marked for email delivery or distribution (C27). */
    RECIPIENT_NOT_FOR_DISTRIBUTION("recipient-not-for-distribution");

    private final String storedValue;

    TransformationAnomaly(final String value) {
        this.storedValue = value;
    }

    /**
     * The bounded code, as it is counted and as it appears in the anomaly summary. Codes only:
     * never free text, and never anything that could name a defendant.
     *
     * @return the bounded code for this anomaly
     */
    public String value() {
        return storedValue;
    }
}
