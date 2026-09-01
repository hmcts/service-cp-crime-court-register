package uk.gov.hmcts.cp.courtregister.support;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.support.DifferentialCorpus.RecordedCase;

/**
 * The derivation register: every way this port is allowed to differ from the legacy, keyed on the
 * {@code doc/DEFECT-FIXES.md} row that allows it.
 *
 * <p><strong>Why this exists at all.</strong> Everything recorded under
 * {@code src/test/resources/differential/recorded/} is a recording of the real Node function app and
 * is the oracle's truth. It is never regenerated and never edited — a golden file somebody adjusted
 * to agree with the port has stopped being evidence. This port, though, is deliberately not a
 * bug-for-bug one: thirty-one catalogued defects are fixed, so for those components the recording
 * carries the legacy behaviour and the port is required to produce something else, and something has
 * to reconcile the two.
 *
 * <p><strong>Registered here means registered there.</strong> Nothing belongs in this class that is
 * not a C-number in {@code doc/DEFECT-FIXES.md}, and every entry quotes its C-number into the
 * failure message so a reader of a red build is one grep from the reasoning and the sign-off. An
 * observed difference that no entry claims is not a gap in this file by default: it is a port defect
 * until a C-row says otherwise, which is the rule the differential audit enforces in both
 * directions.
 *
 * <p><strong>Two mechanisms, because there are two kinds of difference.</strong>
 *
 * <ul>
 *   <li>A <em>derivation</em> ({@link Fix}, looked up by property name and applied by
 *       {@link JsonParity}) is for a component the port re-renders: given the value the oracle
 *       recorded, it computes the value the port is now required to write, and the comparator
 *       demands exactly that. It is not an exclusion — excluding the field would make every golden
 *       assertion stop looking at it, and the next mistake in that component would sail through a
 *       green suite. The check is as strict as equality was; only the expected string moved. It can
 *       fail in a third way, and does so loudly: an oracle value the derivation does not describe is
 *       reported as a difference rather than waved through.</li>
 *   <li>A <em>claim</em> ({@link Claim}, applied by the differential audit) is for everything a
 *       value-for-value derivation cannot express — a field the legacy never wrote, a case the port
 *       drops, a document that exists on one side and not the other. Each carries a predicate that
 *       recognises the <em>signature</em> of its fix in an observed divergence, so it explains that
 *       divergence and no other. The claims are mutually exclusive by construction and the audit
 *       asserts it: a divergence two rows could explain is a register that has stopped saying which
 *       fix produced what.</li>
 * </ul>
 *
 * <p>Both halves are deliberately narrow. A predicate that said "anything at this path may differ"
 * would be an exclusion wearing a C-number, and the first regression in that component would ship
 * inside a green suite.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a>
 */
// PMD.OnlyOneReturn: a predicate reads as a list of disqualifying conditions, each answering where
// it decides; funnelling them through one exit would put the reasoning behind a variable.
@SuppressWarnings("PMD.OnlyOneReturn")
public final class RegisteredDefectFixes {

    /** {@code Europe/London} — the zone whose real rules decide the offsets below. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /** How {@code DateService.getLocalDateTime} renders a time: a London wall clock, then a 'Z'. */
    private static final DateTimeFormatter LEGACY_RENDERING =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** How this port renders an instant: whole seconds, always UTC. */
    private static final DateTimeFormatter INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    /** How this port renders a calendar day. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** The sentinel {@code OffenceMapper.js:17} joins a wording and its legislation with. */
    private static final String SENTINEL = "####";

    /** What JavaScript string concatenation leaves behind where a value was absent. */
    private static final String UNDEFINED = "undefined";

    /** The three renderings {@code HearingMapper} maps {@code attendanceType} to. */
    private static final List<String> APPEARANCES =
            List.of("In person", "By video link", "Not present");

    /**
     * The derivations, by the property name the component reaches the wire under.
     *
     * <p>Matched by property name rather than by JSON pointer because the comparator is handed
     * fragments as well as whole documents, and the same component sits at a different depth in
     * each. Both components below occur at exactly one place in this contract, so the two readings
     * coincide; a component that occurred at several, and was fixed at only one of them, would need
     * a pointer-aware entry, and that decision belongs with the fix that first needs it.
     */
    private static final Map<String, Fix> REGISTER = Map.of(
            "registerDate",
            new Fix("C10 (BST local time labelled as UTC)",
                    RegisteredDefectFixes::registerDate),
            "wording",
            new Fix("C24 (#### sentinel and undefined residue in wording)",
                    RegisteredDefectFixes::wording));

