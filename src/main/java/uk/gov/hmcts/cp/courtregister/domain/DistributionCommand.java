package uk.gov.hmcts.cp.courtregister.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The validated form of an inbound queue message.
 *
 * <p>Compile-safe seam for T007: the component list is the agreed contract, and the behaviour the
 * record owes — the absent-never-null guard and the convenience constructor for a message naming no
 * user — arrives with T012.
 *
 * @param source      publishing system
 * @param requestId   publisher-minted request identity
 * @param hearingId   the resulted hearing
 * @param hearingDay  the hearing day this share relates to
 * @param sharedTime  the instant at which the hearing was shared
 * @param eventType   the publishing event
 * @param userId      the user who shared the results, where the message named one
 */
public record DistributionCommand(
        String source,
        UUID requestId,
        UUID hearingId,
        LocalDate hearingDay,
        Instant sharedTime,
        String eventType,
        Optional<UUID> userId) {
}
