package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.time.Clock;
import java.util.List;
import java.util.function.Consumer;
import uk.gov.hmcts.cp.courtregister.application.ExceptionReportService;
import uk.gov.hmcts.cp.courtregister.application.ExceptionReportSink;
import uk.gov.hmcts.cp.courtregister.config.ReportProperties;

/**
 * {@code report-exceptions [--since S] [--email]}.
 *
 * <p>The sixth command, and the one an incident reaches for: the same report the 07:00 run writes,
 * over a window an operator names, as a table on the stream a runbook step greps.
 *
 * <p><strong>Seam.</strong> The behaviour is T057's. This class exists so that
 * {@code ReportExceptionsCliTest} compiles and fails on its assertions rather than on the compiler.
 */
public class ReportExceptionsCli {

    /** What the command takes, printed under a refusal and on request. */
    /* default */ static final String USAGE = "usage: " + CliMain.REPORT_EXCEPTIONS
            + " [--" + Args.SINCE + " S] [--" + Args.EMAIL
            + "] (lists what has gone wrong since S, or since the previous scheduled report)";

    private final ExceptionReportService reporting;

    private final List<ExceptionReportSink> sinks;

    private final ReportProperties settings;

    private final Clock clock;

    private final Consumer<String> output;

    /**
     * Holds the report, the sinks it may go to, the settings that say whether there is an e-mail
     * output at all, the clock the window ends at, and the operator's own stream.
     *
     * @param reportService  the report, built and delivered through it
     * @param reportSinks    every sink on this context, of which this command chooses its own
     * @param reportSettings the report's settings, for the schedule and the e-mail switch
     * @param pods           this pod's reading of now, which is the window's end
     * @param lines          where the table is written, one line per call
     */
    public ReportExceptionsCli(final ExceptionReportService reportService,
            final List<ExceptionReportSink> reportSinks, final ReportProperties reportSettings,
            final Clock pods, final Consumer<String> lines) {
        this.reporting = reportService;
        this.sinks = List.copyOf(reportSinks);
        this.settings = reportSettings;
        this.clock = pods;
        this.output = lines;
    }

    /**
     * Builds the report for the window that was asked for and writes it out.
     *
     * @param args the arguments that followed {@code report-exceptions}
     * @return {@link CliMain#SUCCESS}, {@link CliMain#REFUSED} or {@link CliMain#FAILED}
     */
    public int run(final List<String> args) {
        throw new UnsupportedOperationException("the sixth command is not implemented yet");
    }
}