    /**
     * The claims, in the order the audit consults them.
     *
     * <p>Order is a reading convenience and nothing more: the predicates are mutually exclusive, and
     * {@link #claimedBy} returns all of them so the audit can prove it.
     */
    private static final List<Claim> REGISTERED_CLAIMS = List.of(
            fileName(),
            verdictCode(),
            defendantPresent(),
            defendantAppearanceDetails(),
            applicationWithoutAProsecutingApplicant(),
            explicitNullOmitted(),
            registerThatReachesNobody(),
            documentTheContractRefuses(),
            matchedOnEveryDefendant(),
            groupProceedingsReadStrictly(),
            youthDefendantWhoseDetailsCannotBeResolved(),
            attendanceReadFromAnEmptyList(),
            failureSwallowedAndReportedAsSuccess());

    private RegisteredDefectFixes() {
    }

    /**
     * The defect fix registered against a property name, if any.
     *
     * @param propertyName the property name as it reaches the wire
     * @return the fix, or {@code null} when the property is compared by equality
     */
    public static Fix forProperty(final String propertyName) {
        return REGISTER.get(propertyName);
    }

    /**
     * Every registered claim, for a report that wants to name the rows the audit rests on.
     *
     * @return the claims
     */
    public static List<Claim> claims() {
        return REGISTERED_CLAIMS;
    }

    /**
     * The claims that explain an observed divergence.
     *
     * <p>Returns all of them rather than the first, because "exactly one row explains this" is an
     * assertion the audit makes and not an assumption it is entitled to.
     *
     * @param divergence the observed divergence
     * @return the claims whose predicate recognises it, empty where none does
     */
    public static List<Claim> claimedBy(final Divergence divergence) {
        return REGISTERED_CLAIMS.stream().filter(claim -> claim.explains(divergence)).toList();
    }

    // --- the derivations -------------------------------------------------------------------------

    /**
     * The {@code registerDate} derivation — {@code doc/DEFECT-FIXES.md} row C10.
     *
     * <p>The oracle records {@code DateService.getLocalDateTime}'s output: a {@code Europe/London}
     * wall-clock date-time with the character {@code Z} appended, whatever the real offset was, so a
     * 10:00 UTC share reaches the wire as {@code 11:00:00Z} for half the year. The port carries the
     * instant it was given. The derivation therefore reads the wall clock straight back out of the
     * oracle's own string, asks the {@code Europe/London} rules which offsets that wall clock had on
     * that date, and renders the instants they name.
     *
     * <p><strong>It derives, it does not re-run the port.</strong> The wall clock comes from the
     * recording rather than from {@link uk.gov.hmcts.cp.courtregister.pipeline.Dates}, so a fault in
     * this port's own parsing cannot cancel itself out.
     *
     * <p><strong>The repeated autumn hour permits two answers, and honestly so.</strong> London
     * repeats 01:00–02:00 once a year and the oracle's rendering — a wall clock plus a meaningless
     * {@code Z} — genuinely does not record which of the two it was. Both offsets the zone allows
     * are accepted, which still fixes the digits exactly and still rejects every other offset. The
     * corpus's {@code shared-time__dst-fall-back} case lands in that hour, so this is not a
     * hypothetical allowance.
     *
     * @param oracleValue the value in the recording
     * @return the permitted renderings, or empty when the oracle's value is undescribed
     */
    private static List<String> registerDate(final String oracleValue) {
        return instantsOf(oracleValue).stream().map(INSTANT::format).toList();
    }

