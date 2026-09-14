package uk.gov.hmcts.cp.courtregister.application;

import uk.gov.hmcts.cp.courtregister.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionReport;

/**
 * Where one built report is delivered.
 *
 * <p>Two implementations ship - the structured events the platform's log collection carries into
 * Log Analytics, and the e-mail with the exception list attached as a CSV - and neither knows the
 * other exists. Behind one port they are two implementations of one capability, which is what stops
 * them being able to take each other down and what keeps the logging library out of
 * {@code application/}: the structured-event shape is an adapter's concern in exactly the way an
 * HTTP body is.
 *
 * <p>Nothing here names a logging library, an HTTP client or a file store. What the service knows
 * is that a report goes somewhere and that the somewhere says how it went.
 */
public interface ExceptionReportSink {

    /**
     * Delivers one report, answering how it went rather than throwing.
     *
     * <p>A sink that could not deliver says so with a bounded reason. It never stops the other
     * sink and it never ends the run: a report that reached one of its two audiences is a
     * partially delivered report, not a failed one (FR-007).
     *
     * @param report the report to deliver
     * @return how the delivery went, with a bounded reason and the per-recipient counts
     */
    DeliveryOutcome deliver(ExceptionReport report);
}
