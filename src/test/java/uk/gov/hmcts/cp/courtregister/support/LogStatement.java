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

    /** The braces a block is counted by, named so an {@code if} carries no literal. */
    private static final char BLOCK_OPENS = '{';

    private static final char BLOCK_CLOSES = '}';

    /** The quote a character literal is written in, named so an {@code if} carries no literal. */
    private static final char CHARACTER_QUOTE = '\'';

    /** How many characters a text block's delimiter takes. */
    private static final int TEXT_BLOCK_DELIMITER = 3;

    private static final char TAB_ESCAPE = 't';

    /**
     * The levels this sweep reads, which is every level Principle VII governs.
     *
     * <p>INFO is in the list and was not: the principle is written about INFO and above, so a
     * statement that attached at INFO passed a sweep reading only the two above it. DEBUG and
     * TRACE are deliberately out, being the levels the principle allows a cause to be written at
     * and the levels two statements deliberately use for exactly that.
     */
    private static final List<String> ATTACHABLE_LEVELS =
            List.of("LOG.error(", "LOG.warn(", "LOG.info(");

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
     * Every WARN or ERROR in the production sources that attaches an exception whose words this
     * service did not write, as {@code file:line catches <the types caught>}.
     *
     * <p><strong>The claim this makes is by construction, and it is the only kind worth making
     * about a whole class of defect.</strong> Five times a statement wrote a bounded reason and
     * then attached the throwable as well, and the message an attached throwable renders belongs
     * to whoever raised it - a driver, a pool, an HTTP client, the broker - so it carries whatever
     * that library chose to put in it, which on a store or transport failure is where a connection
     * string or a fragment of a statement turns up. A suite that pinned each one as it was found
     * would go on finding them; a sweep that refuses the shape cannot be added to without failing.
     *
     * <p><strong>Nothing may be attached, and the flat rule is the only one that holds.</strong>
     * Two softer rules were tried and both were defeated inside one review. A hand-kept list of
     * this service's own exception types let three wrappers through, because a cause chain renders
     * recursively. Deriving the answer from the type - attachable where no constructor takes a
     * {@link Throwable} - lasted no longer: every exception inherits {@code initCause}, so a type
     * with no such constructor can still be given a cause after it is built, a subclass can
     * declare one, and nothing about a constructor's signature says the message it composes is
     * bounded. What is left is the rule that needs no exceptions: a line carries the class of what
     * was caught and never the throwable.
     *
     * <p>The enclosing {@code catch} is resolved <strong>lexically</strong>, by the brace structure
     * around the statement, rather than by matching the argument's name against the catches in the
     * file: a name reused by a later, safer catch would otherwise excuse an earlier attachment.
     * Every type of a multi-catch must be attachable, because the one that is not is the one that
     * renders.
     *
     * @return one entry per offending statement, empty where the sweep's claim holds
     * @throws IOException if a source cannot be read
     */
    public static List<String> exceptionsAttachedOutsideOwnWording() throws IOException {
        final List<String> attached = new ArrayList<>();
        try (java.util.stream.Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            for (final Path source : sources.filter(each -> each.toString().endsWith(".java"))
                    .sorted().toList()) {
                attached.addAll(attachmentsIn(
                        Files.readString(source, StandardCharsets.UTF_8),
                        source.getFileName().toString()));
            }
        }
        return attached;
    }

    /**
     * The same sweep over one source, so the scan itself can be put in front of a case.
     *
     * <p>A scan is a claim about every line in the repository, and a claim that large is worth
     * nothing if the scan quietly stops matching: this is how a suite hands it source it wrote and
     * asserts what it finds, including the two shapes that defeated the first version of it.
     *
     * @param source   the source text
     * @param fileName what to call it in an entry
     * @return one entry per offending statement in it
     */
    public static List<String> attachmentsIn(final String source, final String fileName) {
        final boolean[] codeAt = codePositionsIn(source);
        final List<CatchBlock> catches = catchBlocksIn(codeAt, source);
        final List<String> attached = new ArrayList<>();
        for (final String call : ATTACHABLE_LEVELS) {
            int at = source.indexOf(call);
            while (at >= 0) {
                if (!codeAt[at]) {
                    at = source.indexOf(call, at + 1);
                    continue;
                }
                final String last = lastArgumentOf(source, at + call.length());
                final CatchBlock enclosing = innermostAround(catches, at);
                if (enclosing != null && last.equals(nameOf(source, enclosing))) {
                    attached.add(fileName + ":" + lineOf(source, at)
                            + " catches " + String.join(" | ", typesOf(source, enclosing)));
                }
                at = source.indexOf(call, at + 1);
            }
        }
        return attached;
    }

    /** Every {@code catch} block in a source, outermost first. */
    private static List<CatchBlock> catchBlocksIn(final boolean[] codeAt, final String source) {
        final List<CatchBlock> blocks = new ArrayList<>();
        final java.util.regex.Matcher clauses = java.util.regex.Pattern
                .compile("catch\\s*\\(([^)]*)\\)\\s*\\{").matcher(source);
        while (clauses.find()) {
            if (!codeAt[clauses.start()]) {
                continue;
            }
            int depth = 0;
            int at = clauses.end() - 1;
            while (at < source.length()) {
                if (codeAt[at] && source.charAt(at) == BLOCK_OPENS) {
                    depth++;
                } else if (codeAt[at] && source.charAt(at) == BLOCK_CLOSES) {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                at++;
            }
            blocks.add(new CatchBlock(clauses.start(), at, clauses.end()));
        }
        return blocks;
    }

    /**
     * Which positions of a source are code, so a brace inside a string or a comment counts for
     * nothing.
     *
     * <p>The reason this is not an optimisation. A block matcher that counted every brace could be
     * closed early by one written in a comment or a literal - {@code // }} on the line above a
     * warning ends the enclosing catch as far as the matcher can see, and the statement below it
     * then belongs to no catch and is swept up by nothing. A defeating case is one line long and
     * cost the whole claim, which is what this exists to stop and what
     * {@code LogStatementSweepTest} pins.
     *
     * <p>Text blocks are treated as ordinary strings: their delimiter is three quotes, so the
     * toggle opens on the first and closes on the third, and the two in between leave the state
     * where it started. A brace inside one is still inside a string either way.
     *
     * @param source the source text
     * @return one flag per character, true where that character is code
     */
    private static boolean[] codePositionsIn(final String source) {
        final boolean[] codeAt = new boolean[source.length()];
        boolean inString = false;
        boolean inTextBlock = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean escaped = false;
        int at = 0;
        while (at < source.length()) {
            final char character = source.charAt(at);
            final char next = at + 1 < source.length() ? source.charAt(at + 1) : '\0';
            int step = 1;
            if (inLineComment) {
                inLineComment = character != '\n';
            } else if (inBlockComment) {
                if (character == '*' && next == '/') {
                    inBlockComment = false;
                    step = 2;
                }
            } else if (escaped) {
                escaped = false;
            } else if ((inString || inChar) && character == ESCAPE) {
                escaped = true;
            } else if (inTextBlock) {
                if (character == QUOTE && isTripleQuoteAt(source, at)) {
                    inTextBlock = false;
                    step = TEXT_BLOCK_DELIMITER;
                }
            } else if (inString) {
                inString = character != QUOTE;
            } else if (inChar) {
                inChar = character != CHARACTER_QUOTE;
            } else if (character == '/' && next == '/') {
                inLineComment = true;
                step = 2;
            } else if (character == '/' && next == '*') {
                inBlockComment = true;
                step = 2;
            } else if (character == QUOTE && isTripleQuoteAt(source, at)) {
                inTextBlock = true;
                step = TEXT_BLOCK_DELIMITER;
            } else if (character == QUOTE) {
                inString = true;
            } else if (character == CHARACTER_QUOTE) {
                inChar = true;
            } else {
                codeAt[at] = true;
            }
            at += step;
        }
        return codeAt;
    }

    /**
     * Whether a text block's delimiter starts here.
     *
     * <p>A text block is the shape that defeated the first version of this lexer: its content is
     * ordinary source to a scanner that only knows single quotes, so a JSON fragment inside one -
     * {@code "end": "}"} is the case that found it - closed the string on its first quote and let
     * every brace after it count. Reading the delimiter as one token puts the whole block in a
     * state that ends only at the matching three.
     *
     * @param source the source text
     * @param at     the index of the first quote
     * @return true where three quotes start at that index
     */
    private static boolean isTripleQuoteAt(final String source, final int at) {
        return source.startsWith("\"\"\"", at);
    }

    private static CatchBlock innermostAround(final List<CatchBlock> blocks, final int at) {
        return blocks.stream()
                .filter(block -> block.from() < at && at < block.to())
                .reduce((outer, inner) -> inner)
                .orElse(null);
    }

    /**
     * One {@code catch} block's extent: where the clause starts, where the body ends, and where
     * the clause itself ends so the declaration can be read back off it.
     *
     * @param from       the index of the {@code catch} keyword
     * @param to         the index of the closing brace of its body
     * @param clauseEnds the index just past the opening brace of its body
     */
    private record CatchBlock(int from, int to, int clauseEnds) {
    }

    /** The name a catch binds, read off its own clause. */
    private static String nameOf(final String source, final CatchBlock block) {
        final String clause = source.substring(block.from(), block.clauseEnds());
        final String inside = clause.substring(clause.indexOf('(') + 1, clause.lastIndexOf(')'));
        return inside.strip().substring(inside.strip().lastIndexOf(' ') + 1);
    }

    /** Every type a catch declares, a multi-catch giving more than one. */
    private static List<String> typesOf(final String source, final CatchBlock block) {
        final String clause = source.substring(block.from(), block.clauseEnds());
        final String inside = clause.substring(clause.indexOf('(') + 1, clause.lastIndexOf(')'))
                .replace("final ", "").strip();
        final String declared = inside.substring(0, inside.lastIndexOf(' ')).strip();
        return java.util.Arrays.stream(declared.split("\\|")).map(String::strip).toList();
    }

    private static int lineOf(final String source, final int at) {
        return source.substring(0, at).split("\\n", -1).length;
    }

    /**
     * The last argument of one call, read by balancing brackets from its opening one.
     *
     * @param text from the source
     * @param from the index just past the opening bracket
     * @return the final argument as written, or an empty string where the call has none
     */
    private static String lastArgumentOf(final String text, final int from) {
        int depth = 1;
        int at = from;
        boolean inString = false;
        boolean escaped = false;
        final List<StringBuilder> arguments = new ArrayList<>(List.of(new StringBuilder()));
        while (depth > 0 && at < text.length()) {
            final char character = text.charAt(at);
            at++;
            if (escaped) {
                escaped = false;
                arguments.getLast().append(character);
                continue;
            }
            if (character == ESCAPE) {
                escaped = true;
                arguments.getLast().append(character);
                continue;
            }
            if (character == QUOTE) {
                inString = !inString;
            }
            if (!inString) {
                if (character == '(' || character == '<') {
                    depth++;
                } else if (character == ')' || character == '>') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                } else if (character == ',' && depth == 1) {
                    arguments.add(new StringBuilder());
                    continue;
                }
            }
            arguments.getLast().append(character);
        }
        return arguments.getLast().toString().strip();
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
