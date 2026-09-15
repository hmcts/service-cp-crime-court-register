package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.courtregister.application.ExceptionReportService;
import uk.gov.hmcts.cp.courtregister.application.ExceptionReportSink;
import uk.gov.hmcts.cp.courtregister.batch.RunCorrelation;
import uk.gov.hmcts.cp.courtregister.config.ReportProperties;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryStatus;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryWord;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionEntry;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionKind;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionReport;
import uk.gov.hmcts.cp.courtregister.domain.ReportRunOutcome;
import uk.gov.hmcts.cp.courtregister.domain.ReportSinkName;
import uk.gov.hmcts.cp.courtregister.domain.ReportWindow;

/**
 * {@code report-exceptions [--since S] [--email]}.
 *
 * <p>The sixth command, and the one an incident reaches for. The 07:00 run is the steady state; an
 * incident does not wait for morning, and this answers the same question over a window an operator
 * names, as a table on the stream a runbook step greps.
 *
 * <p><strong>The same service, not a second read path.</strong> It asks
 * {@link ExceptionReportService#build} exactly as {@code ExceptionReportJob} does, so a table read
 * at 02:00 and the morning's events cannot come to disagree about what an exception is, what its
 * age is or which kind it belongs to.
 *
 * <p><strong>The window, and what an unreadable one costs.</strong> {@code --since} takes an
 * ISO-8601 instant, an ISO-8601 duration or one of the {@code 2d} / {@code 2h} / {@code 30m} /
 * {@code 90s} shorthands, and the first form that parses wins (data-model.md). Absent, the window
 * opens at the most recent scheduled occurrence before now - the same
 * {@link ReportWindow#sinceLastScheduledRun} the schedule defines - so the bare command answers
 * what the morning run would have answered rather than a different question, and there is no window
 * setting to disagree with the schedule. A default window that cannot be computed at all is this
 * command failing to produce a report - {@link CliMain#FAILED}, not a refusal about an argument
 * nobody gave. Anything else typed is a refusal rather than a guess, and
 * <strong>the token is never quoted back</strong>: a command is reached by {@code kubectl exec}, so
 * its arguments are an operator's own typing, and a terminal is pasted into tickets (constitution
 * Principle VII).
 *
 * <p><strong>It chooses its sinks.</strong> The log sink always, and the e-mail sink only where
 * {@code --email} was given and the e-mail output is on. A command that e-mailed support whenever
 * it was run would make an incident's third invocation an incident of its own. {@code --email}
 * against a deployment whose e-mail output is off is {@link CliMain#REFUSED} under a bounded reason
 * that names the setting, and nothing is read and nothing is written; against an output that is on
 * with no sink behind it, the same refusal names the sink instead, because the setting is not the
 * thing to go and change.
 *
 * <p><strong>No flag, and no {@code --ignore-flag}.</strong> The report reads and writes nothing
 * the cutover decides, so the {@code CourtRegisterService} flag is not read here at all. That is
 * not a second lever appearing: it is a command that is not on the lever's circuit (constitution
 * Cutover Rule).
 *
 * <p><strong>No recipient address appears at all</strong> - not masked, absent. No read this
 * feature makes selects one, so a {@code NOTIFICATION_FAILED} line names its notification id, its
 * batch and its bounded response code and there is nothing left to mask.
 */
public class ReportExceptionsCli {

    /**
     * What the command takes, printed under a refusal and on request.
     *
     * <p>Package-visible because {@link CliMain} answers {@code --help} with it before it resolves
     * a single bean, exactly as it does for the other five.
     */
    /* default */ static final String USAGE = "usage: " + CliMain.REPORT_EXCEPTIONS
            + " [--" + Args.SINCE + " S] [--" + Args.EMAIL
            + "] (lists what has gone wrong since S, or since the previous scheduled report)";

    private static final Logger LOG = LoggerFactory.getLogger(ReportExceptionsCli.class);

    /** The one event name this command's last line is indexed under, as the job's run line is. */
    private static final String RUN_EVENT = "exception_report_run";

