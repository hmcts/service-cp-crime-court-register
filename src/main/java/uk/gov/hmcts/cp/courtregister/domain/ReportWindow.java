package uk.gov.hmcts.cp.courtregister.domain;

import java.time.Instant;

/**
 * The period a report is a statement about.
 *
 * <p>The three failed kinds are bounded by it, so a report is a snapshot of a period rather than a
 * growing ledger; the two late kinds deliberately are not, because a request stuck for three days
 * is late this morning whether or not it arrived inside the window.
 *
 * <p>There is deliberately <strong>no window setting</strong>. A scheduled run's window opens at
 * the previous occurrence of its own cron, so a duration beside the schedule would be one fact
 * written twice and the morning the two disagree is the morning a failure falls into the gap
 * between two windows or is reported in both.
 *
 * @param from the window's start: the previous scheduled run for a scheduled run, {@code --since}
 *             for a command
 * @param to   the window's end, which is always the moment the run started
 */
public record ReportWindow(Instant from, Instant to) {

    /**
     * Refuses a window read backwards, which would report nothing and look like a quiet morning.
     */
    public ReportWindow {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException(
                    "a report window runs forwards, from a moment strictly before its end");
        }
    }

    /**
     * The window a scheduled run covers: from the run before its own occurrence, to when it fired.
     *
     * @param cron    the report's schedule, in Spring's six-field dialect
     * @param zone    the zone it is read in
     * @param firedAt the moment the run was handed its trigger, which is the window's end
     * @return the window from the previous run to the moment this one started
     */
    public static ReportWindow forScheduledRun(final String cron, final String zone,
            final Instant firedAt) {
        throw new UnsupportedOperationException("the scheduled run's window is not computed yet");
    }

    /**
     * The window a scheduled run covers: from the run before this one, to now.
     *
     * <p>Two steps back through {@link LastScheduledRun}, and the second step is the whole point.
     * A run fires a few milliseconds after its own occurrence, so the most recent occurrence before
     * {@code now} is the one currently firing; the window opens at the occurrence before
     * <em>that</em>, which is the run that last reported. One step would give a window a few
     * milliseconds wide and a morning that looked quiet.
     *
     * @param cron the report's schedule, in Spring's six-field dialect
     * @param zone the zone it is read in
     * @param now  the moment the run started, which is the window's end
     * @return the window from the previous run to now
     */
    public static ReportWindow sinceLastScheduledRun(final String cron, final String zone,
            final Instant now) {
        final Instant firing = LastScheduledRun.before(cron, zone, now);
        return new ReportWindow(LastScheduledRun.before(cron, zone, firing), now);
    }
}
