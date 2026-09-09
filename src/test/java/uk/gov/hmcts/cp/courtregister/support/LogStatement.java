package uk.gov.hmcts.cp.courtregister.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One line a class can write, read out of the class's own source.
 *
 * <p>For the claim a privacy suite makes about a whole leg. "No recipient's address at any level"
 * is a statement about <em>every</em> line the leg can write, and a suite that drove a list of
 * cases would be making it about the lines somebody remembered - a statement added next month is
 * outside such a claim from the moment it is written, silently, with the suite still green. So the
 * statements are enumerated here out of the sources instead, and the suite insists its drive
 * reached each of them: a new line is then a failing test until it has a case, which is the only
 * way round that fails loudly.
 *
 * <p>What identifies a statement is its <strong>message pattern</strong> - {@code
 * ILoggingEvent.getMessage()}, the format string before any argument is substituted - beside the
 * logger that wrote it. Caller data would be the obvious key and is the wrong one: logback resolves
 * it lazily by taking a stack trace when it is first asked, so an event whose caller data is first
 * read on the asserting thread reports the <em>asserting</em> thread's stack. That is the same trap
 * {@link CapturedLog} documents for the MDC, and the pattern is not subject to it.
 *
 * <p><strong>Which is why the key has to be unique, and a sweep has to say so.</strong> Two
 * statements in one class that spell the same pattern have the same key, and the event from either
 * satisfies both declarations - so a line added beside one that already writes that wording would
 * never have to be reached, and the sweep that exists to catch exactly that would pass anyway.
 * {@link #keyCollisionsIn(List)} is what a sweep refuses on, asserted before it asserts anything
 * about what a drive reached. Keying on the caller instead would close the same hole and cost more
 * than it is worth twice over: the stack it would read is the asserting thread's, per the paragraph
 * above, and a key carrying a line number would make a statement that only moved line fail its own
 * declaration.
 *
 * <p>The scan is deliberately literal about this repository's style rather than a parser: every
 * logging statement in it begins its own line with {@code LOG.}, and the pattern is the leading
 * string literal, concatenated across as many lines as it is written over. A statement written any
 * other way is not matched, which is why a suite using this asserts a floor on the count - a scan
 * that had quietly stopped matching anything would fall below it rather than pass.
 *
 * @param loggerName the logger that writes it, which is the declaring class
 * @param pattern    the message pattern, exactly as the source spells it
 * @param where      the source file and line, for the assertion message that names what was missed
 */
public record LogStatement(String loggerName, String pattern, String where) {

    /** Where a class's source lives, from its binary name. */
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /** The characters the pattern is read by, named so an {@code if} carries no literal. */
    private static final char QUOTE = '"';

    private static final char CONCATENATION = '+';

    private static final char ESCAPE = '\\';

    private static final char NEWLINE_ESCAPE = 'n';

    private static final char TAB_ESCAPE = 't';

    /** The five calls this repository writes a line with. */
    private static final List<String> CALLS =
            List.of("LOG.error(", "LOG.warn(", "LOG.info(", "LOG.debug(", "LOG.trace(");

    /**
     * What the suite matches a captured event against: the logger and the pattern it wrote.
     *
     * @return the key this statement is recognised by
     */
    public String key() {
        return loggerName + "|" + pattern;
    }

    /**
     * The statements no sweep over these can tell apart, by the locations that share a key.
     *
     * <p>Asserted empty before anything is asserted about what a drive reached, because two
     * statements on one key make the reach assertion vacuous for the second of them: the event
     * from whichever the drive did reach satisfies both, and the other sits inside no claim at all
     * with the suite still green. The fix is on the source rather than here - give one of the two
     * its own wording - which is the same answer as for a line the drive never reached.
     *
     * @param statements the enumerated declarations, as {@link #everyOneIn(List)} answers them
     * @return one entry per shared key, naming every source location that spells it
     */
    public static List<String> keyCollisionsIn(final List<LogStatement> statements) {
        final Map<String, List<String>> locations = statements.stream()
                .collect(Collectors.groupingBy(LogStatement::key, LinkedHashMap::new,
                        Collectors.mapping(LogStatement::where, Collectors.toList())));
        return locations.values().stream()
                .filter(shared -> shared.size() > 1)
                .map(shared -> String.join(" and ", shared))
                .toList();
    }

    /**
     * Every line the given classes can write, in source order.
     *
     * @param types the classes to read; each must have its source under {@code src/main/java}
     * @return one entry per logging statement found
     * @throws IOException if one of the sources cannot be read
     */
    public static List<LogStatement> everyOneIn(final List<Class<?>> types) throws IOException {
        final List<LogStatement> statements = new ArrayList<>();
        for (final Class<?> type : types) {
            statements.addAll(everyOneIn(type));
        }
        return statements;
    }

    private static List<LogStatement> everyOneIn(final Class<?> type) throws IOException {
        final Path source = SOURCE_ROOT.resolve(type.getName().replace('.', '/') + ".java");
        final List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        final List<LogStatement> statements = new ArrayList<>();

        for (int line = 0; line < lines.size(); line++) {
            final String text = lines.get(line);
            if (CALLS.stream().anyMatch(text.strip()::startsWith)) {
                statements.add(new LogStatement(
                        type.getName(),
                        patternFrom(lines, line, text.indexOf('(', text.indexOf("LOG.")) + 1),
                        type.getSimpleName() + ".java:" + (line + 1)));
            }
        }
        return statements;
    }

    /**
     * The leading string literal of one statement, concatenated over as many lines as it takes.
     *
     * <p>Reading stops at the first character of the argument list that is not whitespace, not a
     * {@code +} joining two literals and not a quote opening the next one - which is the comma
     * before the first argument, or the closing bracket of a statement that has none.
     *
     * @param lines the whole source
     * @param first the line the statement opens on
     * @param from  the column just after the opening bracket
     * @return the pattern, unescaped as the compiler would unescape it
     */
    private static String patternFrom(final List<String> lines, final int first, final int from) {
        final String remaining = lines.get(first).substring(from) + "\n"
                + String.join("\n", lines.subList(first + 1, lines.size()));
        final StringBuilder pattern = new StringBuilder();
        int column = 0;
        boolean reading = true;

        while (reading && column < remaining.length()) {
            final char character = remaining.charAt(column);
            if (character == QUOTE) {
                column = literalFrom(remaining, column + 1, pattern);
            } else if (Character.isWhitespace(character) || character == CONCATENATION) {
                column++;
            } else {
                reading = false;
            }
        }
        return pattern.toString();
    }

    /**
     * One string literal's contents, appended to the pattern being built.
     *
     * @param text    the source from the opening bracket on
     * @param from    the column just after the opening quote
     * @param pattern where the contents are appended
     * @return the column just after the closing quote
     */
    private static int literalFrom(final String text, final int from,
            final StringBuilder pattern) {

        int column = from;
        while (column < text.length() && text.charAt(column) != QUOTE) {
            if (text.charAt(column) == ESCAPE && column + 1 < text.length()) {
                pattern.append(unescaped(text.charAt(column + 1)));
                column += 2;
            } else {
                pattern.append(text.charAt(column));
                column++;
            }
        }
        return column + 1;
    }

    /** The one character an escape sequence stands for, for the three this repository writes. */
    private static char unescaped(final char escaped) {
        final char character;
        if (escaped == NEWLINE_ESCAPE) {
            character = '\n';
        } else if (escaped == TAB_ESCAPE) {
            character = '\t';
        } else {
            character = escaped;
        }
        return character;
    }
}