    /**
     * Roughly how long the counts line comes out, so the builder is sized once rather than grown.
     *
     * <p>Five bounded names, five numbers and the two instants: a hundred and sixty characters is
     * the shape of it, and being a little over costs one allocation nobody measures while being
     * under costs several.
     */
    private static final int A_COUNTS_LINE = 160;

    /** The line a window that covers nothing answers with, because silence is never the signal. */
    private static final String NOTHING_WRONG = "exceptions=none";

    /** The setting {@code --email} is declined against, named because the answer is a deploy. */
    private static final String EMAIL_SETTING = "courtregister.report.email.enabled";

    /** The bounded reason {@code --email} is declined under where the output is switched off. */
    private static final String EMAIL_OUTPUT_DISABLED = "email-output-disabled";

    /** And where the output is on and no sink was contributed behind it. */
    private static final String EMAIL_OUTPUT_NOT_WIRED = "email-output-not-wired";

    /**
     * What the second decline names instead of a setting, because the setting is not the problem.
     *
     * <p>{@code ReportEmailConfig} declares the sink on exactly the condition this command reads,
     * so on a deployed context the branch below cannot be reached. It is kept because nothing in
     * this class's own type says so - the sinks are handed in - and because the alternative to
     * declining is running log-only while an operator believes support was e-mailed. Naming the
     * setting here would send them to the one place there is nothing to change.
     */
    private static final String SINK_EMAIL = "sink=email";

    /** What this command could not finish, as the bounded reason its failure line carries. */
    private static final String NOT_REPORTED = "report-not-built";

    /**
     * The shorthand a window may be given in: a count and one of the four units.
     *
     * <p>Anchored at both ends and digits-only, so {@code 2 hours}, {@code 2H} and a bare {@code h}
     * are refusals rather than readings. What this must never do is decide which number a unit with
     * no number in front of it meant.
     */
    private static final Pattern SHORTHAND = Pattern.compile("^(\\d+)([dhms])$");

