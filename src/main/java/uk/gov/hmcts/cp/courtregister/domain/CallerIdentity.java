package uk.gov.hmcts.cp.courtregister.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Who a run's outbound calls are made as.
 *
 * <p>Compile-safe seam for T007: the shape and the system constant are declared so the tests can
 * name them; the resolution rules arrive with T012.
 *
 * @param userId the user the run is attributed to, where the message named one
 */
public record CallerIdentity(Optional<UUID> userId) {

    /** A run no user is named for. */
    public static final CallerIdentity SYSTEM = new CallerIdentity(Optional.empty());

    /**
     * The identity the command was published under.
     *
     * @param command the validated request
     * @return the user it named, or {@link #SYSTEM} where it named none
     */
    @SuppressWarnings("PMD.ShortMethodName")
    public static CallerIdentity of(final DistributionCommand command) {
        throw new UnsupportedOperationException("CallerIdentity.of is implemented by T012");
    }

    /**
     * The value a {@code CJSCPPUID} header carries for this run.
     *
     * @param systemUserId the identity configured for the client making the call
     * @return the run's user where it has one, and the configured identity otherwise
     */
    public String orSystem(final String systemUserId) {
        throw new UnsupportedOperationException("CallerIdentity.orSystem is implemented by T012");
    }
}