    /**
     * The {@code wording} derivation — {@code doc/DEFECT-FIXES.md} row C24.
     *
     * <p>{@code OffenceMapper.js:17} writes {@code wording + '####' + offenceLegislation}, so an
     * offence with no legislation reaches progression as {@code "…####undefined"} and progression's
     * own PDF generator substitutes the sentinel for a newline at render time. The port joins the
     * two with a real newline and omits the legislation line entirely when there is none, which
     * renders identically for the populated case and loses the {@code undefined} residue for the
     * absent one.
     *
     * <p>So the derivation splits the recording at its own sentinel and re-joins the halves the way
     * the port is required to. A recording carrying no sentinel is not a value this fix describes —
     * the legacy writes one unconditionally — and is reported rather than passed.
     *
     * @param oracleValue the value in the recording
     * @return the permitted rendering, or empty when the oracle's value is undescribed
     */
    private static List<String> wording(final String oracleValue) {
        if (oracleValue == null) {
            return List.of();
        }
        final int sentinel = oracleValue.indexOf(SENTINEL);
        if (sentinel < 0) {
            return List.of();
        }
        final String wording = present(oracleValue.substring(0, sentinel));
        final String legislation = present(oracleValue.substring(sentinel + SENTINEL.length()));
        if (wording.isEmpty() && legislation.isEmpty()) {
            return List.of();
        }
        if (wording.isEmpty()) {
            return List.of(legislation);
        }
        if (legislation.isEmpty()) {
            return List.of(wording);
        }
        return List.of(wording + "\n" + legislation);
    }

    /**
     * One half of a sentinel-joined value, with JavaScript's residue for an absent one removed.
     *
     * @param half the half as the recording carries it
     * @return the half, or the empty string where it was never there
     */
    private static String present(final String half) {
        return UNDEFINED.equals(half) ? "" : half;
    }

    /**
     * The instants a {@code getLocalDateTime} rendering could name — the shared half of C10.
     *
     * @param oracleValue the value in the recording
     * @return the instants, empty where the value is not a rendering this fix describes
     */
    private static List<LocalDateTime> instantsOf(final String oracleValue) {
        if (oracleValue == null || !oracleValue.endsWith("Z")) {
            return List.of();
        }
        final LocalDateTime wallClock;
        try {
            wallClock = LocalDateTime.parse(
                    oracleValue.substring(0, oracleValue.length() - 1), LEGACY_RENDERING);
        } catch (DateTimeParseException notTheLegacyRendering) {
            return List.of();
        }
        final List<LocalDateTime> instants = new ArrayList<>(2);
        for (final ZoneOffset offset : LONDON.getRules().getValidOffsets(wallClock)) {
            instants.add(LocalDateTime.ofInstant(wallClock.toInstant(offset), ZoneOffset.UTC));
        }
        return instants;
    }

    // --- the claims ------------------------------------------------------------------------------

    /**
     * C11 — the file name carries colons and is not unique.
     *
     * @return the claim
     */
    private static Claim fileName() {
        return new Claim("C11 (filename carries colons and is not unique)",
                "court-register_{registerDate}_{code}.pdf embeds a full datetime, so the name "
                        + "carries colons Windows refuses and two hearings at one centre in the same "
                        + "second collide. The port writes the corrected register day, the code and "
                        + "the hearing id — and the day is derived here from the recording's own "
                        + "instant through C10, so a name that is right about the code and wrong "
                        + "about the day is still a difference.",
                divergence -> {
                    if (!(divergence instanceof Divergence.Field field)
                            || !"/fileName".equals(field.path())) {
                        return false;
                    }
                    final String oracle = string(field.oracleValue());
                    final String port = string(field.portValue());
                    if (oracle == null || port == null
                            || !oracle.startsWith("court-register_") || !oracle.endsWith(".pdf")) {
                        return false;
                    }
                    final String body = oracle.substring("court-register_".length(),
                            oracle.length() - ".pdf".length());
                    final int lastPart = body.lastIndexOf('_');
                    if (lastPart < 0) {
                        return false;
                    }
                    final String courtCentreCode = body.substring(lastPart + 1);
                    final String hearingId = divergence.recorded().hearingId().toString();
                    return instantsOf(body.substring(0, lastPart)).stream()
                            .map(instant -> "court-register_" + DAY.format(instant) + '_'
                                    + courtCentreCode + '_' + hearingId + ".pdf")
                            .anyMatch(port::equals);
                });
    }

    /**
     * C23 — {@code verdictCode} carries a prose description.
     *
     * @return the claim
     */
    private static Claim verdictCode() {
        return new Claim("C23 (verdictCode carries a prose description)",
                "OffenceMapper.js:20 writes verdict.verdictType.description into a field named "
                        + "verdictCode, so the field is prose where the platform verdict model has a "
                        + "code — and absent altogether where the verdict type carries no "
                        + "description, which is what the corpus recorded. The port writes the code "
                        + "itself, falling back to the category type; the claim therefore requires "
                        + "the port's value to be code-shaped and the recording's not to be.",
                divergence -> {
                    if (!(divergence instanceof Divergence.Field field)
                            || !field.path().endsWith("/verdictCode")) {
                        return false;
                    }
                    final String port = string(field.portValue());
                    final String oracle = string(field.oracleValue());
                    return port != null && isCodeShaped(port)
                            && (oracle == null || !isCodeShaped(oracle));
                });
    }

