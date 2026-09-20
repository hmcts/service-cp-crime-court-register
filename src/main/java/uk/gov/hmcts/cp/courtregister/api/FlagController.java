package uk.gov.hmcts.cp.courtregister.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.courtregister.api.dto.FlagResponse;
import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.domain.FlagDecision;

/**
 * {@code GET /operations/flag} - what the one lever says, asked from this pod.
 *
 * <p>The endpoint the {@code check-flag} command was, and the same reader behind it: the question
 * is what <em>this</em> pod would get at 18:00, and a second reader configured differently would
 * answer a different one. It is read once per call with no cache, because a flag that was on an
 * hour ago says nothing about a rollback ten minutes ago.
 *
 * <p><strong>All three readings are {@code 200}</strong> (spec assumption 2). The command exited
 * {@code 2} on an unreadable flag and an earlier draft mapped that to {@code 503}; it is not. The
 * endpoint answered the question it exists to answer, and the answer is "nobody can read the flag".
 * A {@code 503} would be a claim about this service rather than about the flag, would read to a
 * gateway as this pod being down, and may cost the body that says why.
 *
 * <p>Nothing is interpreted here and nothing is written down. The reading's own bounded code is
 * what comes back, so the store's words about itself reach an operator's terminal no more than they
 * reach the log (constitution Principle VII).
 *
 * <p>Conditional on the generation half being switched on, because that is the only place
 * {@link FeatureFlagReader} is contributed and it is exactly where {@code check-flag} answered "not
 * wired" - the pod that renders nothing has no 18:00 read to describe. <strong>And on the profile
 * for the same reason</strong>: both configurations that contribute a reader, live and stub, are
 * declared {@code !test}, so a {@code test}-profile context with generation switched on has the
 * switch without the bean - which is the exact shape four of this repository's context suites run
 * in.
 */
@RestController
@Profile("!test")
@ConditionalOnProperty(prefix = "courtregister.generation", name = "enabled", havingValue = "true")
public class FlagController {

    /** The one lever's reader, asked once per call and never remembered. */
    private final FeatureFlagReader reader;

    /**
     * Creates the endpoint over the reader the nightly run uses.
     *
     * @param flagReader the one lever's reader, which never throws
     */
    public FlagController(final FeatureFlagReader flagReader) {
        this.reader = flagReader;
    }

    /**
     * Reads the flag and says what it said.
     *
     * @return the reading, as one of three bounded words and, where it could not be read, the
     *         bounded cause
     */
    @GetMapping(path = "/operations/flag", produces = MediaType.APPLICATION_JSON_VALUE)
    public FlagResponse flag() {
        return answered(reader.read());
    }

    /**
     * Maps one reading onto the body it is answered as.
     *
     * <p>A switch expression over the sealed type, as {@code CheckFlagCli.answered} was: a fourth
     * reading would not compile rather than falling to a default that said {@code OFF}.
     *
     * @param reading what the reader answered, which is never an exception
     * @return the body for that reading
     */
    private static FlagResponse answered(final FlagDecision reading) {
        return switch (reading) {
            case FlagDecision.Enabled ignored -> new FlagResponse(FlagResponse.ON, null);
            case FlagDecision.Disabled ignored -> new FlagResponse(FlagResponse.OFF, null);
            case FlagDecision.Unreadable unreadable ->
                new FlagResponse(FlagResponse.UNREADABLE, unreadable.reason().code());
        };
    }
}
