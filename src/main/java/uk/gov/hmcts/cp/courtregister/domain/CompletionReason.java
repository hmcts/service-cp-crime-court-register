package uk.gov.hmcts.cp.courtregister.domain;

/**
 * Why a request completed.
 *
 * <p>A bounded set rather than free text: the value is written to
 * {@code processed_request.completion_reason}, labels the completions counter, and is read by
 * support. All five are successes; what separates them is whether a register was sent and, if not,
 * which of the four legitimate business skips ended the run. Two of those skips are this flow's most
 * common results, so an undifferentiated success is the legacy defect (C33) rather than an
 * acceptable simplification — "nothing to publish" is a state, not silence.
 */
public enum CompletionReason {

    /** One POST was made and progression answered 202 — or the output was already POSTED. */
    SUBMITTED("submitted"),

    /** The hearing is group proceedings, which the business rule skips (C7, strictly typed). */
    GROUP_PROCEEDINGS("group-proceedings"),

    /** The hearing produced an empty register-defendant list (C6). */
    NO_DEFENDANTS("no-defendants"),

    /** Reference data answered and nothing matched the court centre's subscriptions. */
    NO_SUBSCRIPTIONS("no-subscriptions"),

    /** Subscriptions matched, and the youth filter left nobody on the register. */
    NO_YOUTH_DEFENDANTS("no-youth-defendants");

    private final String storedValue;

    CompletionReason(final String value) {
        this.storedValue = value;
    }

    /**
     * The value as it is written to the processed log, and the label the completion is counted
     * under. Fixed here rather than derived from the constant name, so renaming a constant cannot
     * silently rename a dashboard's series or a support query's answer.
     *
     * @return the bounded code for this completion
     */
    public String value() {
        return storedValue;
    }
}