    /**
     * C9 — {@code defendantPresent} can never be true.
     *
     * @return the claim
     */
    private static Claim defendantPresent() {
        return new Claim("C9 (attendance date can never match)",
                "HearingMapper.js:14 compares an attendance day (a date) with the register date (a "
                        + "datetime), which are never equal in production, so defendantPresent is "
                        + "false on every register the legacy has ever produced. The port matches "
                        + "attendance against any day one of the defendant's gathered results was "
                        + "ordered on, so the field becomes real data. The claim holds the "
                        + "recording to the false it can only ever be — a recorded true would mean "
                        + "the oracle is not the flow this row describes.",
                divergence -> divergence instanceof Divergence.Field field
                        && field.path().endsWith("/hearing/defendantPresent")
                        && isFalse(field.oracleValue())
                        && field.portValue() != null && field.portValue().isBoolean());
    }

    /**
     * C9 — {@code defendantAppearanceDetails} is never written.
     *
     * @return the claim
     */
    private static Claim defendantAppearanceDetails() {
        return new Claim("C9 (attendance date can never match)",
                "The sibling of the field above: because the comparison at HearingMapper.js:14 can "
                        + "never match, the appearance details it guards are never written and the "
                        + "PDF's appearance column renders its dash fallback. The port writes one of "
                        + "the three attendanceType renderings, so the claim requires the recording "
                        + "to carry nothing and the port to carry one of exactly those three.",
                divergence -> divergence instanceof Divergence.Field field
                        && field.path().endsWith("/hearing/defendantAppearanceDetails")
                        && field.oracleValue() == null
                        && APPEARANCES.contains(string(field.portValue())));
    }

    /**
     * C22 — non-prosecuting-authority applications reach the register.
     *
     * @return the claim
     */
    private static Claim applicationWithoutAProsecutingApplicant() {
        return new Claim("C22 (non-prosecuting-authority applications reach the register)",
                "ProsecutionCaseOrApplicationMapper.js:64-66 implements only the subject half of "
                        + "the eligibility its own comment states, so a defence-initiated "
                        + "application reaches the register. The port requires an applicant that "
                        + "prosecutes, in the context builder and in the mapper alike. The claim "
                        + "checks the drop against the hearing itself: every entry the port left "
                        + "out must be a court application whose applicant carries no "
                        + "prosecutingAuthority, so a case dropped for any other reason is not "
                        + "explained here.",
                divergence -> {
                    if (!(divergence instanceof Divergence.Field field)
                            || !field.path().endsWith("/prosecutionCasesOrApplications")) {
                        return false;
                    }
                    final JsonNode oracle = field.oracleValue();
                    final JsonNode port = field.portValue();
                    if (oracle == null || port == null || !oracle.isArray() || !port.isArray()
                            || port.size() >= oracle.size()) {
                        return false;
                    }
                    return droppedEntries(oracle, port).stream()
                            .allMatch(dropped -> isDefenceInitiated(dropped,
                                    divergence.recorded().hearing()));
                });
    }

    /**
     * C26 — a field the legacy writes as an explicit {@code null}.
     *
     * @return the claim
     */
    private static Claim explicitNullOmitted() {
        return new Claim("C26 (mapper/model drift)",
                "The legacy's mappers copy whatever the payload holds, so a field the producer sent "
                        + "as an explicit JSON null reaches the wire as null — which the frozen "
                        + "contract refuses, which is why every recording this claim covers is "
                        + "classified SCHEMA_INVALID at exactly this pointer. The port's typed "
                        + "records cannot express present-and-null: an absent value is an absent "
                        + "field. The claim requires the recording's own violation list to name the "
                        + "pointer, so it covers only the shapes the contract was already refusing "
                        + "and never a field the port has simply lost.",
                divergence -> divergence instanceof Divergence.Field field
                        && field.oracleValue() != null && field.oracleValue().isNull()
                        && field.portValue() == null
                        && divergence.recorded().violationPointers().contains(field.path()));
    }

