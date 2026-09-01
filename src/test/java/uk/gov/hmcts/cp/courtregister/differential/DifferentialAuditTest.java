package uk.gov.hmcts.cp.courtregister.differential;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.adapter.progression.OutboundContractValidator;
import uk.gov.hmcts.cp.courtregister.application.GroupProceedingsPolicy;
import uk.gov.hmcts.cp.courtregister.application.TransformationResult;
import uk.gov.hmcts.cp.courtregister.config.JacksonConfig;
import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.courtregister.domain.TransformationFailedException;
import uk.gov.hmcts.cp.courtregister.pipeline.Dates;
import uk.gov.hmcts.cp.courtregister.pipeline.RegisterBuilder;
import uk.gov.hmcts.cp.courtregister.pipeline.RegisterTransformationChain;
import uk.gov.hmcts.cp.courtregister.pipeline.SubscriptionMatcher;
import uk.gov.hmcts.cp.courtregister.pipeline.SubscriptionRules;
import uk.gov.hmcts.cp.courtregister.support.DifferentialCorpus;
import uk.gov.hmcts.cp.courtregister.support.DifferentialCorpus.RecordedCase;
import uk.gov.hmcts.cp.courtregister.support.JsonParity;
import uk.gov.hmcts.cp.courtregister.support.RegisteredDefectFixes;
import uk.gov.hmcts.cp.courtregister.support.RegisteredDefectFixes.Claim;
import uk.gov.hmcts.cp.courtregister.support.RegisteredDefectFixes.Divergence;
import uk.gov.hmcts.cp.courtregister.support.RegisteredDefectFixes.PortOutcome;
import uk.gov.hmcts.cp.courtregister.support.RegisteredDefectFixes.PortResult;

/**
 * The differential audit: 381 recorded legacy runs, put through this port, with every difference
 * made to name the {@code doc/DEFECT-FIXES.md} row that allows it.
 *
 * <p><strong>What the audit is for.</strong> Every other suite in this repository asserts what the
 * port <em>should</em> do. This one asserts what the legacy <em>did</em>, and then requires the port
 * to agree with it everywhere no C-number says otherwise. That is the constitution's rule stated as
 * a test: the port encodes fixed behaviour, the legacy is the oracle for everything uncatalogued,
 * and an unattributed difference is a port defect. It fails in both directions — a catalogued defect
 * still reproduced shows up as an unexplained agreement in the pinning suites, and a behaviour
 * change nobody registered shows up here, named by its JSON pointer.
 *
 * <p><strong>The real chain, not a rehearsal of it.</strong> Each case runs the recorded hearing and
 * the recorded reference-data answer through the group-proceedings policy and then through
 * {@link RegisterTransformationChain} built over its real stages — the fragment builder, the
 * subscription matcher, the twelve mappers and the frozen-contract validator. Nothing is stubbed:
 * the chain is pure by construction, so the only things it needs are the two the recorder captured.
 * The subscriptions arrive pre-fetched because that is how the running service supplies them; the
 * legacy activity that reads them is the one part of this flow the audit cannot re-run, and the
 * cases where that read failed are treated accordingly below.
 *
 * <p><strong>Three obligations, decided by the output contract axis.</strong>
 *
 * <ul>
 *   <li><strong>{@code IN_CONTRACT}</strong> — progression accepts the document the legacy produced,
 *       so the port must produce it: every difference must be claimed by
 *       {@link RegisteredDefectFixes}, and an unclaimed one fails naming its path.</li>
 *   <li><strong>{@code SCHEMA_INVALID}</strong> — progression answers 400 and C1 swallows it, so the
 *       register is lost with no trace. The port must classify rather than reproduce: refuse the
 *       document at the contract (C29), or produce a repaired one whose every difference is claimed
 *       by the row that authorises the repair.</li>
 *   <li><strong>{@code NO_DOCUMENT}</strong> — the legacy produced nothing, and its recorded reason
 *       carries the obligation instead. The port's outcome must be the one its row governs: the same
 *       no-op where no row applies, and the row's own answer where one does.</li>
 * </ul>
 *
 * <p><strong>One recording is not an oracle and says so itself.</strong> The three
 * {@code shared-time__absent} cases were recorded with no shared time at all, and {@code moment}
 * reads an absent date as <em>now</em>, so the register they produced is a reading of the clock the
 * corpus was built at. There is nothing there for a port to reproduce — reproducing it would require
 * a clock inside a transformation the constitution requires to be pure — so those cases are held to
 * the one thing that is assertable: the port refuses the payload rather than inventing a date for
 * it. C35's two {@code hearing-date} cases are recorded clock-dependent as well and are
 * <em>not</em> treated this way: there the payload is complete and one field is filled from the
 * clock, so the rest of the document remains an oracle and the field is a difference C35 claims —
 * by reading the recorded value back as a London wall clock and requiring it to name the instant the
 * corpus was built at.
 *
 * <p><strong>One comparison is about a request rather than a value.</strong> C12 moves which day's
 * subscription set a hearing is addressed by, and that never reaches the document: a register built
 * from the wrong day's subscriptions looks entirely ordinary. The recorder captured the whole
 * {@code now-subscriptions} GET, so the audit compares the day the legacy asked for against the day
 * this port's own {@link Dates} answers for the same shared time, and reports the difference like
 * any other.
 *
 * <p><strong>It is fast because it is pure.</strong> The whole corpus runs in seconds against no
 * container, no socket and no clock, so it needs no tag and runs in {@code ./gradlew build} with
 * everything else.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a>
 */
