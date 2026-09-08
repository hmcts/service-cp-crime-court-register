package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The one place in this service that writes to a file descriptor: a command's report, and nothing
 * else.
 *
 * <p><strong>What a command prints is protocol, not diagnostics.</strong> Constitution Principle VI
 * forbids the two static process streams and {@code printStackTrace()} because a diagnostic written
 * to a stream nobody collects is a silent failure, and every diagnostic in this service goes through
 * SLF4J instead - in a command's JVM, out to stderr in the estate's own JSON format
 * ({@code logback-cli.xml}). A command's report is not one of those. It is the answer the runbook
 * step that ran the command reads: the line a step greps for, the listing {@code diff} compares
 * between two runs of {@code list-batches}, the line an operator pastes into a ticket. Sent through
 * SLF4J it would arrive wrapped in a JSON envelope on the log stream, timestamped and interleaved
 * with a context's INFO lines, where no operator can read it and no grep can find it. So the answer
 * to the rule is a boundary rather than a redirection: the descriptor is named here, once, and
 * everything upstream of it - {@link CliMain} and the five commands - is handed a
 * {@link Consumer Consumer&lt;String&gt;} and cannot reach a stream at all.
 *
 * <p><strong>This is the only such place, and that is the point.</strong> No other class in the
 * service, and no test, names a process stream: what an operator's terminal carries is decided in
 * one file, and a test reads the same lines by handing a different consumer in. The other
 * descriptor - the log's - belongs to Logback, which this service configures rather than writes to.
 *
 * <p><strong>Four properties, each of them deliberate.</strong> The lines are UTF-8, because a
 * command's JVM encodes its console in whatever locale the pod has and a container with none
 * encodes in ASCII, which would print {@code ?} where a court house's name or a masked recipient
 * carries a character outside it. Each line ends in one {@code \n} rather than the platform's
 * separator, so a listing is the same protocol wherever the JVM ran and two runs of it can be
 * compared. Every line is flushed as it is written, so a step that reads the report line by line is
 * never waiting on a buffer and a command that is killed mid-run has printed exactly what it said
 * it printed. And nothing here is closed: the descriptor was opened by the process rather than by
 * this class, closing it would take standard output away from the JVM it was borrowed from, and
 * flushing per line leaves nothing behind to close it for.
 *
 * <p><strong>A line that cannot be written is not written past.</strong> The report is the whole of
 * what the command answered, so a failed write is wrapped and rethrown rather than counted and
 * carried on from: a truncated listing that a step read as a complete one is exactly the quiet
 * failure Principle VI is about, and the static stream this class replaces would have swallowed it
 * into an error flag nobody reads.
 */
public final class StandardOutput implements Consumer<String> {

    /** What every report line ends in, fixed rather than taken from the platform. */
    private static final char END_OF_LINE = '\n';

    /** Where the lines go, encoded once and flushed a line at a time. */
    private final Writer lines;

    /**
     * Wraps one stream, which in this service is only ever the process's own.
     *
     * <p>Package-visible so that {@code StandardOutputTest} can read what this boundary writes
     * without a descriptor to capture: the real one is exercised by {@code e2e/CliDispatchIT},
     * which asks the built image for a command and reads the container's stdout.
     *
     * @param stream where the encoded lines are written, owned by whoever opened it
     */
    /* default */ StandardOutput(final OutputStream stream) {
        this.lines = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
    }

    /**
     * The process's standard output, as the commands' one destination.
     *
     * @return a consumer that writes each line it is given to file descriptor 1
     */
    // PMD.AvoidFileStream: there is no other public way to reach the descriptor the process was
    // started with, and Files.newOutputStream opens a path rather than borrowing a stream. Nothing
    // is opened here in the sense the rule is about - the descriptor is already open, this only
    // gives it a Java type - and nothing is closed for the same reason.
    @SuppressWarnings("PMD.AvoidFileStream")
    public static StandardOutput ofProcess() {
        return new StandardOutput(new FileOutputStream(FileDescriptor.out));
    }

    /**
     * Writes one report line, and does not return until it has been written.
     *
     * @param line one line of a command's report, already bounded and PII-free by the command that
     *             wrote it (constitution Principle VII)
     * @throws UncheckedIOException where the line could not be written, so that a report a step
     *                              read as complete cannot have been cut short in silence
     */
    @Override
    public void accept(final String line) {
        try {
            lines.write(line);
            lines.write(END_OF_LINE);
            lines.flush();
        } catch (IOException notWritten) {
            throw new UncheckedIOException(
                    "a command's report line could not be written to standard output", notWritten);
        }
    }
}
