package uk.gov.hmcts.cp.courtregister.adapter.report;

import uk.gov.hmcts.cp.courtregister.application.ExceptionReportSink;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionReport;
import uk.gov.hmcts.cp.courtregister.domain.ReportSinkName;

/**
 * The report, written as structured events the platform's log collection carries.
 *
 * <p>The seam the suite of T031 is written against. The two events land at T035.
 */
public class LogEventReportSink implements ExceptionReportSink {

    @Override
    public ReportSinkName name() {
        return ReportSinkName.LOG;
    }

    @Override
    public DeliveryOutcome deliver(final ExceptionReport report) {
        return DeliveryOutcome.delivered(ReportSinkName.LOG);
    }
}
