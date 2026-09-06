package uk.gov.hmcts.cp.courtregister.application;

import java.util.UUID;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;

/**
 * What one batch's requesting leg ended as, told back to the run.
 *
 * <p>The durable answer is the {@code register_batch} row, which the service has already written by
 * the time this is returned; this is what the run counts and what the run report is built from, so
 * that the job never has to re-read a row to know what it just did.
 *
 * <p>A batch that reached GENERATING is not finished, and this says so honestly: the outcome of the
 * <em>run</em> is that a render was asked for, and the outcome of the batch arrives later on the
 * public-event topic. Nothing here waits for it.
 *
 * @param batchId       the batch this is about
 * @param status        where the batch stood when the requesting leg let go of it
 * @param failureReason the bounded reason where it failed, and {@code null} where it did not
 */
public record BatchOutcome(
        UUID batchId,
        BatchStatus status,
        BatchFailureReason failureReason) {
}