// PMD.OnlyOneReturn: this suite is a decision table — which obligation a case carries, whether two
// runs ended in the same place, which row explains a divergence — and each question answers where it
// is decided. A single exit would put every one of those answers behind one variable.
@SuppressWarnings("PMD.OnlyOneReturn")
@DisplayName("Differential audit — 381 recorded legacy runs against the port")
class DifferentialAuditTest {

    private static final Logger LOG = LoggerFactory.getLogger(DifferentialAuditTest.class);

    /** Where the register the audit reads its C-numbers from lives, for the citation check. */
    private static final Path DEFECT_FIXES = Path.of("doc", "DEFECT-FIXES.md");

    /** A C-number at the head of a registered reference, e.g. {@code C10 (…)}. */
    private static final Pattern C_NUMBER = Pattern.compile("^(C\\d+) ");

    /** How a classified contract refusal names the field at fault in its message. */
    private static final String AT = " at ";

    /** The recorded reason a legacy run gave for matching nobody. */
    private static final String NO_MATCHED_SUBSCRIPTIONS = "no matched subscriptions";

    /** The recorded reason a legacy run gave for finding no youth. */
    private static final String NO_YOUTH_DEFENDANT = "no youth defendant";

    /** What each row was found to explain, tallied across the whole corpus for the T075 report. */
    private static final Map<String, Integer> EXPLAINED = new LinkedHashMap<>();

    /** Cases the corpus itself disqualifies as oracles, listed in the summary. */
    private static final List<String> NOT_AN_ORACLE = new ArrayList<>();

    /**
     * One case and place per row, so the summary is checkable rather than only countable.
     *
     * <p>A count alone cannot be followed back to anything. The report T075 commits names, for every
     * row, a case a reader can open — and the first one the audit meets is as good a witness as any
     * and is stable, because the corpus is enumerated in the order the recorder wrote it.
     */
    private static final Map<String, String> EXAMPLES = new LinkedHashMap<>();

    /** The production date reader, for the one comparison that is about a request and not a value. */
    private static final Dates DATES = new Dates();

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    /** Everything the mappers skipped on the way, which C19, C20 and C27 count rather than fail on. */
    private final List<TransformationAnomaly> anomalies = new ArrayList<>();

    private final GroupProceedingsPolicy groupProceedings =
            new GroupProceedingsPolicy(new ProcessingMetrics(new SimpleMeterRegistry()));