    /** How each shorthand unit is read, stated once beside the pattern that recognises it. */
    private static final Map<String, ChronoUnit> UNITS = Map.of(
            "d", ChronoUnit.DAYS,
            "h", ChronoUnit.HOURS,
            "m", ChronoUnit.MINUTES,
            "s", ChronoUnit.SECONDS);

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
     * Builds the report for the window that was asked for, delivers it and writes it out.
     *
     * <p>The whole invocation runs under a correlation of its own, so every line and every event an
     * on-demand report produces carries a run id exactly as the 07:00 run's do (FR-011). The id is
     * handed to the service as an argument rather than read there, which is what keeps
     * {@code application/} free of the MDC on this path as well as on the scheduled one.
     *
     * @param args the arguments that followed {@code report-exceptions}
     * @return {@link CliMain#SUCCESS}, {@link CliMain#REFUSED} where the arguments were not usable
     *         or the e-mail output is off, or {@link CliMain#FAILED} where a sink it asked failed
     */
    public int run(final List<String> args) {
        return RunCorrelation.under(() -> asked(args));
    }

    /**
     * One invocation, under the correlation {@link #run} opened for it.
     *
     * <p>Nothing outside this class is touched until every argument has been read: the parser, the
     * two refusals about {@code --email} and the window come first, and only then is a sink asked
     * even its own name. A refusal that had already spoken to a collaborator would be a refusal
     * that changed something, which is the one thing {@link CliMain#REFUSED} promises it did not.
     *
     * @param args the arguments that followed {@code report-exceptions}
     * @return the exit code
     */
    // PMD.OnlyOneReturn: the exits are the things that can happen to an invocation, each said where
    // it is decided; one exit would carry a verdict past a read that must not be made once the
    // arguments have been refused. PMD.AvoidCatchingGenericException: see the window's catch below.
    @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.AvoidCatchingGenericException"})
    private int asked(final List<String> args) {
        final Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException notUsable) {
            return CliMain.unreadable(CliMain.REPORT_EXCEPTIONS, USAGE, CliMain.NO_ARGUMENT_NAMED,
                    notUsable, output);
        }
        if (parsed.askedForHelp()) {
            output.accept(USAGE);
            return CliMain.SUCCESS;
        }
        if (!parsed.permits(Set.of(Args.SINCE), Set.of(Args.EMAIL))) {
            return CliMain.refusal(CliMain.REPORT_EXCEPTIONS, USAGE, CliMain.UNEXPECTED_ARGUMENT,
                    output);
        }
        final boolean emailAsked = parsed.flags().contains(Args.EMAIL);
        if (emailAsked && !settings.email().enabled()) {
            return declineEmail(EMAIL_OUTPUT_DISABLED + " setting=" + EMAIL_SETTING);
        }
        final Instant startedAt = clock.instant();
        final String typed = parsed.options().get(Args.SINCE);
        final ReportWindow window;
        if (typed == null) {
            try {
                window = sinceTheLastRun(startedAt);
            } catch (RuntimeException notAWindow) {
                // A window nobody typed. Whatever refused it - a schedule that cannot be read, a
                // zone that is not one - this is the command failing to produce a report, not an
                // operator having got an argument wrong, and telling them their --since was
                // unreadable would send them looking for an argument they never gave.
                return couldNotBuild(notAWindow);
            }
        } else {
            try {
                window = sinceTyped(typed, startedAt);
            } catch (RuntimeException notAWindow) {
                // Every reader a window goes through refuses in its own type -
                // DateTimeParseException from the two parsers, IllegalArgumentException from the
                // rules below them and from ReportWindow itself, DateTimeException from arithmetic
                // on an absurd count - and all of them mean the one thing an operator can act on:
                // this is not a window. Nothing is swallowed; unreadable names the argument and the
                // class that refused it, and refuses to write down either the message or the token
                // (constitution Principle VII).
                return CliMain.unreadable(CliMain.REPORT_EXCEPTIONS, USAGE, Args.SINCE, notAWindow,
                        output);
            }
        }
        final Optional<ExceptionReportSink> email = sinkNamed(ReportSinkName.EMAIL);
        if (emailAsked && email.isEmpty()) {
            return declineEmail(EMAIL_OUTPUT_NOT_WIRED + " " + SINK_EMAIL);
        }
        final List<ExceptionReportSink> asked = new ArrayList<>();
        sinkNamed(ReportSinkName.LOG).ifPresent(asked::add);
        if (emailAsked) {
            email.ifPresent(asked::add);
        }
        return reported(window, asked, emailAsked, startedAt);
    }

    /**
     * Builds the report, hands it to the sinks this invocation chose, and writes the table.
     *
     * <p>The last line is written after every sink has returned, because {@code delivered_log} and
     * {@code delivered_email} are claims about deliveries that have happened: written any earlier
     * it would report a delivery nobody had observed, and the morning that mattered would be the
     * morning a sink was refusing.
     *
     * @param window     the window that was asked for
     * @param asked      the sinks this invocation delivers to, in the order they are asked
     * @param emailAsked whether {@code --email} was given and accepted
     * @param startedAt  this invocation's own reading of now, taken once when it opened
     * @return {@link CliMain#SUCCESS} where every sink asked took it, else {@link CliMain#FAILED}
     * @throws ReportNotWritten where the destination refused a line, which is not this command's
     *                          answer to give
     */
    // PMD.AvoidCatchingGenericException: a read that would not answer arrives as the store's own
    // unchecked type, and a projection that has drifted from its table as IllegalArgumentException;
    // both mean the same thing here - this command could not produce a report - and a narrower
    // catch would leave the classes it does not name reaching an operator as a stack trace and the
    // process on the JVM's own 1, which is the code a runbook step reads as "declined". Nothing is
    // swallowed: the line below says it happened and the exit code is FAILED.
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
    private int reported(final ReportWindow window, final List<ExceptionReportSink> asked,
            final boolean emailAsked, final Instant startedAt) {

        try {
            final ExceptionReport report = reporting.build(window, RunCorrelation.current());
            final List<DeliveryOutcome> delivered = reporting.deliver(report, asked);
            table(report);
            runLine(window, report, delivered, emailAsked, startedAt);
            return everySinkTookIt(delivered) ? CliMain.SUCCESS : CliMain.FAILED;
        } catch (ReportNotWritten notWritten) {
            // The destination refused a line of the table, which is not a report that could not be
            // built: the reads happened. It leaves here for the entry point, which answers it
            // without a terminal.
            throw notWritten;
        } catch (RuntimeException notBuilt) {
            return couldNotBuild(notBuilt);
        }
    }

    /**
     * What this command says when it could not produce a report at all.
     *
     * <p>Two things reach it - a window nobody typed that cannot be computed, and a read that would
     * not answer - and they are the same fact to whoever is reading the terminal: there is no
     * report. {@link CliMain#FAILED} rather than {@link CliMain#REFUSED}, because this is the one a
     * runbook may retry, and the cause is named by class because its message belongs to whatever
     * raised it (constitution Principle VII).
     *
     * @param notBuilt what stopped it, read for its class and for nothing else
     * @return {@link CliMain#FAILED}
     */
    private int couldNotBuild(final RuntimeException notBuilt) {
        LOG.error("The exception report an operator asked for could not be produced, so "
                + "nothing was written to their terminal about what is wrong. cause={}",
                notBuilt.getClass().getName());
        return CliMain.failure(CliMain.REPORT_EXCEPTIONS, "", NOT_REPORTED, output);
    }

    /**
     * The window a bare invocation covers: the same one the morning run would have answered.
     *
     * <p>No fallback and no setting. A duration beside the schedule would be one fact written
     * twice, and the morning the two disagreed is the morning something fell into the gap between
     * two windows.
     *
     * @param now the window's end, which is always this pod's reading of now
     * @return the window from the last scheduled run to now
     */
    private ReportWindow sinceTheLastRun(final Instant now) {
        return ReportWindow.sinceLastScheduledRun(settings.cron(), settings.zone(), now);
    }

    /**
     * The window an operator named, read in the order data-model.md states.
     *
     * <p>The first form that parses wins and nothing falls through to a default once a value was
     * given. A zero or negative duration, a shorthand with no digits and an instant that has not
     * happened yet are each refused - the last of them by {@link ReportWindow} itself, which is
     * where a window of no width is refused for every caller: one that reports nothing looks
     * exactly like a quiet night, which is the one reading an operator must never be given by
     * accident.
     *
     * @param typed what followed {@code --since}
     * @param now   the window's end, which is always this pod's reading of now
     * @return the window
     * @throws IllegalArgumentException where the value is not a window this command can use
     */
    private static ReportWindow sinceTyped(final String typed, final Instant now) {
        return new ReportWindow(readBack(typed, now), now);
    }

    /**
     * An instant, a duration or a shorthand, tried in that order.
     *
     * <p>Nothing an operator typed reaches a message here either. The readers below quote the token
     * they choked on - {@code Text '...' could not be parsed} - and this method's own refusals name
     * the rule rather than the value; {@link CliMain#unreadable} then writes the argument's name
     * and the class of the reader that refused it, and neither the message nor the throwable.
     *
     * @param typed what followed {@code --since}
     * @param now   the window's end
     * @return the window's start
     * @throws IllegalArgumentException where none of the three forms reads
     */
    // PMD.OnlyOneReturn: three forms tried in a stated order, each answered where it parses; one
    // exit would need a sentinel standing in for "not this form", which is the null this avoids.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static Instant readBack(final String typed, final Instant now) {
        final Optional<Instant> instant = asInstant(typed);
        if (instant.isPresent()) {
            return instant.get();
        }
        final Optional<Duration> duration = asDuration(typed);
        if (duration.isPresent()) {
            return now.minus(positive(duration.get()));
        }
        final Matcher shorthand = SHORTHAND.matcher(typed);
        if (!shorthand.matches()) {
            throw new IllegalArgumentException(
                    "a window is an instant, an ISO-8601 duration or <n>d, <n>h, <n>m or <n>s");
        }
        return now.minus(positive(Duration.of(Long.parseLong(shorthand.group(1)),
                UNITS.get(shorthand.group(2)))));
    }

