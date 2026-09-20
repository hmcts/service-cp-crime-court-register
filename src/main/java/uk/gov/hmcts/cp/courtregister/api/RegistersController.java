package uk.gov.hmcts.cp.courtregister.api;

import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.courtregister.api.dto.RecordedWhileOffResponse;
import uk.gov.hmcts.cp.courtregister.application.BatchListingService;
import uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException;

/**
 * The register endpoints: what is waiting, and - from a later task - what a rollback may give up.
 *
 * <p>An inbound adapter. It takes nothing, calls {@link BatchListingService}, and maps the answer.
 *
 * <p>The listing exists because automatic batching passes these rows over deliberately: they were
 * recorded while the flag said the legacy generates. Without an answer that names them, a rollback
 * would leave every one waiting for somebody to find it.
 *
 * <p>{@code @Profile("!test")} for the reason {@link BatchesController} carries it: the store is
 * declared `!test` in the processed-log configuration and that profile has no database. And
 * {@code courtregister.operations.enabled} for the reason it carries that: the switch that stops
 * this service answering the operator's paths withdraws the listings too, and a controller left
 * scanned over one that is gone is a pod that will not start (FR-044).
 */
@RestController
@Profile("!test")
@ConditionalOnProperty(prefix = "courtregister.operations", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RegistersController {

    /** An instance that names nowhere, which is how the field is kept out of the body. */
    private static final URI NOWHERE = URI.create("");

    private static final Logger LOG = LoggerFactory.getLogger(RegistersController.class);

    /** The rows could not be read, so there is no listing to give. */
    private static final String LISTING_FAILED = "listing-failed";

    /** The two listings, over the three reads they are built from. */
    private final BatchListingService listings;

    /**
     * Creates the endpoints over the listings they answer from.
     *
     * @param batchListings the application service holding the reads
     */
    public RegistersController(final BatchListingService batchListings) {
        this.listings = batchListings;
    }

    /**
     * The registers automatic batching passed over because the flag did not say ON.
     *
     * @return the waiting registers, or the bounded refusal that stopped the listing being made
     * @throws RuntimeException any failure that is not the store being unreachable, which is a
     *         defect in this service and is answered {@code 500} rather than being dressed up as
     *         a dependency outage (FR-023)
     */
    // PMD.OnlyOneReturn: the listing and the refusal are said where each is decided.
    @SuppressWarnings("PMD.OnlyOneReturn")
    @GetMapping(path = "/operations/registers/recorded-while-off",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> recordedWhileOff() {
        try {
            final List<RecordedWhileOffResponse.RecordedRegister> waiting =
                    listings.recordedWhileOff().stream()
                            .map(record -> new RecordedWhileOffResponse.RecordedRegister(
                                    record.recordId(), record.hearingId(), record.registerDate(),
                                    record.flag()))
                            .toList();
            return ResponseEntity.ok(new RecordedWhileOffResponse(waiting));
        } catch (StoreUnavailableException | DataAccessException notRead) {
            // The two shapes an unreachable store has, and only those two - for the reason
            // BatchesController states at the same catch. A defect answered 503 is a defect a
            // runbook retries for ever, so anything else is left to reach the 500 the status map
            // keeps for it.
            LOG.error("The registers recorded while the flag was off could not be read, so no "
                    + "listing is given. cause={}", notRead.getClass().getName());
            return refusal();
        }
    }

    /**
     * The one refusal this endpoint has, in bounded fields and nothing else.
     *
     * @return the refusal
     */
    private static ResponseEntity<Object> refusal() {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle(HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase());
        // Spring fills `instance` with the request URI whenever it is left null, which on the
        // notify path would be the batch id the caller typed. An EMPTY uri is serialised away by
        // the problem-detail mixin's NON_EMPTY rule, so this is how the field is suppressed rather
        // than populated; setting it to null would simply let the framework fill it in again.
        problem.setInstance(NOWHERE);
        problem.setProperty("reason", LISTING_FAILED);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
