package uk.gov.hmcts.cp.courtregister.batch.cli;

import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The one boundary a command's report leaves this service through: what it writes, and what it does
 * not.
 *
 * <p><strong>[A] characterisation.</strong> {@link StandardOutput} was extracted from the
 * destination {@code CliMain.main} used to reach for, and this suite states what the extracted
 * boundary does rather than driving it: it passed on introduction and no implementation commit
 * follows it. What it exists for is that the four properties of the boundary are the whole
 * difference between a report a runbook step can read and one it cannot, and none of them is
 * observable through the injected consumer every other suite in this package uses.
 *
 * <p><strong>The descriptor itself is not read here, and cannot be.</strong> A test that captured
 * file descriptor 1 would have to take the process's own stream over, which is the thing
 * constitution Principle VI forbids and this class was written to make unnecessary. So the stream
 * is handed to the boundary instead, and the proof that the real descriptor still carries the lines
 * is {@code e2e/CliDispatchIT}, which asks the built image for {@code check-flag} and asserts the
 * container's stdout is exactly {@code flag=ON}.
 *
 * <p><strong>Each case is one of the properties, and each has a failure mode a runbook feels.</strong>
 * A line ends in one newline and carries nothing else, so a step's {@code grep} matches the line the
 * command meant. It is flushed as it is written, so a step reading line by line is not waiting on
 * an encoder's buffer and a command killed mid-run has printed what it said it printed. It is UTF-8
 * because the encoding is named here rather than inherited from the pod's locale, which in a
 * container that has none is ASCII and would print {@code ?} through a masked recipient or a court
 * house's name. Nothing is closed, because the descriptor belongs to the process. And a line that
 * could not be written is wrapped and rethrown, because a listing cut short in silence would be
 * read as a complete one.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the boundary that owns the command's own stream")
class StandardOutputTest {

    /** A report line, as {@code check-flag} writes it. */
    private static final String FLAG_IS_ON = "flag=ON";

    /** What the lines are written to, in place of the descriptor the process owns. */
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();

    private final StandardOutput output = new StandardOutput(written);

    @InjectSoftAssertions
    private SoftAssertions softly;

    @Test
    void every_line_should_be_written_with_one_newline_after_it_and_nothing_else() {
        output.accept(FLAG_IS_ON);
        output.accept("date=2026-09-07 released=0");

        softly.assertThat(written.toString(StandardCharsets.UTF_8))
                .as("line-oriented and stable: two lines, each ended once, so what a step greps "
                        + "for is the line the command wrote and `diff` between two runs of a "
                        + "listing compares reports rather than line endings")
                .isEqualTo("flag=ON\ndate=2026-09-07 released=0\n");
    }

    @Test
    void a_line_should_be_flushed_as_it_is_written_rather_than_at_the_end() {
        output.accept(FLAG_IS_ON);

        softly.assertThat(written.toString(StandardCharsets.UTF_8))
                .as("nothing is left in an encoder's buffer: a step that reads the report line by "
                        + "line is not waiting on the next one, and a command killed mid-run has "
                        + "printed exactly what it said it printed")
                .isEqualTo("flag=ON\n");
    }

    @Test
    void a_line_should_be_encoded_as_utf_8_rather_than_in_whatever_the_pod_reads_as_its_locale() {
        output.accept("court=Cynulliad Sir Fôn");

        softly.assertThat(written.toByteArray())
                .as("the encoding is named here rather than inherited, because a container with "
                        + "no locale set encodes its console in ASCII and would put a `?` through "
                        + "a court house's name and through a masked recipient")
                .isEqualTo("court=Cynulliad Sir Fôn\n".getBytes(StandardCharsets.UTF_8));
    }

    // PMD.CloseResource: the stream standing in for the process's own is deliberately left open,
    // because that it is never closed is the whole of what this case asserts.
    @SuppressWarnings("PMD.CloseResource")
    @Test
    void the_stream_should_never_be_closed_by_the_boundary_that_did_not_open_it() {
        final CountsCloses process = new CountsCloses();

        new StandardOutput(process).accept(FLAG_IS_ON);

        softly.assertThat(process.closed)
                .as("the descriptor was opened by the process, and closing it would take standard "
                        + "output away from the JVM it was borrowed from")
                .isZero();
        softly.assertThat(process.toString(StandardCharsets.UTF_8))
                .as("and the line was written all the same, which is what flushing per line is "
                        + "instead of a close")
                .isEqualTo("flag=ON\n");
    }

    @Test
    void a_line_that_could_not_be_written_should_be_wrapped_rather_than_swallowed() {
        final StandardOutput broken = new StandardOutput(new BrokenPipe());

        final Throwable notWritten = catchThrowable(() -> broken.accept(FLAG_IS_ON));

        softly.assertThat(notWritten)
                .as("a report is the whole of what the command answered, so a listing cut short "
                        + "in silence would be read as a complete one - the static stream this "
                        + "boundary replaces set an error flag nobody reads")
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class)
                // And the line itself is not in the message: a command's report is bounded, but an
                // exception's words travel further than a terminal does.
                .hasMessageNotContaining(FLAG_IS_ON);
    }

    /**
     * A stream that keeps its lines and counts how often it was closed.
     */
    private static final class CountsCloses extends ByteArrayOutputStream {

        private int closed;

        @Override
        public void close() throws IOException {
            closed++;
            super.close();
        }
    }

    /**
     * The stream at the far end of {@code list-batches | head -1}, which stops reading.
     */
    private static final class BrokenPipe extends OutputStream {

        @Override
        public void write(final int one) throws IOException {
            throw new IOException("Broken pipe");
        }

        @Override
        public void write(final byte[] some, final int from, final int length) throws IOException {
            throw new IOException("Broken pipe");
        }
    }
}