    /**
     * The value as an ISO-8601 instant, where it is one.
     *
     * <p>"Not this form" is answered rather than caught at the call site, which is the difference
     * between a suppressed empty catch and a reader that says what it found. Only the last form
     * tried is a refusal, so the two before it must be able to say "no" without raising anything:
     * a line on an operator's stderr for every window they typed correctly is noise, and a
     * suppressed catch is a place a real failure could later hide.
     *
     * @param typed what followed {@code --since}
     * @return the instant, or empty where this is not one
     */
    private static Optional<Instant> asInstant(final String typed) {
        Optional<Instant> read;
        try {
            read = Optional.of(Instant.parse(typed));
        } catch (DateTimeParseException notAnInstant) {
            read = Optional.empty();
        }
        return read;
    }

    /**
     * The value as an ISO-8601 duration, where it is one.
     *
     * <p>Answered rather than thrown, for the reason {@link #asInstant} gives. Whether the duration
     * is one a window can be measured back by is {@link #positive}'s question and not this one's:
     * {@code PT0S} parses and is refused, and the two refusals are different sentences.
     *
     * @param typed what followed {@code --since}
     * @return the duration, or empty where this is not one
     */
    private static Optional<Duration> asDuration(final String typed) {
        Optional<Duration> read;
        try {
            read = Optional.of(Duration.parse(typed));
        } catch (DateTimeParseException notADuration) {
            read = Optional.empty();
        }
        return read;
    }

