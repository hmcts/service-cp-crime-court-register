import uk.gov.justice.core.courts.CourtApplication;
import uk.gov.justice.core.courts.CourtApplicationParty;
import uk.gov.justice.core.courts.CourtApplicationType;
import uk.gov.justice.core.courts.MasterDefendant;
import uk.gov.justice.core.courts.courtRegisterDocument.CourtRegisterCaseOrApplication;
import uk.gov.justice.core.courts.courtRegisterDocument.CourtRegisterDefendant;
import uk.gov.justice.core.courts.courtRegisterDocument.CourtRegisterDocumentRequest;
import uk.gov.moj.cpp.progression.processor.CourtRegisterPdfPayloadGenerator;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;

/**
 * Records the progression goldens for specs/002-consolidate-progression-leg task T004.
 *
 * <p>Goal A runs the real uk.gov.moj.cpp.progression.processor.CourtRegisterPdfPayloadGenerator,
 * compiled from cpp-context-progression at 79edf7cf3dab8a8f4fb667bc0946f36cc4547ee6, over every
 * recorded 001 register document: once per document, and once per batch formed by grouping the
 * documents on (courtCentreId, the date part of registerDate).
 *
 * <p>Goal B runs CourtRegisterHandlerRule, which carries CourtRegisterHandler.getDefendantType and
 * CourtRegisterHandler.getCourtApplicationId verbatim, over the base hearings and over the
 * synthesised hearing variants that the base hearings cannot express.
 *
 * <p>Every golden is written pretty-printed with sorted keys, so nothing here depends on the order
 * a JSON-P implementation happens to keep.
 */
public final class GoldenRecorder {

    private static final String NEWLINE = "\n";
    private static final String INDENT = "  ";
    private static final String REQUESTS = "courtRegisterDocumentRequests";

    private final Path repo;
    private final Path recorded;
    private final Path goldens;
    private final Map<String, String> inputDigests = new TreeMap<>();

    private GoldenRecorder(final Path repo) {
        this.repo = repo;
        this.recorded = repo.resolve("src/test/resources/differential/recorded");
        this.goldens = repo.resolve("src/test/resources/goldens/progression");
    }

