package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * A command's report line the destination would not take, as this service's own answer.
 *
 * <p><strong>Why a type of the CLI's own.</strong> {@code startup.sh list-batches | head -1} is an
 * everyday invocation, and the descriptor a report is written to stops taking lines the moment
 * {@code head} has had its one. Everything upstream of {@link StandardOutput} catches broadly on
 * purpose - a context that will not start throws whatever the bean that refused threw, and a store
 * translates an outage into an unchecked type of its own - so the only way a broken pipe is told
 * apart from those is by being a type nothing else in this service throws. Wrapped in a plain
 * {@link UncheckedIOException} it was indistinguishable from any other IO failure the context or a
 * command could raise, and {@link CliMain#dispatch} reported it as a context that never started:
 * a false diagnostic, written to the destination that had just refused a line, and answered on the
 * exit code that means the command declined.
 *
 * <p><strong>Still an {@link UncheckedIOException}, though.</strong> This narrows the boundary's
 * contract rather than replacing it: what happened is an {@link IOException} on a write, the cause
 * carries it, and a caller that only cares that the report failed can read it as the IO failure it
 * is. What the subtype adds is the one thing a {@code catch} needs to know - that it was the
 * report's own destination and not the work the command was asked to do.
 *
 * <p><strong>The line itself is never in the message.</strong> A report is bounded and PII-free by
 * the command that wrote it (constitution Principle VII), but an exception's words travel further
 * than a terminal does, so what is said here is that a line could not be written and not which one.
 */
public final class ReportNotWritten extends UncheckedIOException {

    private static final long serialVersionUID = 1L;

    /**
     * Wraps the refusal the destination answered a write with.
     *
     * @param message     what could not be written, in this service's own words and never the line
     * @param notWritten  what the destination refused with
     */
    public ReportNotWritten(final String message, final IOException notWritten) {
        super(message, notWritten);
    }
}
