package uk.gov.hmcts.cp.courtregister.domain;

/**
 * The fingerprint of a request's immutable fields.
 *
 * <p>Compile-safe seam for T008: the shape of the call is declared so the canonicalisation rules can
 * be pinned; the hash itself arrives with T013.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
        // Function holder.
    }

    /**
     * Returns the lowercase hex SHA-256 of the command's canonical immutable form.
     *
     * @param command the validated request
     * @return the fingerprint
     */
    @SuppressWarnings("PMD.ShortMethodName")
    public static String of(final DistributionCommand command) {
        throw new UnsupportedOperationException("RequestFingerprint.of is implemented by T013");
    }
}