    /**
     * C36 — recipient-less registers submitted to nobody.
     *
     * @return the claim
     */
    private static Claim registerThatReachesNobody() {
        return new Claim("C36 (recipient-less registers submitted to nobody)",
                "When every matched subscription is dropped by the recipient predicate — no usable "
                        + "email, letter delivery only, forDistribution false (C27 counts each) — "
                        + "OutboundCourtRegister/index.js:28-40 posts the document with recipients "
                        + "undefined. Progression stores it, renders the PDF and emits a "
                        + "notification nothing subscribes to, so the register sticks at GENERATED "
                        + "forever. The port does not submit it: the run completes no-subscriptions, "
                        + "there being nobody to distribute to.",
                divergence -> divergence instanceof Divergence.Outcome
                        && divergence.recorded().producedDocument()
                        && !hasRecipients(divergence.recorded().expected())
                        && divergence.port().isNoRegister("no-subscriptions"));
    }

    /**
     * C29 — a missing required field silently loses the register.
     *
     * @return the claim
     */
    private static Claim documentTheContractRefuses() {
        return new Claim("C29 (missing required address silently loses the register)",
                "A document the frozen add-court-register contract refuses is answered 400 by "
                        + "progression, and C1's catch swallows it: the whole hearing's register is "
                        + "lost with no trace. The port validates before sending, so the same "
                        + "document is an explicit, dead-lettered OUTBOUND_CONTRACT_VIOLATION. The "
                        + "claim is not satisfied by any refusal: the pointer the port names must be "
                        + "one the recorder's own validator named, or an extension of one — ajv "
                        + "reports a required failure against the object, this port appends the "
                        + "missing property — so a port refusing the right document for the wrong "
                        + "reason is still a difference.",
                divergence -> divergence instanceof Divergence.Outcome
                        && RecordedCase.SCHEMA_INVALID.equals(
                                divergence.recorded().contractStatus())
                        && divergence.port().refusedByTheContract()
                        && divergence.recorded().violationPointers().stream()
                                .anyMatch(pointer ->
                                        divergence.port().failurePointer().startsWith(pointer)));
    }

    /**
     * C31 — only the first defendant's vocabulary is matched.
     *
     * @return the claim
     */
    private static Claim matchedOnEveryDefendant() {
        return new Claim("C31 (only the first defendant's vocabulary is matched)",
                "CourtRegisterSubscriptions/index.js:49 matches on registerDefendants[0].vocabulary "
                        + "alone, and the list is not pre-filtered to youths, so an adult-first "
                        + "hearing has its youth register matched against adult vocabulary and "
                        + "reaches nobody. The port asks every register defendant. The claim "
                        + "therefore requires the recording's own counts to carry that signature — "
                        + "a register built for two or more defendants that matched no subscription "
                        + "at all — and the port to have addressed one, whether it then sent the "
                        + "register or had it refused by the contract (C29).",
                divergence -> divergence instanceof Divergence.Outcome
                        && divergence.recorded().matchedSubscriptions().orElse(-1) == 0
                        && divergence.recorded().registerDefendants().orElse(0) >= 2
                        && divergence.port().addressedSomebody());
    }

    /**
     * C7 — the group-proceedings skip is loosely typed.
     *
     * @return the claim
     */
    private static Claim groupProceedingsReadStrictly() {
        return new Claim("C7 (group-proceedings skip is silent and loosely typed)",
                "CourtRegisterOrchestrator/index.js:21-23 proceeds only on null or false under "
                        + "JavaScript's loose equality, so every other value suppresses the register "
                        + "with no record — the string \"false\" included. The port suppresses on "
                        + "the JSON boolean true and nothing else, and counts anything that is not a "
                        + "boolean as a contract anomaly. The claim requires the recording to have "
                        + "skipped on a value that is not a boolean, so a hearing that really is "
                        + "group proceedings is not explained here.",
                divergence -> divergence instanceof Divergence.Outcome
                        && "skipped-group-proceedings".equals(divergence.recorded().outcome())
                        && isNotABoolean(
                                divergence.recorded().hearing().get("isGroupProceedings"))
                        && !divergence.port().suppressed());
    }

