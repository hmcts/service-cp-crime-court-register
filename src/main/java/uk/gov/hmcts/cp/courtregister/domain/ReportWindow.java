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
     * Refuses a window read backwards, or one of no width at all.
     *
     * <p>Either reports nothing and looks exactly like a quiet morning, which is the one failure
     * this report exists to make impossible, so both are refused where the window is made rather
     * than puzzled over where it is read.
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
     * <p>Two steps back through {@link LastScheduledRun}, and each is a different step. The run
     * has an occurrence of its own - the schedule is what woke it - and the first step finds it
     * <strong>at or before</strong> {@code firedAt}, because a scheduler hands a run its trigger a
     * few milliseconds after the instant it was due and sometimes on the instant itself. A strictly
     * earlier step would skip its own occurrence on an exact fire and open the window a whole period
     * too early, reporting the same failures twice. The second step is then strictly before that
     * occurrence, and lands on the run that last reported.
     *
     * <p>The end is {@code firedAt} and not the occurrence, so a run held up covers its own period
     * and the delay as well: <strong>the window widens and never shrinks</strong>, and nothing
     * falls into a gap between two reports. There is deliberately no tolerance around the
     * occurrence - "near enough to count as on it" is a second boundary to get wrong, and the
     * at-or-before step already answers the only case a tolerance was ever for.
     *
     * @param cron    the report's schedule, in Spring's six-field dialect
     * @param zone    the zone it is read in
     * @param firedAt the moment the run was handed its trigger, which is the window's end
     * @return the window from the previous run to the moment this one started
     */
    public static ReportWindow forScheduledRun(final String cron, final String zone,
            final Instant firedAt) {
        final Instant ownOccurrence = LastScheduledRun.atOrBefore(cron, zone, firedAt);
        return new ReportWindow(LastScheduledRun.before(cron, zone, ownOccurrence), firedAt);
    }

    /**
     * The window a caller with no occurrence of its own covers: from the last run, to now.
     *
     * <p>What a bare {@code report-exceptions} answers (FR-009). One step, because the caller is
     * not a run: nothing woke it on a schedule, so the most recent occurrence strictly before
     * {@code now} is the run that last reported and not a run that is firing. An operator asking at
     * 06:59 reads the window this morning's run is about to read; one asking at 09:00 reads what has
     * gone wrong since that run reported, rather than repeating it.
     *
     * <p>A scheduled run must <strong>not</strong> use this: the moment it fires is its own
     * occurrence or a hair past it, and one step from there gives a window a few milliseconds wide
     * and a morning that looks quiet. That run uses
     * {@link #forScheduledRun(String, String, Instant)}.
     *
     * @param cron the report's schedule, in Spring's six-field dialect
     * @param zone the zone it is read in
     * @param now  the moment the caller asked, which is the window's end
     * @return the window from the last scheduled run to now
     */
    public static ReportWindow sinceLastScheduledRun(final String cron, final String zone,
            final Instant now) {
        return new ReportWindow(LastScheduledRun.before(cron, zone, now), now);
    }
}