    private final RegisterTransformationChain chain = new RegisterTransformationChain(
            new RegisterBuilder(new Dates()),
            new SubscriptionMatcher(new SubscriptionRules()),
            new OutboundContractValidator(JacksonConfig.contractObjectMapper()));

    /**
     * Every recorded case, as the parameter of the audit below.
     *
     * @return the case identifiers
     */
    static List<String> recordedCorpus() {
        return DifferentialCorpus.caseIds();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recordedCorpus")
    @DisplayName("differs from the legacy only where a defect-fix row says it may")
    void differs_from_the_legacy_only_where_a_defect_fix_row_says_it_may(final String caseId) {
        final RecordedCase recorded = DifferentialCorpus.load(caseId);
        final PortOutcome port = run(recorded);

        if (recorded.sharedTimeAbsent()) {
            // The recording is a reading of the clock it was made at (moment.tz(undefined, zone) is
            // the current time), so there is no oracle here to agree or disagree with — not the
            // register date, not the file-name day, and not the day the subscription set was read
            // for. What is assertable is that the port did not invent one either.
            //
            // Only the shared-time cases are held this way, and deliberately so. C35's two clock
            // legs are ALSO recorded clock-dependent, but there the whole payload is present and it
            // is one field the legacy fills from the clock, so the rest of the document is a
            // perfectly good oracle and the field itself is a difference C35 claims by proving the
            // recorded value IS the clock. Excluding every clock-dependent case would have hidden
            // exactly the row the corpus was extended to reach.
            NOT_AN_ORACLE.add(caseId);
            assertThat(port.result())
                    .describedAs("%s was recorded with no shared time, so its output is a reading "
                            + "of the corpus's own clock; the port must refuse the payload rather "
                            + "than invent a date for it", caseId)
                    .isEqualTo(PortResult.FAILED);
            return;
        }

        tallyDerivations(recorded, port);
        for (final Divergence divergence : divergences(recorded, port)) {
            tally(caseId, divergence);
        }
    }

    @Test
    @DisplayName("registers nothing that is not a row of doc/DEFECT-FIXES.md")
    void registers_nothing_that_is_not_a_row_of_the_defect_fix_register() {
        // The register is only worth what its citations are worth: an entry quoting a C-number that
        // does not exist would attribute a behaviour change to a review that never happened.
        final String rows = read(DEFECT_FIXES);
        final List<String> cited = new ArrayList<>();
        RegisteredDefectFixes.claims().forEach(claim -> cited.add(claim.reference()));
        cited.add(RegisteredDefectFixes.forProperty("registerDate").reference());
        cited.add(RegisteredDefectFixes.forProperty("wording").reference());

        assertThat(cited).isNotEmpty();
        for (final String reference : cited) {
            final Matcher number = C_NUMBER.matcher(reference);
            assertThat(number.find())
                    .describedAs("every registered entry opens with its C-number, but %s does not",
                            reference)
                    .isTrue();
            assertThat(rows)
                    .describedAs("%s cites %s, which is not a row of doc/DEFECT-FIXES.md",
                            reference, number.group(1))
                    .contains("| " + number.group(1) + " | ");
        }
    }

    @Test
    @DisplayName("audits the whole recorded corpus and not a subset of it")
    void audits_the_whole_recorded_corpus() {
        // A corpus that quietly shrank would make this suite pass by looking at less.
        assertThat(recordedCorpus()).hasSize(381);
    }

    /**
     * Prints what each row was found to explain, which is the audit's own evidence for T075.
     */
    @AfterAll
    static void report() {
        final StringBuilder summary = new StringBuilder(512)
                .append("\nDifferential audit — differences claimed, by defect-fix row:");
        EXPLAINED.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(row -> summary.append("\n  ").append(row.getValue())
                        .append(" × ").append(row.getKey())
                        .append("\n      e.g. ").append(EXAMPLES.get(row.getKey())));
        summary.append("\n  ").append(NOT_AN_ORACLE.size())
                .append(" × case(s) the corpus marks clock-dependent, held to a refusal instead: ")
                .append(NOT_AN_ORACLE);
        // The audit's own evidence, logged rather than asserted: T075 reconciles these counts
        // against doc/DEFECT-FIXES.md, where a fix that explains nothing means the corpus misses
        // its shape rather than that the fix is absent.
        LOG.info("{}", summary);
    }

    // --- running the port ------------------------------------------------------------------------

    /**
     * What this port does with one recorded case's inputs.
     *
     * @param recorded the recorded case
     * @return the outcome
     */
    private PortOutcome run(final RecordedCase recorded) {
        if (recorded.subscriptionsNeverAnswered()) {
            // The legacy caught the reference-data failure and carried on with nothing; this port's
            // read raises out of the adapter, so the transformation is never reached at all. There
            // is nothing to run here, and saying so is more honest than running the chain against a
            // subscription set the port would never have been given.
            return PortOutcome.notTransformed(
                    "reference data never answered the now-subscriptions read");
        }

        final DistributionCommand command = commandFor(recorded);
        try {
            if (groupProceedings.suppresses(command, recorded.hearing())) {
                return new PortOutcome(PortResult.SUPPRESSED, null, "group-proceedings", null, "");
            }
            final TransformationResult result = chain.transform(
                    command, recorded.payload(), recorded.subscriptions(), anomalies::add);
            if (result instanceof TransformationResult.Register register) {
                return new PortOutcome(PortResult.REGISTER,
                        mapper.valueToTree(register.document()), null, null, "");
            }
            return new PortOutcome(PortResult.NO_REGISTER, null,
                    ((TransformationResult.NoRegister) result).reason().completion().value(),
                    null, "");
        } catch (TransformationFailedException classified) {
            return new PortOutcome(PortResult.FAILED, null, null,
                    classified.reason().name(), pointerOf(classified));
        }
    }

    /**
     * The validated request a recorded case would have been admitted under.
     *
     * <p>The hearing day and the request identity are the message's, not the payload's, and neither
     * reaches the transformation: the chain reads its dates from the envelope. They are filled in so
     * the correlation fields of the chain's own logging are the shape they are in production.
     *
     * @param recorded the recorded case
     * @return the command
     */
    private static DistributionCommand commandFor(final RecordedCase recorded) {
        return new DistributionCommand(
                "RESULTS",
                UUID.nameUUIDFromBytes(recorded.caseId().getBytes(StandardCharsets.UTF_8)),
                recorded.hearingId(),
                LocalDate.parse("2020-01-20"),
                Instant.parse("2020-06-01T10:00:00Z"),
                "Hearing_Resulted");
    }

    /**
     * The JSON pointer a classified contract refusal names.
     *
     * <p>Read out of the message rather than off the exception: the bounded
     * {@code OUTBOUND_CONTRACT_VIOLATION} is what the pipeline records and what the dead-letter
     * carries, and the pointer is deliberately a log-and-message diagnostic rather than a second
     * field on the failure (C29). The audit wants it because a refusal for the right document and
     * the wrong reason is still a difference.
     *
     * @param classified the failure
     * @return the pointer, or the empty string where the failure named no field
     */
    private static String pointerOf(final TransformationFailedException classified) {
        final String message = classified.getMessage();
        final int at = message == null ? -1 : message.lastIndexOf(AT);
        return at < 0 ? "" : message.substring(at + AT.length());
    }

    // --- what differs ----------------------------------------------------------------------------

    /**
     * Everything about this run that the legacy did differently.
     *
     * <p>At most one outcome divergence — the run as a whole ended somewhere else — and then, only
     * where both sides produced a document, one field divergence per component the comparator
     * cannot reconcile.
     *
     * @param recorded the recorded case
     * @param port     what the port did
     * @return the divergences, empty where the port did exactly what the legacy did
     */
    private static List<Divergence> divergences(
            final RecordedCase recorded, final PortOutcome port) {

        final List<Divergence> divergences = new ArrayList<>(referenceDataDay(recorded, port));
        if (!agrees(recorded, port)) {
            divergences.add(new Divergence.Outcome(recorded, port));
            return List.copyOf(divergences);
        }
        if (!recorded.producedDocument()) {
            return List.copyOf(divergences);
        }
        JsonParity.differences(recorded.expected(), port.document()).stream()
                .map(difference -> (Divergence) new Divergence.Field(recorded, port,
                        difference.path(), difference.expected(), difference.actual()))
                .forEach(divergences::add);
        return List.copyOf(divergences);
    }

    /**
     * Whether the two runs would read the same day's subscription set, as a divergence where they
     * would not.
     *
     * <p>The one externally-visible effect of C10 that never reaches the document. The recorder
     * captured the whole {@code now-subscriptions} GET — query string included — so the day the
     * legacy asked for is evidence rather than a re-derivation, and the day this port asks for is
     * computed here from the same shared time by the production {@link Dates}. A register addressed
     * from the wrong day's subscription set is otherwise indistinguishable from a correct one, which
     * is precisely why C12 is its own row and why it took a comparison of its own to observe.
     *
     * @param recorded the recorded case
     * @param port     what the port did
     * @return the divergence, or nothing where the two days agree or the run never made the read
     */
    private static List<Divergence> referenceDataDay(
            final RecordedCase recorded, final PortOutcome port) {

        final String legacyDay = recorded.referenceDataDay().orElse(null);
        final String sharedTime = recorded.sharedTime().orElse(null);
        if (legacyDay == null || sharedTime == null) {
            return List.of();
        }
        final String portDay;
        try {
            portDay = DATES.subscriptionDay(sharedTime).toString();
        } catch (TransformationFailedException theSharedTimeIsUnreadable) {
            // The port refuses the payload rather than reading a day out of it, which is the whole
            // of C2's territory and is already claimed as an outcome divergence.
            return List.of();
        }
        return legacyDay.equals(portDay)
                ? List.of()
                : List.of(new Divergence.ReferenceDataDay(recorded, port, legacyDay, portDay));
    }

    /**
     * Whether the port's run ended where the legacy's did.
     *
     * <p>The legacy's four endings map onto this port's five, and two of them have no counterpart at
     * all: a run whose exception was caught, logged and discarded reported {@code Success: true}
     * with no register and no record, and so did a run whose reference-data read never answered.
     * Neither is an ending this port has, which is C1's and C2's whole point — so those cases are
     * never agreement, and the register has to say what the port's ending is instead.
     *
     * @param recorded the recorded case
     * @param port     what the port did
     * @return whether the two runs ended in the same place
     */
    // PMD.OnlyOneReturn: this is a mapping table written as one, and a single exit would hide which
    // of the legacy's endings was matched.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static boolean agrees(final RecordedCase recorded, final PortOutcome port) {
        if (recorded.subscriptionsNeverAnswered()) {
            return false;
        }
        return switch (recorded.outcome()) {
            case "document" -> port.result() == PortResult.REGISTER;
            case "skipped-group-proceedings" -> port.suppressed();
            case "swallowed-exception" -> false;
            case "no-document" -> agreesOnNoDocument(recorded, port);
            default -> false;
        };
    }