    /**
     * C19 — the youth mapper dies on non-person defendants.
     *
     * @return the claim
     */
    private static Claim youthDefendantWhoseDetailsCannotBeResolved() {
        return new Claim("C19 (youth mapper dies on non-person defendants)",
                "YouthDefendantMapper.js:32,34 dereferences personDefendant.personDetails with no "
                        + "legal-entity fallback, the TypeError is swallowed at "
                        + "OutboundCourtRegister/index.js:62-64, and the whole hearing's register is "
                        + "silently lost — which is exactly the swallowed error this claim matches "
                        + "on. The port omits the defendant it cannot resolve, counts it, and lets "
                        + "the register survive for the rest; where nothing survives, the document "
                        + "it assembles is refused by the contract (C29) rather than lost.",
                divergence -> divergence instanceof Divergence.Outcome
                        && swallowed(divergence.recorded(), "reading 'personDetails'")
                        && divergence.port().addressedSomebody());
    }

    /**
     * C8 — the attendance lookup throws on an empty list.
     *
     * @return the claim
     */
    private static Claim attendanceReadFromAnEmptyList() {
        return new Claim("C8 (attendance lookup assigns instead of comparing)",
                "HearingMapper.js:13 uses an assignment where it means a comparison, so find always "
                        + "answers element 0 — and on an empty defendantAttendance that is "
                        + "undefined, whose attendanceDays dereference throws and takes the register "
                        + "with it. The port selects by equality against the mapped defendant's ids "
                        + "and answers defendantPresent false for an absent or empty list, so the "
                        + "register survives.",
                divergence -> divergence instanceof Divergence.Outcome
                        && swallowed(divergence.recorded(), "reading 'attendanceDays'")
                        && divergence.port().result() == PortResult.REGISTER);
    }

    /**
     * C2 — a failure swallowed and reported as success.
     *
     * @return the claim
     */
    private static Claim failureSwallowedAndReportedAsSuccess() {
        return new Claim("C2 (orchestration reports success on failure)",
                "Two recorded shapes, one defect: a reference-data outage that "
                        + "ReferenceDataService.js catches and answers nothing for, and a shared "
                        + "time the date service cannot read, whose RangeError cascades into a "
                        + "second TypeError one activity later. Both end in Success: true with no "
                        + "register and no record, which is the row's own sentence — every code path "
                        + "must end in a recorded terminal state, and no failure handling may fail "
                        + "unrecorded (C13: never a swallowed secondary throw; C3: which "
                        + "classification a refused read earns). The port reaches a classified "
                        + "failure, or never runs the transformation at all because the read raises "
                        + "in the adapter, where ReferenceDataNowSubscriptionsClientTest and "
                        + "DistributionPipelineTest pin it.",
                divergence -> divergence instanceof Divergence.Outcome
                        && (swallowed(divergence.recorded(), "Invalid time value")
                                || divergence.recorded().subscriptionsNeverAnswered())
                        && divergence.port().classifiedOrNeverRun());
    }

    // --- reading the trees -----------------------------------------------------------------------

    /**
     * A node's text, where it has any.
     *
     * @param node the node; may be {@code null}
     * @return the text, or {@code null}
     */
    private static String string(final JsonNode node) {
        return node == null || !node.isString() ? null : node.stringValue();
    }

    /**
     * Whether a node is the JSON boolean {@code false}.
     *
     * @param node the node; may be {@code null}
     * @return whether it is false
     */
    private static boolean isFalse(final JsonNode node) {
        return node != null && node.isBoolean() && !node.booleanValue();
    }

    /**
     * Whether a node is missing or is an explicit JSON null, which this payload spells the same
     * absence two ways with.
     *
     * @param node the node; may be {@code null}
     * @return whether nothing is there
     */
    private static boolean isAbsent(final JsonNode node) {
        return node == null || node.isNull();
    }

    /**
     * Whether a node is present and is not a JSON boolean.
     *
     * @param node the node; may be {@code null}
     * @return whether it is present and not a boolean
     */
    private static boolean isNotABoolean(final JsonNode node) {
        return node != null && !node.isNull() && !node.isBoolean();
    }

    /**
     * Whether a value looks like a code rather than like prose.
     *
     * @param value the value
     * @return whether every character is one a code is written with
     */
    private static boolean isCodeShaped(final String value) {
        return !value.isEmpty()
                && value.equals(value.toUpperCase(Locale.ROOT))
                && value.chars().allMatch(character ->
                        Character.isLetterOrDigit(character) || character == '_');
    }

