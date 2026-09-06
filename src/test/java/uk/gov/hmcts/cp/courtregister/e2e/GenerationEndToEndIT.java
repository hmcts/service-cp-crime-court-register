package uk.gov.hmcts.cp.courtregister.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.adapter.publicevents.DocumentEventListener;
import uk.gov.hmcts.cp.courtregister.batch.RegisterGenerationJob;
import uk.gov.hmcts.cp.courtregister.config.JacksonConfig;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision;
import uk.gov.hmcts.cp.courtregister.domain.RunReport;
import uk.gov.hmcts.cp.courtregister.support.GeneratedRegisters;
import uk.gov.hmcts.cp.courtregister.support.GenerationStackSupport;
import uk.gov.hmcts.cp.courtregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.courtregister.support.ProcessedLogTestSupport;

/**
 * One night, from a recorded register to a generated batch, through the whole assembled service
 * (T052).
 *
 * <p>Every stage of this has its own suite and every one of them passes against a double of the
 * stage either side of it. What none of them can say is that the stages are joined: that a run reads
 * the flag from the store its settings name, writes the payload into the file service its second
 * datasource points at, sends {@code generate-document} to the endpoint it was given, and then hears
 * the answer on a durable subscription and moves the batch and its registers with it. Phase 5
 * shipped with none of that wired, and this is the test that would have said so.
 *
 * <p>Four outsides, three of them real: a Postgres for the register store, a second Postgres for the
 * framework file service seeded from the vendored schema, WireMock for App Configuration and
 * systemdocgenerator, and an in-VM Artemis carrying {@code public.event}. Only the App Configuration
 * credential is stood in for, because a federated-token exchange happens against Entra ID and not
 * against the store.
 *
 * <p>The notify leg is Phase 6's. The batch is asserted GENERATED here, which is where this
 * increment's US2 ends.
 *
 * <p><strong>An acceptance suite (tasks.md [A]).</strong> Nothing here is driven test-first: it
 * records what the assembled service does.
 */
@DisplayName("a night's generation, end to end")
class GenerationEndToEndIT {

    private static final LocalDate REGISTER_DAY = LocalDate.parse("2026-08-21");

    private static final Instant REGISTER_TIME = Instant.parse("2026-08-21T16:30:00Z");

    /** The document systemdocgenerator says it rendered, which the e-mail would attach. */
    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("2f9d4c37-8a15-4b6e-90c2-7d3f1a5b8e04");

    /** When it says it finished, with the offset the estate publishes times under. */
    private static final Instant GENERATED_AT = Instant.parse("2026-08-21T17:04:11Z");

    /** How long the outcome is given to travel the topic and reach the batch row. */
    private static final Duration DELIVERED_WITHIN = Duration.ofSeconds(30);

    private static final Duration POLL = Duration.ofMillis(200);

    /** The shared contract mapper, which is what wrote the metadata row being read back. */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /**
     * This case's registers, at a court centre nobody else holds a row for.
     *
     * <p>Minted per case, so what this suite asserts about a batch is about its own batch: the
     * shared store holds other suites' active registers and a night is entitled to batch those too.
     */
    private final GeneratedRegisters registers =
            new GeneratedRegisters(service, UUID.randomUUID());

    private static GenerationStackSupport stack;

    private static ConfigurableApplicationContext service;

    @BeforeAll
    static void startTheWholeStack() {
        PostgresTestSupport.applyFlyway();
        ProcessedLogTestSupport.dataSource();
        stack = GenerationStackSupport.start();
        service = GenerationStackSupport.startService(stack.settings());
    }

    @AfterAll
    static void stopTheWholeStack() {
        service.close();
        stack.close();
    }

    @BeforeEach
    void aNightWithOneCourtCentreWaiting() {
        stack.reset();
        stack.flagIs(true);
        registers.record(UUID.randomUUID(), REGISTER_DAY, REGISTER_TIME);
    }

