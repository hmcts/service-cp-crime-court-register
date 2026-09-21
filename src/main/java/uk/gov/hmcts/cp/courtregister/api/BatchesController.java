package uk.gov.hmcts.cp.courtregister.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.courtregister.api.dto.BatchListingResponse;
import uk.gov.hmcts.cp.courtregister.application.BatchListing;
import uk.gov.hmcts.cp.courtregister.application.BatchListingService;
import uk.gov.hmcts.cp.courtregister.domain.OperationsReason;
import uk.gov.hmcts.cp.courtregister.domain.OperationsRefusedException;
import uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException;

/**
 * The batch endpoints: what a register date holds, and - from a later task - what to do about it.
 *
 * <p>An inbound adapter and nothing else. It parses the one argument, calls
 * {@link BatchListingService}, and maps the answer; the three reads a listing is built from are the
 * service's, because a controller that held a repository would not be an adapter any more.
 *
 * <p><strong>Nothing the caller typed comes back.</strong> A date that will not read is refused by
 * the name of the <em>argument</em>, never by its value (FR-025): a refusal is read in a log index
 * and in a ticket, and the characters an operator typed are the one thing this service cannot
 * vouch for. The date on a successful listing is this service's own parse, not those characters.
 *
 * <p>{@code @Profile("!test")} because {@link BatchListingService} carries it: the store and its
 * two repositories are declared `!test` in the processed-log configuration, that profile has no
 * database at all, and a controller over readers that do not exist is a context that will not
 * refresh.
 *
 * <p><strong>And the same condition the listings carry</strong>, because
 * {@code courtregister.operations.enabled=false} is what stops this service answering the
 * operator's paths at all (FR-044). The switch withdraws the listings; a controller left scanned
 * over a listing nothing contributes is a pod that will not start, which is the one thing a
 * deployment shape setting may not do.
 */
@RestController
@Profile("!test")
@ConditionalOnProperty(prefix = "courtregister.operations", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class BatchesController {

    private static final Logger LOG = LoggerFactory.getLogger(BatchesController.class);

    /** This service's own name for the one argument the listing takes. */
    private static final String DATE = "date";

    /** The two listings, over the three reads they are built from. */
    private final BatchListingService listings;

    /**
     * Creates the endpoints over the listings they answer from.
     *
     * @param batchListings the application service holding the reads
     */
    public BatchesController(final BatchListingService batchListings) {
        this.listings = batchListings;
    }

    /**
     * One register date's batches, each with its record count and its recipients.
     *
     * @param date the register date, as an ISO local date; required
     * @return the date's batches, or the bounded refusal that stopped the listing being made
     * @throws RuntimeException any failure that is not the store being unreachable, which is a
     *         defect in this service and is answered {@code 500} rather than being dressed up as
     *         a dependency outage (FR-023)
     */
    @GetMapping(path = "/operations/batches", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> list(
            @RequestParam(name = DATE, required = false) final String date) {

        if (date == null || date.isBlank()) {
            throw new OperationsRefusedException(OperationsReason.MISSING_ARGUMENT, DATE, null);
        }
        final LocalDate registerDate;
        try {
            registerDate = LocalDate.parse(date);
        } catch (DateTimeParseException notADate) {
            throw new OperationsRefusedException(OperationsReason.UNREADABLE_ARGUMENT, DATE,
                    notADate);
        }
        try {
            return ResponseEntity.ok(listed(registerDate));
        } catch (StoreUnavailableException | TransientDataAccessException
                | RecoverableDataAccessException | DataAccessResourceFailureException notRead) {
            // The shapes an unreachable store has on this path, and only those: the store
            // translates its own outage into the first, and a repository that reached the driver
            // before the driver reached the database hands the refusal out as one of the other
            // three. The whole DataAccessException hierarchy is NOT what is caught - a bad grammar,
            // a violated constraint or a mapping that will not read are all defects in this
            // service wearing the store's exception type, and a defect answered 503 is a defect a
            // runbook retries for ever. Anything else is left to reach the 500 the design rules'
            // status map keeps for it, whose body OperationsErrorAttributes renders in the same
            // bounded fields.
            LOG.error("The batches of one register date could not be read, so no listing is given "
                    + "for it. cause={}", notRead.getClass().getName());
            throw new OperationsRefusedException(OperationsReason.LISTING_FAILED, null,
                    notRead);
        }
    }

    /**
     * Maps the service's answer onto the body.
     *
     * @param registerDate the date the listing is for
     * @return the listing as it is answered
     */
    private BatchListingResponse listed(final LocalDate registerDate) {
        final List<BatchListingResponse.BatchSummary> summaries =
                listings.batchesOn(registerDate).stream().map(BatchesController::summarised)
                        .toList();
        return new BatchListingResponse(registerDate, summaries);
    }

    /**
     * One batch, as the response describes it.
     *
     * @param listing the batch as the service answered it
     * @return the batch as the body carries it
     */
    private static BatchListingResponse.BatchSummary summarised(final BatchListing listing) {
        return new BatchListingResponse.BatchSummary(listing.batchId(), listing.courtHouse(),
                listing.state(), listing.records(),
                listing.recipients().stream()
                        .map(recipient -> new BatchListingResponse.RecipientOutcome(
                                recipient.address(), recipient.outcome()))
                        .toList());
    }

}