    /**
     * Whether the port declined for the same recorded reason the legacy did.
     *
     * @param recorded the recorded case
     * @param port     what the port did
     * @return whether the two no-ops are the same no-op
     */
    private static boolean agreesOnNoDocument(
            final RecordedCase recorded, final PortOutcome port) {

        final String reason = recorded.noDocumentReason();
        return reason.endsWith(NO_MATCHED_SUBSCRIPTIONS) && port.isNoRegister("no-subscriptions")
                || reason.endsWith(NO_YOUTH_DEFENDANT) && port.isNoRegister("no-youth-defendants");
    }

    // --- attributing it --------------------------------------------------------------------------

    /**
     * Requires exactly one registered row to explain a divergence, and counts it.
     *
     * @param caseId     the case the divergence was observed in
     * @param divergence the divergence
     */
    private static void tally(final String caseId, final Divergence divergence) {
        final List<Claim> claims = RegisteredDefectFixes.claimedBy(divergence);

        assertThat(claims)
                .describedAs("%s: %s is not explained by any doc/DEFECT-FIXES.md row, so it is a "
                        + "port defect until one says otherwise — %s",
                        caseId, where(divergence), describe(divergence))
                .isNotEmpty();
        assertThat(claims)
                .describedAs("%s: %s is claimed by more than one row, so the register has stopped "
                        + "saying which fix produced what", caseId, where(divergence))
                .hasSize(1);

        EXPLAINED.merge(claims.get(0).reference(), 1, Integer::sum);
        EXAMPLES.putIfAbsent(claims.get(0).reference(), caseId + " — " + where(divergence));
    }