    @Test
    @DisplayName("the payload is written to the file service before the render is asked for")
    void the_run_should_store_the_payload_and_ask_systemdocgenerator_to_render_it() {
        final RunReport report = run();

        assertThat(report.gateDecision()).isEqualTo(new GateDecision.Proceed(false));
        assertThat(registers.batchStatuses())
                .as("GENERATING says a render was asked for and not that a document exists")
                .containsExactly(BatchStatus.GENERATING.name());

        final UUID payloadFileId = registers.payloadFileId().orElseThrow();
        assertThat(stack.payloadStoredUnder(payloadFileId))
                .as("systemdocgenerator is given an id and nothing else, and renders whatever the "
                        + "file service holds under it; a request for an id nothing was written "
                        + "under is a render of a file that does not exist")
                .isTrue();
        final JsonNode metadata = MAPPER.readTree(stack.metadataUnder(payloadFileId));
        assertThat(metadata.path("templateName").stringValue())
                .as("progression's five keys, and this is the one that names the template the "
                        + "document is rendered from; read as a tree because how Postgres prints "
                        + "jsonb back is not this service's decision")
                .isEqualTo("OEE_Layout5");
        assertThat(metadata.path("conversionFormat").stringValue()).isEqualTo("pdf");
        assertThat(metadata.path("numberOfPages").intValue()).isEqualTo(1);
        assertThat(metadata.path("fileName").stringValue())
                .as("the file the day is rendered under, as the first register named it")
                .isEqualTo(GeneratedRegisters.fileNameFor(REGISTER_DAY));
        assertThat(requestsNaming(registers.batches().getFirst()))
                .as("one render request, correlated on the batch identity the event will answer on")
                .hasSize(1);
    }

    @Test
    @DisplayName("the document-available event moves the batch and its registers to GENERATED")
    void an_outcome_on_the_public_event_topic_should_generate_the_batch() {
        run();
        final UUID batchId = registers.batches().getFirst();
        final UUID payloadFileId = registers.payloadFileId().orElseThrow();

        publishDocumentAvailable(batchId, payloadFileId);

        await().alias("the batch reaches GENERATED off the public-event topic")
                .atMost(DELIVERED_WITHIN)
                .pollInterval(POLL)
                .until(() -> registers.batchStatuses()
                        .equals(List.of(BatchStatus.GENERATED.name())));
        assertThat(registers.documentFileId())
                .as("the rendered document's id, which is what the e-mail attaches")
                .contains(DOCUMENT_FILE_ID);
        assertThat(registers.completedBy())
                .as("the topic learned it, not the reconciler; a run whose outcomes all arrive by "
                        + "reconciliation is a subscription to investigate")
                .contains(CompletedBy.EVENT.name());
        assertThat(registers.statuses())
                .as("and the batch's own registers move with it - this batch's rows and no others, "
                        + "which is defect fix P3")
                .containsExactly(BatchStatus.GENERATED.name());
    }

    /**
     * Publishes systemdocgenerator's announcement onto the topic, as the estate publishes it.
     *
     * <p>A framework {@code JsonEnvelope} - {@code _metadata} and the event's own fields beside it -
     * with {@code CPPNAME} as the string property the broker's selector filters on, and the times
     * carrying the offset the platform sends them with rather than in UTC.
     *
     * @param batchId       the batch the render was requested for
     * @param payloadFileId the payload it was rendered from
     */
    private static void publishDocumentAvailable(final UUID batchId, final UUID payloadFileId) {
        final String body = """
                {"_metadata":{"id":"%s","name":"%s"},
                 "sourceCorrelationId":"%s",
                 "payloadFileServiceId":"%s",
                 "documentFileServiceId":"%s",
                 "templateIdentifier":"OEE_Layout5",
                 "originatingSource":"%s",
                 "generatedTime":"%s"}
                """.formatted(UUID.randomUUID(), DocumentEventListener.DOCUMENT_AVAILABLE, batchId,
                payloadFileId, DOCUMENT_FILE_ID, DocumentEventListener.ORIGINATING_SOURCE,
                OffsetDateTime.ofInstant(GENERATED_AT, ZoneOffset.UTC));

        try (JMSContext jms = service.getBean(ConnectionFactory.class).createContext()) {
            jms.createProducer()
                    .setProperty(DocumentEventListener.EVENT_NAME_PROPERTY,
                            DocumentEventListener.DOCUMENT_AVAILABLE)
                    .send(jms.createTopic("public.event"), body);
        }
    }

    /**
     * The render requests this stack received that name one batch.
     *
     * @param batchId the batch the render was requested for
     * @return the bodies naming it, in arrival order
     */
    private static List<String> requestsNaming(final UUID batchId) {
        return stack.renderRequests().stream()
                .filter(body -> body.contains(batchId.toString()))
                .toList();
    }

    /**
     * Runs the night through the bean the schedule would have fired.
     *
     * @return what the run reported
     */
    private static RunReport run() {
        return service.getBean(RegisterGenerationJob.class).run();
    }
}
