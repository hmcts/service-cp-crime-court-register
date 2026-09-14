package uk.gov.hmcts.cp.courtregister.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What one run of the exception report found.
 *
 * <p>Read-only by construction: it is composed from the repositories and nothing about it is ever
 * written back (FR-014). The {@code runId} is the correlation the caller opened and passed in
 * rather than something this type reads for itself, which is what keeps the application layer free
 * of the MDC and makes the correlation true on the command path as well as on the scheduled one.
 *
 * @param runId      the correlation the caller opened, the same value {@code RunCorrelation} put in
 *                   the MDC
 * @param window     what was asked for
 * @param snapshotAt when the reads were taken, which is not when the events were written
 * @param entries    every exception found, oldest first, across all five kinds
 */
public record ExceptionReport(
        String runId,
        ReportWindow window,
        Instant snapshotAt,
        List<ExceptionEntry> entries) {

    /**
     * How many of each kind, <strong>zero-filled</strong>.
     *
     * <p>Five numbers always, so the summary event carries five numbers always and an empty morning
     * is distinguishable from a morning the report did not run (FR-012). A kind omitted because it
     * had nothing is a kind a dashboard reads as absent, which is the same silence this service
     * exists to end.
     *
     * @return one count per kind, including the kinds that have none
     */
    public Map<ExceptionKind, Integer> counts() {
        throw new UnsupportedOperationException("the report's counts are not computed yet");
    }
}
