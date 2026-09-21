package uk.gov.hmcts.cp.courtregister.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.courtregister.api.dto.BatchListingResponse;
import uk.gov.hmcts.cp.courtregister.api.dto.GenerateRegisterRequest;
import uk.gov.hmcts.cp.courtregister.api.dto.GenerateRegisterResponse;
import uk.gov.hmcts.cp.courtregister.application.BatchListing;
import uk.gov.hmcts.cp.courtregister.application.BatchListingService;
import uk.gov.hmcts.cp.courtregister.application.OperationsRunLauncher;
import uk.gov.hmcts.cp.courtregister.application.OperationsRunLauncher.RunAccepted;
import uk.gov.hmcts.cp.courtregister.application.RegisterRegenerationService.Selection;
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
 *
 * <p><strong>And {@code courtregister.generation.enabled}, because two of its three endpoints
 * need the generating half.</strong> The regeneration hand-off and the resend are contributed only
 * where the flag gate, the assembler, the requesting leg and the notifier are, so a pod that
 * renders nothing holds none of them - and a controller left scanned over beans that do not exist
 * is the refresh failure the operations switch is written to avoid. Such a pod answers all three
 * batch paths {@code 501 command-not-wired} through {@code NotWiredController}, which is exactly
 * what the commands they replace answered there (FR-052).
 */
@RestController
@Profile("!test")
@ConditionalOnProperty(prefix = "courtregister.operations", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "courtregister.generation", name = "enabled",
        havingValue = "true")
public class BatchesController {

    private static final Logger LOG = LoggerFactory.getLogger(BatchesController.class);

    /** This service's own name for the one argument the listing takes. */
    private static final String DATE = "date";

    /** This service's own name for the argument that names one batch of a date. */
    private static final String BATCH_ID = "batchId";

    /** This service's own name for the argument that bounds a run to part of a date. */
    private static final String RECORDED_BEFORE = "recordedBefore";

    /** The two listings, over the three reads they are built from. */
    private final BatchListingService listings;

    /** The regeneration hand-off, which validates, reads the flag and answers with a run id. */
    private final OperationsRunLauncher launcher;

    /**
     * Creates the endpoints over the services they answer from.
     *
     * @param batchListings the application service holding the reads
     * @param runLauncher   the application service holding the regeneration hand-off
     */
    public BatchesController(final BatchListingService batchListings,
            final OperationsRunLauncher runLauncher) {
        this.listings = batchListings;
        this.launcher = runLauncher;
    }

    /**
     * Regenerates a register date, and answers as soon as the run has been accepted.
     *
     * <p>Every decision is the launcher's: the cross-field rule on the override, the one lever's
     * reading, the run id and the lock. This parses the three values that have to be read, one at
     * a time and each under its own name, so that a refusal can say <em>which</em> argument would
     * not read - the one thing about a refused value that may be written down, since the value
     * itself may not (FR-024).
     *
     * @param request what the caller asked for, or {@code null} where they sent no body
     * @return {@code 202} with the run id, the date as this service parsed it, and whether the run
     *         goes ahead over a flag that would have stopped it
     * @throws uk.gov.hmcts.cp.courtregister.domain.OperationsRefusedException where the date is
     *         absent, where any of the three values will not read, where an override was asked for
     *         without the one batch it may cover, or where the flag did not admit the run - all
     *         answered by {@link OperationsExceptionHandler} from the one status map
     */
    @PostMapping(path = "/operations/batches/generate",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> generate(
            @RequestBody(required = false) final GenerateRegisterRequest request) {

        final GenerateRegisterRequest asked =
                request == null ? GenerateRegisterRequest.NOTHING : request;
        if (asked.date() == null || asked.date().isBlank()) {
            throw new OperationsRefusedException(OperationsReason.MISSING_ARGUMENT, DATE, null);
        }
        final Selection selection = new Selection(dateOf(asked.date()), asked.courtHouse(),
                batchOf(asked.batchId()), instantOf(asked.recordedBefore()), asked.overrideAsked());
        final RunAccepted accepted = launcher.launch(selection);
        return ResponseEntity.accepted().body(new GenerateRegisterResponse(accepted.runId(),
                accepted.registerDate(), accepted.overridden()));
    }

    /**
     * The register date, as this service reads it.
     *
     * @param typed what the caller sent
     * @return the date
     */
    private static LocalDate dateOf(final String typed) {
        try {
            return LocalDate.parse(typed);
        } catch (DateTimeParseException notADate) {
            throw new OperationsRefusedException(OperationsReason.UNREADABLE_ARGUMENT, DATE,
                    notADate);
        }
    }

    /**
     * The one batch a caller named, or the absence of one.
     *
     * @param typed what the caller sent, which may be absent
     * @return the batch's identity, or {@code null} for every batch of the day
     */
    // PMD.OnlyOneReturn: absent and unreadable are different answers to different questions, each
    // said where it is decided.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static UUID batchOf(final String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(typed);
        } catch (IllegalArgumentException notAnIdentity) {
            throw new OperationsRefusedException(OperationsReason.UNREADABLE_ARGUMENT, BATCH_ID,
                    notAnIdentity);
        }
    }

    /**
     * The instant a caller bounded the run at, or the absence of a bound.
     *
     * @param typed what the caller sent, which may be absent
     * @return the exclusive bound, or {@code null} for the whole day
     */
    // PMD.OnlyOneReturn: as above - no bound and a bound that will not read are two answers.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static Instant instantOf(final String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(typed);
        } catch (DateTimeParseException notAnInstant) {
            throw new OperationsRefusedException(OperationsReason.UNREADABLE_ARGUMENT,
                    RECORDED_BEFORE, notAnInstant);
        }
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
