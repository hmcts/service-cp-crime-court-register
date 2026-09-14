package uk.gov.hmcts.cp.courtregister.domain;

import java.time.Instant;

/**
 * When a given schedule last fired before a given moment.
 *
 * <p>One computation, needed twice by this increment and by two different schedules. The
 * <strong>generation</strong> cron answers the never-batched BATCH_LATE rule - a register the most
 * recent scheduled generation run left where it was is late, and one recorded since it is not - and
 * the <strong>report</strong> cron answers where a scheduled run's window opens, through
 * {@link ReportWindow#sinceLastScheduledRun(String, String, Instant)}. The cron and the zone are
 * arguments rather than settings this class binds, because two callers give it two.
 *
 * <p><strong>It lives in {@code domain/} rather than in {@code batch/}</strong>, and it is worth
 * saying why, because a cron expression looks like scheduling. It is a pure computation over a cron
 * expression, a zone and an instant: it reaches nothing, holds nothing, and decides nothing about
 * when anything runs. {@link ReportWindow} - itself a domain record - is one of its two callers, so
 * a {@code batch} home would make a domain type depend on a batch one.
 *
 * <p>The one type it borrows is Spring's {@code org.springframework.scheduling.support.CronExpression},
 * which is a parser and a pure {@code next(Temporal)} function over the six-field dialect this
 * service's two schedules are already written in. Re-implementing the dialect here so that the
 * domain imported nothing would be a second parser to keep in step with the one the schedules are
 * actually read by - a fact written twice, and the morning the two disagree is the morning a report
 * covers a window nothing ran over.
 */
public final class LastScheduledRun {

    private LastScheduledRun() {
        // A computation, not a thing.
    }

    /**
     * The most recent occurrence of a cron, in a zone, strictly before an instant.
     *
     * @param cron    the schedule, in Spring's six-field dialect
     * @param zone    the zone the schedule is read in
     * @param instant the moment to look back from
     * @return the most recent occurrence strictly before it
     */
    public static Instant before(final String cron, final String zone, final Instant instant) {
        throw new UnsupportedOperationException(
                "the most-recent-occurrence computation is not written yet");
    }
}