    /**
     * Counts the components the comparator reconciled by derivation rather than by equality.
     *
     * <p>They never become divergences — the register's whole point is that the comparator computes
     * what the port owes and demands exactly that — so without this the report would show C10 and
     * C24 explaining nothing, which is precisely the reading T075 must not draw from it: a fix that
     * produces no difference is evidence the corpus misses its shape, and these two produce a great
     * many.
     *
     * @param recorded the recorded case
     * @param port     what the port did
     */
    private static void tallyDerivations(final RecordedCase recorded, final PortOutcome port) {
        if (!recorded.producedDocument() || port.document() == null) {
            return;
        }
        countReconciled(recorded.caseId(), recorded.expected());
    }

    /**
     * Walks a recorded document, counting every component a derivation covers.
     *
     * @param caseId the case being walked, for the report's example
     * @param node   the node to walk
     */
    private static void countReconciled(final String caseId, final JsonNode node) {
        if (node.isArray()) {
            node.forEach(entry -> countReconciled(caseId, entry));
        } else if (node.isObject()) {
            node.propertyNames().forEach(name -> {
                final RegisteredDefectFixes.Fix fix = RegisteredDefectFixes.forProperty(name);
                if (fix != null) {
                    EXPLAINED.merge(fix.reference(), 1, Integer::sum);
                    EXAMPLES.putIfAbsent(fix.reference(),
                            caseId + " — the derived component " + name);
                }
            });
            node.propertyStream().forEach(property ->
                    countReconciled(caseId, property.getValue()));
        }
    }