    /**
     * The duration, where it is one a window can be measured back by.
     *
     * @param read what was parsed
     * @return the same duration
     * @throws IllegalArgumentException where it is zero or negative
     */
    private static Duration positive(final Duration read) {
        if (read.isZero() || read.isNegative()) {
            throw new IllegalArgumentException(
                    "a window has to have width, and a negative one has not happened yet");
        }
        return read;
    }

    /**
     * Declines {@code --email}, having read and written nothing.
     *
     * <p>The two declines name two different things, because they have two different answers: an
     * output that is switched off is a deployment change, and an output that is on with no sink
     * behind it is a context to fix. A reason that named the setting in both cases would send half
     * the people who saw it to a setting that is already right.
     *
     * @param reason the bounded reason it was declined under, and what that reason names
     * @return {@link CliMain#REFUSED}
     */
    private int declineEmail(final String reason) {
        return CliMain.declined(CliMain.REPORT_EXCEPTIONS, USAGE, reason, output);
    }

    /**
     * The sink of one name this context holds, where it holds one.
     *
     * @param name which sink
     * @return it, or empty where this deployment has none
     */
    private Optional<ExceptionReportSink> sinkNamed(final ReportSinkName name) {
        return sinks.stream().filter(sink -> sink.name() == name).findFirst();
    }

    /**
     * The table: one bounded line per exception, oldest first, then the counts and the window.
     *
     * <p>The keys are the event field names, spelled exactly as {@code courtregister_exception}
     * spells them, so an operator reading the table and a saved query reading the index are naming
     * the same things - a key that differs between the two is a key somebody greps for and does not
     * find. A field the kind does not carry is absent rather than empty, exactly as the event omits
     * it.
     *
     * @param report what the reads found
     */
    private void table(final ExceptionReport report) {
        if (report.entries().isEmpty()) {
            output.accept(NOTHING_WRONG);
        } else {
            report.entries().forEach(entry -> output.accept(lineFor(entry)));
        }
        final Map<ExceptionKind, Integer> counts = report.counts();
        final StringBuilder line = new StringBuilder(A_COUNTS_LINE).append("counts");
        for (final ExceptionKind kind : ExceptionKind.values()) {
            line.append(' ').append(kind.name().toLowerCase(Locale.ROOT))
                    .append('=').append(counts.get(kind));
        }
        output.accept(line
                .append(" window_from=").append(report.window().from())
                .append(" window_to=").append(report.window().to())
                .toString());
    }

