package uk.gov.hmcts.cp.courtregister.batch;

import uk.gov.hmcts.cp.courtregister.domain.RunReport;

/**
 * The nightly run: flag, assemble, request, report.
 *
 * <p>The order is the whole of it. {@link FeatureFlagGate} is asked first and its answer ends the
 * run where it is not ON, because a run that assembled before it read the flag would have stamped
 * rows into batches the flag says this service may not generate (constitution Cutover Rule). Then
 * one batch at a time, sequentially, each through
 * {@link uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService}; the run deadline
 * bounds the requesting and nothing else, because completion arrives on the public-event topic long
 * after the run has ended.
 *
 * <p>Every run produces a {@link RunReport}, the skipped ones included: a report that only appeared
 * when work happened would make "the flag is off" and "the job did not fire" the same silence, and
 * before cutover the first of those is every night.
 *
 * <p><strong>The schedule and the lock arrive with T050</strong>, and deliberately not before: the
 * {@code @Scheduled(cron = "0 0 18 * * MON-FRI", zone = "Europe/London")} and
 * {@code @SchedulerLock} that this class carries are themselves what
 * {@code RegisterGenerationJobTest.job_is_scheduled_in_europe_london} asserts, so a seam that
 * already wore them would be a pin that passed before anything wired it. Nothing here is annotated
 * yet, and no context creates it.
 *
 * <p><strong>Seam only.</strong> The job lands with T050; until then this throws, so that
 * {@code RegisterGenerationJobTest} records a failing assertion rather than a compile error.
 */
public class RegisterGenerationJob {

    /**
     * Runs one generation, from the flag read to the report.
     *
     * @return what the run did, including a run the flag stopped
     */
    public RunReport run() {
        throw new UnsupportedOperationException("T050");
    }
}
