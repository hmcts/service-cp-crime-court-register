package uk.gov.hmcts.cp.courtregister.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Everything written about a court register in the breath before it is posted.
 *
 * <p>One value rather than a parameter list because every component of it is known at the same
 * moment — the document has been assembled and validated, and nothing remains but the POST — and
 * because four of them are strings or identifiers that a positional call would happily accept in the
 * wrong order.
 *
 * <p>The court register produces at most one output per request, so {@code (source, requestId)} is
 * the whole key; the court centre, its OU code, the register day and the file name are descriptive
 * columns support reads, not part of what makes the row unique.
 *
 * <p>{@code requestDigest} and {@code anomalySummary} are the two things worth more after a failure
 * than after a success: what was attempted, and which parts of the register were skipped to produce
 * it. Both are written before the POST and neither is erased by its outcome.
 *
 * @param outputId          identity for a row written for the first time; a re-claim leaves the
 *                          existing row's identity alone
 * @param source            the request's key, part 1
 * @param requestId         the request's key, part 2
 * @param courtCentreId     the court centre the register was assembled for
 * @param courtCentreOuCode that court centre's OU code, or {@code null} where the hearing carried
 *                          none
 * @param registerDate      the register day derived from the command's shared time (fix C10)
 * @param fileName          the file name the document was built under (fix C11)
 * @param requestDigest     SHA-256 of exactly the bytes about to be sent
 * @param anomalySummary    bounded reason-code counts for the guarded transformation anomalies this
 *                          register survived, or {@code null} where there were none
 */
public record ProcessedOutputClaim(
        UUID outputId,
        String source,
        UUID requestId,
        UUID courtCentreId,
        String courtCentreOuCode,
        LocalDate registerDate,
        String fileName,
        String requestDigest,
        String anomalySummary) {
}
