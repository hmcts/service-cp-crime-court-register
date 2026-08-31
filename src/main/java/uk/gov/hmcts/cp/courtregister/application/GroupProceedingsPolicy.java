package uk.gov.hmcts.cp.courtregister.application;

import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;

/**
 * Whether a hearing's group-proceedings flag suppresses its register.
 *
 * <p>The business rule ports unchanged: a group-proceedings hearing produces no court register. What
 * does not port is how the legacy reads the flag.
 * {@code CourtRegisterOrchestrator/index.js:21-23} tests
 * {@code isGroupProceedings == null || isGroupProceedings == false} and proceeds — so every value
 * that is neither of those suppresses the register, including the <em>string</em> {@code "false"},
 * which under JavaScript's loose equality is not equal to {@code false} at all. A producer that
 * serialised the flag as text would suppress every register it published, and the run would report
 * success with nothing to show for it.
 *
 * <p><strong>Defect fix C7.</strong> Only a JSON boolean {@code true} suppresses. {@code false},
 * {@code null} and an absent field all proceed, as they do today. Any other value is a contract
 * anomaly: it does not decide anything, it is WARN-logged, and it is counted as
 * {@code non-boolean-group-proceedings} so a producer sending one is visible. The suppression itself
 * is recorded by the pipeline as {@code COMPLETED, completion_reason = group-proceedings}, which is
 * the other half of the fix — the legacy records nothing at all.
 *
 * <p>The WARN names the field and the JSON type it arrived as, never the value: this line reaches a
 * log index shared by the whole estate, and the rule that keeps payload content out of it does not
 * bend for a value that looks harmless (constitution Principle VII).
 */
public class GroupProceedingsPolicy {

    /** The marker the red run records while the policy is unwritten. */
    private static final String UNIMPLEMENTED = "the group-proceedings policy is not written yet";

    private final ProcessingMetrics metrics;

    /**
     * Creates the policy over the instrument surface a contract anomaly is counted on.
     *
     * @param metrics the instrument surface
     */
    public GroupProceedingsPolicy(final ProcessingMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Whether this hearing's register is suppressed as group proceedings.
     *
     * @param command the request being run, for the correlation identifiers a WARN must carry
     * @param hearing the hearing payload, exactly as the producer sent it
     * @return whether the register is suppressed
     */
    public boolean suppresses(final DistributionCommand command, final JsonNode hearing) {
        throw new UnsupportedOperationException(UNIMPLEMENTED);
    }
}