    /**
     * Whether the legacy recorded a swallowed error carrying some text.
     *
     * @param recorded the recorded case
     * @param text     the text to look for
     * @return whether any swallowed error carries it
     */
    private static boolean swallowed(final RecordedCase recorded, final String text) {
        return recorded.swallowedErrors().stream().anyMatch(error -> error.contains(text));
    }

    /**
     * Whether a recorded document names anybody to send it to.
     *
     * @param document the recorded document
     * @return whether it carries at least one recipient
     */
    private static boolean hasRecipients(final JsonNode document) {
        final JsonNode recipients = document.get("recipients");
        return recipients != null && recipients.isArray() && !recipients.isEmpty();
    }

    /**
     * The entries of a recorded list the port did not carry, by their reference.
     *
     * @param oracle the recorded list
     * @param port   the port's list
     * @return the recorded entries the port left out
     */
    private static List<JsonNode> droppedEntries(final JsonNode oracle, final JsonNode port) {
        final List<String> kept = new ArrayList<>();
        port.forEach(entry -> kept.add(string(entry.get("caseOrApplicationReference"))));
        final List<JsonNode> dropped = new ArrayList<>();
        oracle.forEach(entry -> {
            if (!kept.contains(string(entry.get("caseOrApplicationReference")))) {
                dropped.add(entry);
            }
        });
        return dropped;
    }

    /**
     * Whether a dropped register entry is a court application the hearing shows was not brought by
     * a prosecuting authority.
     *
     * @param dropped the entry the port left out
     * @param hearing the hearing it was gathered from
     * @return whether the hearing accounts for the drop under C22
     */
    private static boolean isDefenceInitiated(final JsonNode dropped, final JsonNode hearing) {
        final String applicationId = string(dropped.get("courtApplicationId"));
        if (applicationId == null) {
            return false;
        }
        final JsonNode applications = hearing.get("courtApplications");
        if (applications == null || !applications.isArray()) {
            return false;
        }
        for (final JsonNode application : applications) {
            if (applicationId.equals(string(application.get("id")))) {
                final JsonNode applicant = application.get("applicant");
                // An explicit JSON null is an absent applicant here, and the corpus records both
                // shapes: `drop-optional-field` removes the block and `null-field` nulls it. The
                // eligibility gate reads them the same way, so this claim has to as well.
                return isAbsent(applicant) || isAbsent(applicant.get("prosecutingAuthority"));
            }
        }
        return false;
    }

    // --- the vocabulary an observed divergence is described in -----------------------------------

    /**
     * One registered component and the derivation that reconciles the port with the oracle.
     *
     * @param reference  the {@code doc/DEFECT-FIXES.md} C-number, quoted into every failure message
     * @param derivation the renderings the port may produce, given the value the oracle recorded;
     *                   empty when the oracle's value is not one this fix describes
     */
    public record Fix(String reference, Function<String, List<String>> derivation) {

        /**
         * The renderings the port may produce for a value the oracle recorded.
         *
         * @param oracleValue the value in the golden file
         * @return the permitted renderings, or empty when the oracle's value is undescribed
         */
        public List<String> permittedFor(final String oracleValue) {
            return derivation.apply(oracleValue);
        }
    }

    /**
     * One registered claim: a difference class, the row that authorises it, and the predicate that
     * recognises it.
     *
     * @param reference the {@code doc/DEFECT-FIXES.md} C-number, quoted into every failure message
     * @param rationale what the row says, in enough detail to read a red build without opening it
     * @param predicate whether an observed divergence carries this fix's signature
     */
    public record Claim(String reference, String rationale, Predicate<Divergence> predicate) {

        /**
         * Whether this claim explains an observed divergence.
         *
         * @param divergence the divergence
         * @return whether the predicate recognises it
         */
        public boolean explains(final Divergence divergence) {
            return predicate.test(divergence);
        }
    }

    /**
     * Something the port did that the legacy did not, or the other way about.
     *
     * <p>Two shapes, because there are two ways a port can differ: it can write a different value
     * into a document both sides produced, or it can produce a different thing altogether.
     */
    public sealed interface Divergence {

        /**
         * The recorded case the divergence was observed in.
         *
         * @return the case
         */
        RecordedCase recorded();

        /**
         * What the port did with that case's inputs.
         *
         * @return the outcome
         */
        PortOutcome port();

