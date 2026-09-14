package uk.gov.hmcts.cp.courtregister.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;

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

    /**
     * How far back a schedule may be searched before it is treated as one that never fires.
     *
     * <p>A year and a day. The two schedules this serves fire on every weekday, so the search ends
     * on its first or second step; the bound exists because a cron that matches nothing - a 30th of
     * February, a day-of-week and day-of-month pair that never coincide - would otherwise be an
     * unbounded walk backwards rather than a refusal.
     */
    private static final int SEARCH_DAYS = 366;

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
        final CronExpression schedule = CronExpression.parse(cron);
        final ZonedDateTime moment = instant.atZone(ZoneId.of(zone));
        ZonedDateTime latest = null;
        for (int daysBack = 0; daysBack <= SEARCH_DAYS && latest == null; daysBack++) {
            latest = lastOccurrenceOn(schedule, moment.minusDays(daysBack).toLocalDate(), moment);
        }
        if (latest == null) {
            throw new IllegalArgumentException("the schedule fired at no point in the "
                    + SEARCH_DAYS + " days before the moment asked about");
        }
        return latest.toInstant();
    }

    /**
     * The last time the schedule fired on one local day, before a given moment.
     *
     * <p>Walked forwards from the start of the day rather than searched backwards, because
     * {@code CronExpression} only answers forwards. The day is entered a nanosecond early so that
     * an occurrence at midnight itself is found, and every candidate is checked to be still on the
     * day being asked about - which is what keeps the walk from wandering into the next one across
     * a clock change, where a local day is twenty-three or twenty-five hours long.
     *
     * @param schedule the parsed cron
     * @param day      the local day being searched
     * @param moment   the moment every occurrence must fall strictly before
     * @return the last occurrence on that day before the moment, or {@code null} where there is
     *     none
     */
    private static ZonedDateTime lastOccurrenceOn(final CronExpression schedule,
            final LocalDate day, final ZonedDateTime moment) {
        ZonedDateTime latest = null;
        ZonedDateTime candidate =
                schedule.next(day.atStartOfDay(moment.getZone()).minusNanos(1));
        while (candidate != null && candidate.isBefore(moment)
                && candidate.toLocalDate().equals(day)) {
            latest = candidate;
            candidate = schedule.next(candidate);
        }
        return latest;
    }
}