    public static void main(final String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: GoldenRecorder <court-register-repo-root>");
        }
        new GoldenRecorder(Path.of(args[0])).record();
    }

    private void record() throws IOException {
        final LocalDate clockDate = LocalDate.now();

        final JsonArrayBuilder pdfEntries = Json.createArrayBuilder();
        final JsonArrayBuilder batchEntries = Json.createArrayBuilder();
        final JsonArrayBuilder defendantTypeEntries = Json.createArrayBuilder();

        final int[] pdfCounts = recordPdfPayloads(pdfEntries, batchEntries);
        final int defendantTypeCount = recordDefendantTypes(defendantTypeEntries);

        final JsonObject index = Json.createObjectBuilder()
                .add("task", "T004")
                .add("progressionCommit", "79edf7cf3dab8a8f4fb667bc0946f36cc4547ee6")
                .add("ageClockDate", clockDate.toString())
                .add("corpusDigest", corpusDigest())
                .add("counts", Json.createObjectBuilder()
                        .add("pdfPayloadDocumentGoldens", pdfCounts[0])
                        .add("pdfPayloadBatchGoldens", pdfCounts[1])
                        .add("pdfPayloadRefusals", pdfCounts[2])
                        .add("defendantTypeGoldens", defendantTypeCount))
                .add("pdfPayloadDocuments", pdfEntries)
                .add("pdfPayloadBatches", batchEntries)
                .add("defendantType", defendantTypeEntries)
                .add("inputDigests", digestObject())
                .build();
        write(goldens.resolve("INDEX.json"), index);

        System.out.println("age clock date        : " + clockDate);
        System.out.println("document goldens      : " + pdfCounts[0]);
        System.out.println("batch goldens         : " + pdfCounts[1]);
        System.out.println("refused documents     : " + pdfCounts[2]);
        System.out.println("defendant-type goldens: " + defendantTypeCount);
        System.out.println("corpus digest         : " + corpusDigest());
    }

    // ---------------------------------------------------------------- goal A

    private int[] recordPdfPayloads(final JsonArrayBuilder entries, final JsonArrayBuilder batchEntries) throws IOException {
        final CourtRegisterPdfPayloadGenerator generator = new CourtRegisterPdfPayloadGenerator();
        final Path out = goldens.resolve("pdf-payload");
        final Map<String, List<String>> batches = new TreeMap<>();
        final Map<String, JsonObject> documents = new LinkedHashMap<>();

        int written = 0;
        int refused = 0;
        for (final JsonValue row : readArray(recorded.resolve("index.json"))) {
            final String caseId = ((JsonObject) row).getString("caseId");
            final String status = ((JsonObject) row).getString("contractStatus");

            for (final String variant : List.of("expected", "expected-second-delivery", "expected-alternate-clock")) {
                final Path source = recorded.resolve(caseId).resolve(variant + ".json");
                if (!Files.exists(source)) {
                    continue;
                }
                final JsonObject document = readDocument(source);
                if (document == null) {
                    continue;
                }
                final String goldenId = "expected".equals(variant) ? caseId : caseId + "__" + variant.substring("expected-".length());
                final JsonObject wrapped = Json.createObjectBuilder()
                        .add(REQUESTS, Json.createArrayBuilder().add(document))
                        .build();

                final JsonObjectBuilder entry = Json.createObjectBuilder()
                        .add("goldenId", goldenId)
                        .add("caseId", caseId)
                        .add("source", relative(source))
                        .add("contractStatus", status)
                        .add("variant", variant);
                try {
                    final JsonObject payload = generator.mapPayload(wrapped);
                    write(out.resolve(goldenId + ".json"), payload);
                    entry.add("golden", "pdf-payload/" + goldenId + ".json");
                    entry.add("cases", payload.getJsonArray("cases").size());
                    written++;
                } catch (RuntimeException refusal) {
                    entry.add("golden", JsonValue.NULL);
                    entry.add("refusal", refusal.getClass().getName() + ": " + String.valueOf(refusal.getMessage()));
                    refused++;
                }
                entries.add(entry.build());

                // Batches are what the nightly job assembles, and it assembles recorded rows. Only an
                // IN_CONTRACT document is ever recorded - a SCHEMA_INVALID one is dead-lettered before
                // the store (corpus README, contractStatus partition) - so only those are grouped here.
                if ("expected".equals(variant) && "IN_CONTRACT".equals(status)) {
                    documents.put(caseId, document);
                    batches.computeIfAbsent(batchKey(document), key -> new ArrayList<>()).add(caseId);
                }
            }
        }

        int batchGoldens = 0;
        for (final Map.Entry<String, List<String>> batch : batches.entrySet()) {
            final List<String> members = batch.getValue();
            members.sort(Comparator.naturalOrder());
            final JsonArrayBuilder requests = Json.createArrayBuilder();
            final JsonArrayBuilder memberIds = Json.createArrayBuilder();
            for (final String caseId : members) {
                requests.add(documents.get(caseId));
                memberIds.add(caseId);
            }
            final JsonObject wrapped = Json.createObjectBuilder().add(REQUESTS, requests).build();
            final String goldenId = "batch__" + batch.getKey();

            final JsonObjectBuilder entry = Json.createObjectBuilder()
                    .add("goldenId", goldenId)
                    .add("batchKey", batch.getKey())
                    .add("members", memberIds)
                    .add("memberCount", members.size());
            try {
                final JsonObject payload = generator.mapPayload(wrapped);
                write(out.resolve(goldenId + ".json"), payload);
                entry.add("golden", "pdf-payload/" + goldenId + ".json");
                entry.add("cases", payload.getJsonArray("cases").size());
                batchGoldens++;
            } catch (RuntimeException refusal) {
                entry.add("golden", JsonValue.NULL);
                entry.add("refusal", refusal.getClass().getName() + ": " + String.valueOf(refusal.getMessage()));
            }
            batchEntries.add(entry.build());
        }

        return new int[] {written, batchGoldens, refused};
    }

    private String batchKey(final JsonObject document) {
        final String courtCentreId = text(document, "courtCentreId", "no-court-centre-id");
        final String registerDate = text(document, "registerDate", "no-register-date");
        final String datePart = registerDate.length() >= 10 ? registerDate.substring(0, 10) : registerDate;
        return courtCentreId + "__" + datePart;
    }

    // ---------------------------------------------------------------- goal B

    private int recordDefendantTypes(final JsonArrayBuilder entries) throws IOException {
        final CourtRegisterHandlerRule rule = new CourtRegisterHandlerRule();
        final Path out = goldens.resolve("defendant-type");
        int written = 0;

        final Map<String, String> baseCases = new TreeMap<>();
        baseCases.put("hearing-with-address-less-youth-and-parent", "base__address-less-youth-and-parent");
        baseCases.put("hearing-with-adult-first-youth-second", "base__adult-first-youth-second");
        baseCases.put("hearing-with-complete-court-centre", "base__complete-court-centre");
        baseCases.put("hearing-with-group-proceedings", "base__group-proceedings");
        baseCases.put("hearing-with-non-prosecuting-authority-application", "base__non-prosecuting-authority-application");
        baseCases.put("hearing-with-surviving-youth-defendant", "base__surviving-youth-defendant");

        for (final Map.Entry<String, String> baseCase : baseCases.entrySet()) {
            final Path fixture = repo.resolve("src/test/resources/fixtures/base").resolve(baseCase.getKey() + ".json");
            final Path documentPath = recorded.resolve(baseCase.getValue()).resolve("expected.json");
            final JsonObject payload = readObject(fixture);
            final JsonObject hearing = payload.getJsonObject("hearing");
            final JsonObject document = readDocument(documentPath);

            if (document == null) {
                entries.add(Json.createObjectBuilder()
                        .add("goldenId", baseCase.getKey())
                        .add("golden", JsonValue.NULL)
                        .add("skipped", "the recorded case " + baseCase.getValue()
                                + " produced no document, so there is no register document to type")
                        .build());
                continue;
            }

            final JsonObject golden = resolve(rule,
                    relative(fixture),
                    relative(documentPath),
                    hearing.getJsonArray("courtApplications"),
                    document.getJsonArray("defendants"),
                    null);
            write(out.resolve(baseCase.getKey() + ".json"), golden);
            entries.add(Json.createObjectBuilder()
                    .add("goldenId", baseCase.getKey())
                    .add("golden", "defendant-type/" + baseCase.getKey() + ".json")
                    .add("kind", "base-fixture")
                    .add("defendantType", golden.get("defendantType"))
                    .build());
            written++;
        }

        final List<Path> synthetics = new ArrayList<>();
        try (var stream = Files.list(out.resolve("synthetic"))) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(synthetics::add);
        }
        synthetics.sort(Comparator.comparing(path -> path.getFileName().toString()));

        for (final Path synthetic : synthetics) {
            final JsonObject input = readObject(synthetic);
            final String name = synthetic.getFileName().toString().replace(".json", "");
            final String goldenId = "synthetic__" + name;
            final JsonObject golden = resolve(rule,
                    relative(synthetic),
                    relative(synthetic),
                    input.getJsonObject("hearing").getJsonArray("courtApplications"),
                    input.getJsonObject("document").getJsonArray("defendants"),
                    input.getString("note"));
            write(out.resolve(goldenId + ".json"), golden);
            entries.add(Json.createObjectBuilder()
                    .add("goldenId", goldenId)
                    .add("golden", "defendant-type/" + goldenId + ".json")
                    .add("kind", "synthetic")
                    .add("expected", input.getString("expected"))
                    .add("defendantType", golden.get("defendantType"))
                    .build());
            written++;
        }

        return written;
    }

    private JsonObject resolve(final CourtRegisterHandlerRule rule,
                               final String hearingFixture,
                               final String documentSource,
                               final JsonArray courtApplications,
                               final JsonArray defendants,
                               final String note) {
        final CourtRegisterDocumentRequest request = documentRequest(defendants);
        final UUID courtApplicationId = rule.courtApplicationIdOf(request);

        final JsonObjectBuilder golden = Json.createObjectBuilder()
                .add("hearingFixture", hearingFixture)
                .add("documentSource", documentSource)
                .add("courtApplicationId", courtApplicationId == null ? JsonValue.NULL : Json.createValue(courtApplicationId.toString()));
        if (note != null) {
            golden.add("note", note);
        }

        if (courtApplicationId == null) {
            // CourtRegisterHandler:84-92 - the aggregate is never read and defendantType stays StringUtils.EMPTY.
            golden.add("defendantType", "");
            golden.add("rule", "CourtRegisterHandler:84 leaves StringUtils.EMPTY when getCourtApplicationId returns null");
            return golden.build();
        }

        final CourtApplication application = courtApplication(courtApplications, courtApplicationId);
        golden.add("courtApplicationFoundOnHearing", application != null);
        try {
            golden.add("defendantType", rule.defendantTypeOf(request, application));
        } catch (RuntimeException refusal) {
            golden.add("defendantType", JsonValue.NULL);
            golden.add("threw", refusal.getClass().getName() + ": " + String.valueOf(refusal.getMessage()));
        }
        return golden.build();
    }

    private CourtRegisterDocumentRequest documentRequest(final JsonArray defendants) {
        if (defendants == null) {
            return CourtRegisterDocumentRequest.courtRegisterDocumentRequest().build();
        }
        final List<CourtRegisterDefendant> mapped = new ArrayList<>();
        for (final JsonValue value : defendants) {
            final JsonObject defendant = (JsonObject) value;
            CourtRegisterDefendant.Builder builder = CourtRegisterDefendant.courtRegisterDefendant();
            if (defendant.containsKey("masterDefendantId")) {
                builder = builder.withMasterDefendantId(UUID.fromString(defendant.getString("masterDefendantId")));
            }
            final JsonArray pcoas = defendant.getJsonArray("prosecutionCasesOrApplications");
            if (pcoas != null) {
                final List<CourtRegisterCaseOrApplication> cases = new ArrayList<>();
                for (final JsonValue pcoaValue : pcoas) {
                    final JsonObject pcoa = (JsonObject) pcoaValue;
                    CourtRegisterCaseOrApplication.Builder pcoaBuilder = CourtRegisterCaseOrApplication.courtRegisterCaseOrApplication();
                    if (pcoa.containsKey("courtApplicationId")) {
                        pcoaBuilder = pcoaBuilder.withCourtApplicationId(UUID.fromString(pcoa.getString("courtApplicationId")));
                    }
                    cases.add(pcoaBuilder.build());
                }
                builder = builder.withProsecutionCasesOrApplications(cases);
            }
            mapped.add(builder.build());
        }
        return CourtRegisterDocumentRequest.courtRegisterDocumentRequest().withDefendants(mapped).build();
    }

    private CourtApplication courtApplication(final JsonArray courtApplications, final UUID courtApplicationId) {
        if (courtApplications == null) {
            return null;
        }
        for (final JsonValue value : courtApplications) {
            final JsonObject application = (JsonObject) value;
            if (!courtApplicationId.toString().equals(text(application, "id", null))) {
                continue;
            }
            CourtApplication.Builder builder = CourtApplication.courtApplication();

            final JsonObject type = application.getJsonObject("type");
            if (type != null) {
                CourtApplicationType.Builder typeBuilder = CourtApplicationType.courtApplicationType();
                if (type.containsKey("appealFlag")) {
                    typeBuilder = typeBuilder.withAppealFlag(type.getBoolean("appealFlag"));
                }
                if (type.containsKey("applicantAppellantFlag")) {
                    typeBuilder = typeBuilder.withApplicantAppellantFlag(type.getBoolean("applicantAppellantFlag"));
                }
                builder = builder.withType(typeBuilder.build());
            }

            final JsonObject applicant = application.getJsonObject("applicant");
            if (applicant != null) {
                builder = builder.withApplicant(party(applicant));
            }

            final JsonArray respondents = application.getJsonArray("respondents");
            if (respondents != null) {
                final List<CourtApplicationParty> parties = new ArrayList<>();
                for (final JsonValue respondent : respondents) {
                    parties.add(party((JsonObject) respondent));
                }
                builder = builder.withRespondents(parties);
            }

            return builder.build();
        }
        return null;
    }

    private CourtApplicationParty party(final JsonObject partyJson) {
        CourtApplicationParty.Builder builder = CourtApplicationParty.courtApplicationParty();
        final JsonObject masterDefendant = partyJson.getJsonObject("masterDefendant");
        if (masterDefendant != null) {
            MasterDefendant.Builder master = MasterDefendant.masterDefendant();
            if (masterDefendant.containsKey("masterDefendantId")) {
                master = master.withMasterDefendantId(UUID.fromString(masterDefendant.getString("masterDefendantId")));
            }
            builder = builder.withMasterDefendant(master.build());
        }
        return builder.build();
    }

    // ------------------------------------------------------------ json plumbing

    private JsonObject readDocument(final Path path) throws IOException {
        final String body = Files.readString(path, StandardCharsets.UTF_8);
        digest(path, body);
        if ("null".equals(body.trim())) {
            return null;
        }
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            return reader.readObject();
        }
    }

    private JsonObject readObject(final Path path) throws IOException {
        final String body = Files.readString(path, StandardCharsets.UTF_8);
        digest(path, body);
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            return reader.readObject();
        }
    }

    private JsonArray readArray(final Path path) throws IOException {
        final String body = Files.readString(path, StandardCharsets.UTF_8);
        digest(path, body);
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            return reader.readArray();
        }
    }

    private void digest(final Path path, final String body) {
        inputDigests.put(relative(path), sha256(body.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonObjectBuilder digestObject() {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        inputDigests.forEach(builder::add);
        return builder;
    }

    private String corpusDigest() {
        final StringBuilder manifest = new StringBuilder();
        inputDigests.forEach((path, sha) -> manifest.append(sha).append("  ").append(path).append(NEWLINE));
        return sha256(manifest.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String relative(final Path path) {
        return repo.relativize(path).toString();
    }

    private String text(final JsonObject object, final String key, final String fallback) {
        final JsonValue value = object.get(key);
        if (value instanceof JsonString) {
            return ((JsonString) value).getString();
        }
        return fallback;
    }

    private static String sha256(final byte[] bytes) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            final StringBuilder hex = new StringBuilder(new BigInteger(1, hash).toString(16));
            while (hex.length() < 64) {
                hex.insert(0, '0');
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void write(final Path path, final JsonValue value) throws IOException {
        Files.createDirectories(path.getParent());
        final StringBuilder out = new StringBuilder();
        canonical(value, out, "");
        out.append(NEWLINE);
        Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
    }

    private void canonical(final JsonValue value, final StringBuilder out, final String indent) {
        switch (value.getValueType()) {
            case OBJECT -> canonicalObject((JsonObject) value, out, indent);
            case ARRAY -> canonicalArray((JsonArray) value, out, indent);
            case STRING -> quote(((JsonString) value).getString(), out);
            default -> out.append(value.toString());
        }
    }

    private void canonicalObject(final JsonObject object, final StringBuilder out, final String indent) {
        if (object.isEmpty()) {
            out.append("{}");
            return;
        }
        final String inner = indent + INDENT;
        out.append('{').append(NEWLINE);
        final List<String> keys = new ArrayList<>(object.keySet());
        keys.sort(Comparator.naturalOrder());
        for (int i = 0; i < keys.size(); i++) {
            out.append(inner);
            quote(keys.get(i), out);
            out.append(": ");
            canonical(object.get(keys.get(i)), out, inner);
            if (i < keys.size() - 1) {
                out.append(',');
            }
            out.append(NEWLINE);
        }
        out.append(indent).append('}');
    }

    private void canonicalArray(final JsonArray array, final StringBuilder out, final String indent) {
        if (array.isEmpty()) {
            out.append("[]");
            return;
        }
        final String inner = indent + INDENT;
        out.append('[').append(NEWLINE);
        for (int i = 0; i < array.size(); i++) {
            out.append(inner);
            canonical(array.get(i), out, inner);
            if (i < array.size() - 1) {
                out.append(',');
            }
            out.append(NEWLINE);
        }
        out.append(indent).append(']');
    }

    private void quote(final String text, final StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
