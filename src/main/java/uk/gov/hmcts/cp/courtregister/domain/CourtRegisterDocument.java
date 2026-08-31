package uk.gov.hmcts.cp.courtregister.domain;

/**
 * The {@code add-court-register} command this service produces: one hearing's register, assembled
 * and ready for progression.
 *
 * <p><strong>A seam, deliberately narrow.</strong> The full record family — hearing venue,
 * defendants, parent guardians, cases and applications, offences, results, recipients, aliases,
 * counsels and addresses, twelve records matching the vendored progression schemas — lands with the
 * mapper phase, which is the phase whose tests can say what each component has to carry. What is
 * here is only what the pipeline itself names, and both come straight from
 * {@code OutboundCourtRegister/index.js:31-33}: the hearing the register is for, and the file name
 * progression stores it under.
 *
 * <p>Naming them now rather than typing the ports on a placeholder keeps the pipeline's contract
 * honest: a stage that hands this to the submission client is handing over one hearing's register,
 * and the compiler can say so before a single mapper exists.
 *
 * @param hearingId the hearing this register covers
 * @param fileName  the name progression stores the rendered register under
 */
public record CourtRegisterDocument(String hearingId, String fileName) {
}