    /**
     * One exception, as the thirteen fields its kind carries.
     *
     * @param entry one thing wrong
     * @return the line an operator reads
     */
    private static String lineFor(final ExceptionEntry entry) {
        final StringBuilder line = new StringBuilder("kind=").append(entry.kind());
        carried(line, "source", entry.source());
        carried(line, "request_id", entry.requestId());
        carried(line, "hearing_id", entry.hearingId());
        carried(line, "hearing_day", entry.hearingDay());
        carried(line, "batch_id", entry.batchId());
        carried(line, "notification_id", entry.notificationId());
        carried(line, "court_centre_id", entry.courtCentreId());
        carried(line, "register_date", entry.registerDate());
        carried(line, "status", entry.status());
        carried(line, "attempts", entry.attempts());
        carried(line, "reason", entry.reason());
        return line.append(" age_seconds=").append(entry.ageSeconds()).toString();
    }

    /**
     * Adds one field, or adds nothing where the kind does not carry it.
     *
     * @param line    the line being assembled
     * @param name    the field's name, as the event spells it
     * @param carried its value, or {@code null}
     */
    private static void carried(final StringBuilder line, final String name, final Object carried) {
        if (carried != null) {
            line.append(' ').append(name).append('=').append(carried);
        }
    }

    /**
     * The run's own last line, written after every sink has returned.
     *
     * <p>The equivalent of {@code ExceptionReportJob}'s {@code exception_report_run}, on the
     * operator's stream rather than in the log, so an on-demand report says the same things about
     * its delivery that the 07:00 run does - in the same words, which are
     * {@link DeliveryWord}'s and {@link ReportRunOutcome}'s rather than a second copy of each.
     *
     * <p>{@code duration_ms} is measured on the injected clock, between this invocation opening
     * its correlation and this line, exactly as the run measures its own. A reading taken off a
     * second, un-injected clock is a field no case can state a value for, and a field no case
     * states a value for is a field that can quietly stop being a duration at all.
     *
     * @param window     the window that was read
     * @param report     what the reads found, for its count and for what the cap dropped
     * @param delivered  one outcome per sink asked, in the order they were asked
     * @param emailAsked whether {@code --email} was given and accepted
     * @param startedAt  when the invocation opened its correlation
     */
    private void runLine(final ReportWindow window, final ExceptionReport report,
            final List<DeliveryOutcome> delivered, final boolean emailAsked,
            final Instant startedAt) {

        output.accept("event=" + RUN_EVENT
                + " run_id=" + RunCorrelation.current()
                + " window_from=" + window.from()
                + " window_to=" + window.to()
                + " entries=" + report.entries().size()
                + " truncated=" + report.truncated()
                + " delivered_log=" + said(ReportSinkName.LOG, delivered, true)
                + " delivered_email=" + said(ReportSinkName.EMAIL, delivered, emailAsked)
                + " outcome=" + ReportRunOutcome.from(delivered).name().toLowerCase(Locale.ROOT)
                + " duration_ms=" + Duration.between(startedAt, clock.instant()).toMillis());
    }

    /**
     * What the line says about one sink, in the words the 07:00 run uses for the same facts.
     *
     * <p>Whether the sink is here at all is read from the sinks this context contributed, and not
     * from the settings: the job reads it that way, and the two read it the same way or they can
     * call the same context two different things. {@code skipped} is this command's alone - an
     * output that exists and an invocation that did not want it - and it is the word that has to
     * stay distinguishable from {@code disabled}, which says nobody could.
     *
     * @param sink      which sink the field is about
     * @param delivered one outcome per sink asked
     * @param asked     whether this invocation asked it
     * @return the bounded word
     */
    private String said(final ReportSinkName sink, final List<DeliveryOutcome> delivered,
            final boolean asked) {

        final boolean here = sinkNamed(sink).isPresent();
        return DeliveryWord.said(sink, delivered, here, here && asked);
    }

    /**
     * Whether every sink this invocation asked took the report.
     *
     * <p>Exit 2 where one did not, which is the same fact {@code outcome=partial} states on the
     * last line, so a shell learns it without reading a line. Nothing is retried: a report is
     * regenerated in full by the next run or by asking again, so a retry would re-send a list
     * support is about to receive anyway.
     *
     * @param delivered one outcome per sink asked
     * @return true where every one of them delivered
     */
    private static boolean everySinkTookIt(final List<DeliveryOutcome> delivered) {
        return !delivered.isEmpty()
                && delivered.stream().allMatch(o -> o.status() == DeliveryStatus.DELIVERED);
    }
}