        /**
         * A component of the document that differs.
         *
         * @param recorded    the recorded case
         * @param port        what the port did
         * @param path        the JSON pointer of the component
         * @param oracleValue what the recording carries there, or {@code null} where it carries
         *                    nothing
         * @param portValue   what the port wrote there, or {@code null} where it wrote nothing
         */
        record Field(
                RecordedCase recorded,
                PortOutcome port,
                String path,
                JsonNode oracleValue,
                JsonNode portValue) implements Divergence {
        }

        /**
         * The run itself ended differently.
         *
         * @param recorded the recorded case
         * @param port     what the port did
         */
        record Outcome(RecordedCase recorded, PortOutcome port) implements Divergence {
        }
    }

    /** The five things the port can do with a recorded case's inputs. */
    public enum PortResult {

        /** It assembled a register the contract accepts. */
        REGISTER,

        /** It completed with one of the bounded no-register reasons. */
        NO_REGISTER,

        /** The group-proceedings policy suppressed the register before the chain ran. */
        SUPPRESSED,

        /** A stage raised a classified transformation failure. */
        FAILED,

        /** The transformation was never reached, because the inputs never arrived. */
        NOT_TRANSFORMED
    }

    /**
     * What the port did with one recorded case's inputs.
     *
     * @param result         which of the five
     * @param document       the assembled register, or {@code null}
     * @param reason         the bounded no-register reason, or {@code null}
     * @param failureCode    the bounded failure reason code, or {@code null}
     * @param failurePointer the JSON pointer a contract refusal named, or the empty string
     */
    public record PortOutcome(
            PortResult result,
            JsonNode document,
            String reason,
            String failureCode,
            String failurePointer) {

        /** The reason code a document the frozen contract refuses is classified under. */
        private static final String CONTRACT_VIOLATION = "OUTBOUND_CONTRACT_VIOLATION";

        /**
         * Whether the port completed with a particular no-register reason.
         *
         * @param expected the bounded reason
         * @return whether it did
         */
        public boolean isNoRegister(final String expected) {
            return result == PortResult.NO_REGISTER && expected.equals(reason);
        }

        /**
         * Whether the port refused a document its own contract check would not pass.
         *
         * @return whether it did
         */
        public boolean refusedByTheContract() {
            return result == PortResult.FAILED && CONTRACT_VIOLATION.equals(failureCode);
        }

        /**
         * Whether the port matched at least one subscription — which it did if it produced a
         * register, and also if the register it produced was refused by the contract, because the
         * chain assembles nothing it has not first addressed.
         *
         * @return whether it addressed somebody
         */
        public boolean addressedSomebody() {
            return result == PortResult.REGISTER || refusedByTheContract();
        }

        /**
         * Whether the group-proceedings policy suppressed the register.
         *
         * @return whether it did
         */
        public boolean suppressed() {
            return result == PortResult.SUPPRESSED;
        }

        /**
         * Whether the port reached a classified failure, or never ran the transformation because
         * the inputs it needed were never read.
         *
         * @return whether it did
         */
        public boolean classifiedOrNeverRun() {
            return result == PortResult.FAILED || result == PortResult.NOT_TRANSFORMED;
        }

        /**
         * A short rendering for a report or a failure message.
         *
         * @return what the port did, in one line
         */
        public String describe() {
            return switch (result) {
                case REGISTER -> "assembled a register";
                case NO_REGISTER -> "completed with no register: " + reason;
                case SUPPRESSED -> "suppressed the register as group proceedings";
                case FAILED -> "failed: " + failureCode
                        + (failurePointer.isEmpty() ? "" : " at " + failurePointer);
                case NOT_TRANSFORMED -> "never ran the transformation: " + reason;
            };
        }

        /**
         * The outcome of a case whose inputs the port never received.
         *
         * @param why why they never arrived
         * @return the outcome
         */
        public static PortOutcome notTransformed(final String why) {
            return new PortOutcome(PortResult.NOT_TRANSFORMED, null, why, null, "");
        }
    }

    /**
     * Every claim that explains a divergence, or nothing.
     *
     * <p>A convenience over {@link #claimedBy} for callers that have already established there is at
     * most one.
     *
     * @param divergence the observed divergence
     * @return the single claim, or empty
     */
    public static Optional<Claim> claiming(final Divergence divergence) {
        final List<Claim> claims = claimedBy(divergence);
        return claims.size() == 1 ? Optional.of(claims.get(0)) : Optional.empty();
    }
}
