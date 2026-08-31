package uk.gov.hmcts.cp.courtregister.application;

/**
 * What progression answered when a register was submitted.
 *
 * <p>The status is carried rather than discarded because it is written to
 * {@code processed_output.response_code}: "we sent it and it was accepted" and "we sent it and
 * something answered" are different facts, and the legacy records neither — it never inspects the
 * response at all (defect C1). Only {@code 202} reaches this record; anything else is a failure, so
 * this is a receipt and never a verdict.
 *
 * @param responseCode the status progression answered with — {@code 202}, by construction
 */
public record SubmissionReceipt(int responseCode) {
}
