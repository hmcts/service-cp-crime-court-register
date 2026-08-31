package uk.gov.hmcts.cp.courtregister.adapter.progression;

import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;

/**
 * Holds an assembled register to the contract progression published, before it is sent.
 *
 * <p>The {@code add-court-register} command and its nested {@code courtRegisterDocument/*} schemas
 * are progression-owned, frozen and {@code additionalProperties: false}. They are vendored into
 * {@code src/main/resources/contracts/progression/} at {@code criminal-court-public-model}
 * 17.103.13 — the version progression compiles — and they are <strong>main</strong> resources rather
 * than test ones precisely because this check runs in production, on every document, before the
 * POST.
 *
 * <p><strong>Defect C29 is what this class exists for.</strong> The schemas require
 * {@code courtRegisterDefendant.address}, {@code courtRegisterParentGuardian.address} and
 * {@code courtRegisterAddress.address1}. The legacy address mapper answers nothing for an absent
 * address, so a child with no address on the payload produces a document progression answers 400
 * to — and the legacy swallows that 400 (C1), so the whole hearing's register is lost with no trace
 * anywhere. Validating first turns a silent loss into an explicit, bounded, replayable failure.
 *
 * <p>The failure is a {@link uk.gov.hmcts.cp.courtregister.domain.ContractValidationException}
 * carrying a bounded {@link uk.gov.hmcts.cp.courtregister.domain.ContractViolation} and the JSON
 * pointer of the offending field — never the field's <em>value</em>, which is a child's address, and
 * never a raw validator message, which may quote the document it choked on. Both would reach the
 * dead-letter description and the log index.
 *
 * <p>It is also C26's authority: the record family is honest about the wire only if the wire agrees,
 * and a fully-populated document validating against a closed schema is what proves that every field
 * the records declare is a field progression accepts.
 *
 * <p><strong>A seam.</strong> The behaviour lands in T055, against the assertions T052 writes.
 */
public final class OutboundContractValidator {

    /** Renders the typed document to the tree the schema is applied to. */
    private final ObjectMapper json;

    /**
     * Creates the validator.
     *
     * @param objectMapper the service's contract mapper, which is what serialises the document on
     *                     the way out too — validating with a different one would validate a
     *                     document nobody sends
     */
    public OutboundContractValidator(final ObjectMapper objectMapper) {
        this.json = objectMapper;
    }

    /**
     * Refuses a document the frozen contract would.
     *
     * @param document the assembled register
     * @throws uk.gov.hmcts.cp.courtregister.domain.ContractValidationException where the document
     *         does not satisfy the vendored schemas, carrying the bounded violation and the JSON
     *         pointer of the offending field
     */
    public void validate(final CourtRegisterDocument document) {
        throw new UnsupportedOperationException(
                "OutboundContractValidator.validate is implemented by T055");
    }
}