    /**
     * Where a divergence was observed, for a failure message.
     *
     * @param divergence the divergence
     * @return the JSON pointer, or a word for the run itself
     */
    private static String where(final Divergence divergence) {
        if (divergence instanceof Divergence.Field field) {
            return "the difference at " + field.path();
        }
        return divergence instanceof Divergence.ReferenceDataDay
                ? "the day the subscription set was read for"
                : "the run's outcome";
    }

    /**
     * A divergence rendered for a reader of a red build.
     *
     * @param divergence the divergence
     * @return both sides of it, in one line
     */
    private static String describe(final Divergence divergence) {
        if (divergence instanceof Divergence.Field field) {
            return "the legacy wrote " + render(field.oracleValue())
                    + " and the port wrote " + render(field.portValue());
        }
        if (divergence instanceof Divergence.ReferenceDataDay day) {
            return "the legacy read the subscriptions in force on " + day.oracleDay()
                    + " and the port reads the set in force on " + day.portDay();
        }
        final RecordedCase recorded = divergence.recorded();
        return "the legacy ended as " + recorded.outcome()
                + (recorded.noDocumentReason().isEmpty()
                        ? "" : " (" + recorded.noDocumentReason() + ")")
                + " and the port " + divergence.port().describe();
    }

    /**
     * A node rendered short enough to read in a failure message.
     *
     * @param node the node; may be {@code null}
     * @return a short rendering
     */
    private static String render(final JsonNode node) {
        if (node == null) {
            return "nothing";
        }
        final String rendered = node.toString();
        return rendered.length() <= 160 ? rendered : rendered.substring(0, 160) + "...";
    }

    /**
     * Reads a file of this repository.
     *
     * @param path the path, relative to the project directory
     * @return its content
     */
    private static String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException cannotRead) {
            throw new UncheckedIOException(cannotRead);
        }
    }
}
