package uk.gov.hmcts.cp.courtregister.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.adapter.notificationnotify.NotificationNotifyClient;
import uk.gov.hmcts.cp.courtregister.adapter.publicevents.DeliveryObserver;
import uk.gov.hmcts.cp.courtregister.adapter.publicevents.DocumentEventListener;
import uk.gov.hmcts.cp.courtregister.adapter.systemdocgenerator.SystemDocGeneratorClient;
import uk.gov.hmcts.cp.courtregister.application.DocumentOutcomeSinkImpl;
import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.application.PayloadFileStore;
import uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService;
import uk.gov.hmcts.cp.courtregister.application.RegisterNotifierService;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.batch.BatchAssembler;
import uk.gov.hmcts.cp.courtregister.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.courtregister.batch.GenerationReconciler;
import uk.gov.hmcts.cp.courtregister.batch.RegisterGenerationJob;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
import uk.gov.hmcts.cp.courtregister.config.GenerationProperties;
import uk.gov.hmcts.cp.courtregister.config.JacksonConfig;
import uk.gov.hmcts.cp.courtregister.domain.AssembledBatch;
import uk.gov.hmcts.cp.courtregister.domain.BatchAssembly;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;
import uk.gov.hmcts.cp.courtregister.domain.CourtCentreDay;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterCaseOrApplication;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.courtregister.domain.Deadline;
import uk.gov.hmcts.cp.courtregister.domain.FlagDecision;
import uk.gov.hmcts.cp.courtregister.domain.GateDecision;
import uk.gov.hmcts.cp.courtregister.domain.NotificationStatus;
import uk.gov.hmcts.cp.courtregister.domain.PayloadStoreUnavailableException;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterNotification;
import uk.gov.hmcts.cp.courtregister.domain.RegisterRecord;
import uk.gov.hmcts.cp.courtregister.domain.StoreRefusedRowException;
import uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.courtregister.persistence.NotificationClaim;
import uk.gov.hmcts.cp.courtregister.persistence.NotificationSettlement;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterNotificationRepository;

/**
 * A night's registers driven end to end, over a batch and a recipient made entirely of markers.
 *
 * <p>The subject of {@code config/TelemetryPrivacyTest}'s downstream group: everything that happens
 * to a register after the delivery path has recorded it. A batch is assembled, a payload is stored,
 * systemdocgenerator is asked for the render, the outcome comes back by topic or by query, and the
 * document is e-mailed to the Youth Offending Teams the subscription matched - and every one of
 * those steps writes lines and moves meters. What this fixture exists to do is make <em>every</em>
 * one of those lines happen once, with a marker wherever a person could be named, so a sweep over
 * the capture is a sweep over the whole leg rather than over a sample of it.
 *
 * <p><strong>Three doubles, and they are the three that write nothing.</strong> The register store
 * and its two repositories are mocks, because a database is the one outside a unit suite cannot
 * have and because none of the three writes a log line of its own. Everything above them is real:
 * the payload mapper that turns a batch into a render payload, the client that asks
 * systemdocgenerator, the client that asks notificationnotify, the topic listener, the outcome
 * sink, the reconciler and the nightly run. The two HTTP clients matter most - they are the classes
 * with a recipient's address in their hands and a far end's status line in their exceptions - so
 * they run over a real socket against a real server, and every answer below is one
 * systemdocgenerator or notificationnotify really could give.
 *
 * <p>The markers themselves are {@link PersonalDataMarkers}', because the container suite sweeps
 * for the same values through the same leg: two lists of markers is how one suite comes to look for
 * a field the other has stopped setting, with both of them green.
 *
 * <p>It is a wide class, and the width is the subject rather than a smell: a claim about every
 * line the leg can write cannot be made from a narrower graph, and one method per arrangement is
 * what keeps which arrangement produces which line legible.
 *
 * <p><strong>The drive is about what was written down, not about what was answered.</strong> Most
 * of the scenarios below are failures, and several of them end in a refusal reaching the caller -
 * that is what makes the line happen. Each is therefore run through
 * {@link #whateverItAnswers(Runnable)}, which is not a swallowed failure: what those calls answer
 * is asserted by the eight suites that own those classes, and this one asserts what they said out
 * loud on the way.
 */
public final class GenerationLegs implements AutoCloseable {

    /** The batch every scenario is about, fixed so a suite can look for it in the capture. */
    public static final UUID BATCH_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");

    /** The identity one recipient's e-mail is asked for under, fixed for the same reason. */
    public static final UUID NOTIFICATION_ID =
            UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa");

    /** The classes that write a line between a recorded register and the e-mail about it. */
    public static final List<Class<?>> THE_LEGS = List.of(
            RegisterGenerationJob.class,
            RegisterGenerationService.class,
            SystemDocGeneratorClient.class,
            GenerationReconciler.class,
            DocumentEventListener.class,
            DocumentOutcomeSinkImpl.class,
            RegisterNotifierService.class,
            NotificationNotifyClient.class);

    /** Everything a meter's name or label may never carry, whoever it describes. */
    public static final List<String> NOTHING_A_SERIES_MAY_CARRY = List.of(
            PersonalDataMarkers.RECIPIENT_EMAIL,
            PersonalDataMarkers.RECIPIENT_ORGANISATION,
            PersonalDataMarkers.GENERATOR_REASON);

    private static final UUID PAYLOAD_FILE_ID =
            UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff");

    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("0a1b2c3d-4e5f-4a6b-8c9d-1e2f3a4b5c6d");

    private static final UUID TEMPLATE_ID =
            UUID.fromString("7c1d9f42-3b8a-4e15-9c60-2d5f8a1b4e37");

    private static final UUID COURT_CENTRE =
            UUID.fromString("2f6b8d10-4a3c-4e57-9b21-8c0d5e7f1a94");

    private static final String OU_CODE = "B01LY00";

    private static final String COURT_HOUSE = "Lavender Hill Youth Court";

    private static final LocalDate REGISTER_DATE = LocalDate.of(2026, 3, 2);

    private static final String FILE_NAME = "CourtRegister_B01LY00_20260302.pdf";

    private static final String SYSTEM_USER_ID = "b2f8a1c4-6d3e-4f57-9a80-1c5b7e9d2f46";

    private static final Instant AT = Instant.parse("2026-03-02T18:04:00Z");

    /** The run's budget, long enough that only the scenario that shortens it overruns. */
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(60);

    /** Attempts and back-off small enough that a retry loop finishes inside a unit suite. */
    private static final int MAX_ATTEMPTS = 2;

    private static final Duration BACKOFF = Duration.ofMillis(1);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private static final Duration GRACE_PERIOD = Duration.ofMinutes(10);

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /** Any generate-document command, which is one path and takes no parameter. */
    private static final String ANY_RENDER_COMMAND = SystemDocGeneratorClient.COMMAND_PATH;

    /** Any document query, whatever payload is asked about. */
    private static final String ANY_DOCUMENT_QUERY = SystemDocGeneratorClient.QUERY_PATH
            .replace("{payloadFileId}", "[^/]+");

    /** Any send-email-notification, whatever identity it was made under. */
    private static final String ANY_EMAIL_COMMAND = NotificationNotifyClient.COMMAND_PATH
            .replace("{notificationId}", "[^/]+");

    private final WireMockServer contexts;

    private final GenerationMetrics metrics;

    private final AdjustableClock clock = AdjustableClock.startingAt(AT);

    private final RegisterStore store = mock(RegisterStore.class);

    private final RegisterBatchRepository batches = mock(RegisterBatchRepository.class);

    private final RegisterNotificationRepository notifications =
            mock(RegisterNotificationRepository.class);

    private final PayloadFileStore payloadFileStore = mock(PayloadFileStore.class);

    private final BatchAssembler assembler = mock(BatchAssembler.class);

    private final FeatureFlagGate gate = mock(FeatureFlagGate.class);

    private final SystemDocGeneratorClient renderer;

    private final NotificationNotifyClient notifier;

    private final RegisterGenerationService generation;

    private final RegisterNotifierService notifying;

    private final GenerationReconciler reconciler;

    private final DocumentOutcomeSinkImpl sink;

    private final DocumentEventListener listener;

    private final RegisterGenerationJob job;

    private GenerationLegs(final WireMockServer wireMock, final MeterRegistry registry) {
        this.contexts = wireMock;
        this.metrics = new GenerationMetrics(registry);
        this.renderer = new SystemDocGeneratorClient(
                restClientFor(wireMock.baseUrl()), SYSTEM_USER_ID, MAPPER);
        this.notifier = new NotificationNotifyClient(
                restClientFor(wireMock.baseUrl()), SYSTEM_USER_ID, MAPPER);
        this.generation = new RegisterGenerationService(store,
                new uk.gov.hmcts.cp.courtregister.pipeline.PdfPayloadMapper(clock),
                payloadFileStore, renderer, MAPPER, retryPolicy(), waited -> {}, metrics, clock);
        this.notifying = new RegisterNotifierService(store, batches, notifications, notifier,
                metrics, TEMPLATE_ID, retryPolicy(), waited -> {}, clock);
        this.reconciler = new GenerationReconciler(batches, renderer, sinkOf(), store, metrics,
                GRACE_PERIOD, clock);
        this.sink = sinkOf();
        this.listener = new DocumentEventListener(sink, metrics, DeliveryObserver.NONE);
        this.job = new RegisterGenerationJob(gate, store, assembler, generation, reconciler,
                metrics, settings(), clock);
    }

    /**
     * Starts the two contexts and assembles the leg over them.
     *
     * @param registry the registry the leg's meters are registered against and read back off
     * @return the leg, to be closed when the drive is done
     */
    public static GenerationLegs overMarkedRecipients(final MeterRegistry registry) {
        final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        return new GenerationLegs(wireMock, registry);
    }

    @Override
    public void close() {
        contexts.stop();
    }

    /**
     * Makes every line the leg can write happen once.
     *
     * <p>Grouped by the class each arrangement is about, and every group is one arrangement per
     * line rather than one per interesting behaviour: the claim the capture is swept for is about
     * the lines, so a branch nothing here reaches is a line outside it.
     */
    public void driveEverything() {
        theNightlyRun();
        theRequestingLeg();
        theRenderersClient();
        theReconciler();
        theTopicListener();
        theOutcomeSink();
        theNotifyingLeg();
        theNotifiersClient();
    }

    // --- the nightly run -------------------------------------------------------------------------

    private void theNightlyRun() {
        aNightTheOneLeverStopped();

        readyToGenerate();
        when(store.assemble(any(RegisterBatch.class), anyList()))
                .thenThrow(new StoreUnavailableException(
                        "the store could not be reached to stamp a batch onto its registers",
                        new IllegalStateException("the connection pool is empty")));
        whateverItAnswers(job::run);

        readyToGenerate();
        when(store.assemble(any(RegisterBatch.class), anyList())).thenReturn(pending());
        renderCommandAnswering(HttpStatus.ACCEPTED.value());
        whateverItAnswers(job::run);

        aNightThatStoppedPartWay();
    }

    /**
     * The night the store went away before the run could read what was waiting.
     *
     * <p>The one line the run writes about itself rather than about a batch: a run that stopped
     * part way still reports how far it got, and what stopped it is named beside that report. The
     * store's refusal carries its own words and the class of the cause, which is what the sweep is
     * here to hold to counts and bounded codes.
     */
    private void aNightThatStoppedPartWay() {
        readyToGenerate();
        when(store.activeUnbatched()).thenThrow(new StoreUnavailableException(
                "the store could not be reached to read what is waiting to be batched",
                new IllegalStateException("the connection pool is empty")));
        whateverItAnswers(job::run);
    }

    /**
     * The night the flag said the legacy generates, over the real gate rather than a doubled one.
     *
     * <p>The gate is the one class of the leg that counts a run without writing a line about it,
     * and its label is a bounded code {@code FlagDecision} owns. It is real here for that reason:
     * a doubled gate leaves {@code courtregister_generation_skipped_total} with no series and its
     * label outside the sweep.
     */
    private void aNightTheOneLeverStopped() {
        reset(store, batches, assembler);
        final FeatureFlagReader reader = mock(FeatureFlagReader.class);
        when(reader.read()).thenReturn(new FlagDecision.Disabled());
        whateverItAnswers(new RegisterGenerationJob(new FeatureFlagGate(reader, metrics), store,
                assembler, generation, reconciler, metrics, settings(), clock)::run);
    }

    // --- the requesting leg ----------------------------------------------------------------------

    private void theRequestingLeg() {
        aBatchThatHoldsNoRegisters();
        aPayloadTheFileServiceWouldNotTake();
        aRenderTheGeneratorRefusedOutright();
        aRenderNothingAnswered();
        aRenderThatWouldNotFitTheBudget();
        aRenderTheGeneratorAccepted();
        aWaitBetweenAttemptsThatWasInterrupted();
    }

    private void aBatchThatHoldsNoRegisters() {
        reset(store);
        when(store.batched(BATCH_ID)).thenReturn(List.of());
        whateverItAnswers(() -> generation.request(pending(), deadline()));
    }

    private void aPayloadTheFileServiceWouldNotTake() {
        aBatchOfOneRegister();
        payloadStoreRefusing();
        whateverItAnswers(() -> generation.request(pending(), deadline()));
    }

    private void aRenderTheGeneratorRefusedOutright() {
        aBatchOfOneRegister();
        renderCommandAnswering(HttpStatus.BAD_REQUEST.value());
        whateverItAnswers(() -> generation.request(pending(), deadline()));
    }

    private void aRenderNothingAnswered() {
        aBatchOfOneRegister();
        renderCommandFaulting();
        whateverItAnswers(() -> generation.request(pending(), deadline()));
    }

    private void aRenderThatWouldNotFitTheBudget() {
        aBatchOfOneRegister();
        renderCommandAnswering(HttpStatus.SERVICE_UNAVAILABLE.value());
        whateverItAnswers(() -> generation.request(pending(), new Deadline(clock.instant())));
    }

    private void aRenderTheGeneratorAccepted() {
        aBatchOfOneRegister();
        renderCommandAnswering(HttpStatus.ACCEPTED.value());
        whateverItAnswers(() -> generation.request(pending(), deadline()));
    }

    private void aWaitBetweenAttemptsThatWasInterrupted() {
        aBatchOfOneRegister();
        renderCommandAnswering(HttpStatus.SERVICE_UNAVAILABLE.value());
        final RegisterGenerationService interruptible = new RegisterGenerationService(store,
                new uk.gov.hmcts.cp.courtregister.pipeline.PdfPayloadMapper(clock),
                payloadFileStore, renderer, MAPPER, retryPolicy(), waited -> {
                    throw new InterruptedException("the run's thread was asked to stop");
                }, metrics, clock);
        whateverItAnswers(() -> interruptible.request(pending(), deadline()));
        Thread.interrupted();
    }

    // --- the renderer's client -------------------------------------------------------------------

    private void theRenderersClient() {
        renderCommandAnswering(HttpStatus.OK.value());
        whateverItAnswers(this::askForARender);

        renderCommandAnswering(HttpStatus.SERVICE_UNAVAILABLE.value());
        whateverItAnswers(this::askForARender);

        queryAnswering(HttpStatus.NOT_FOUND.value(), "");
        whateverItAnswers(this::askAboutTheDocument);

        queryAnswering(HttpStatus.SERVICE_UNAVAILABLE.value(), "");
        whateverItAnswers(this::askAboutTheDocument);

        queryAnswering(HttpStatus.BAD_REQUEST.value(), "");
        whateverItAnswers(this::askAboutTheDocument);

        queryAnswering(HttpStatus.OK.value(), "{ this is not an answer");
        whateverItAnswers(this::askAboutTheDocument);

        queryAnswering(HttpStatus.OK.value(), refusedDocument());
        whateverItAnswers(this::askAboutTheDocument);

        queryFaulting();
        whateverItAnswers(this::askAboutTheDocument);
    }

    // --- the reconciler --------------------------------------------------------------------------

    private void theReconciler() {
        aBatchNobodyHasBeenToldAbout();
        aRenderTheGeneratorHasFinished();
        aRenderTheGeneratorRefused();
        aRenderTheGeneratorHasNoVerdictFor();
        aRendererThatWouldNotAnswerTheQuery();
    }

    private void aBatchNobodyHasBeenToldAbout() {
        reset(batches);
        when(batches.generatingSince(any(Instant.class))).thenReturn(List.of());
        when(batches.pendingSince(any(Instant.class))).thenReturn(List.of());
        when(batches.generatedSince(any(Instant.class)))
                .thenReturn(List.of(batch(BatchStatus.GENERATED, null, null)));
        whateverItAnswers(reconciler::reconcile);
    }

    private void aRenderTheGeneratorHasFinished() {
        reconcilingOne();
        queryAnswering(HttpStatus.OK.value(), generatedDocument());
        whateverItAnswers(reconciler::reconcile);
    }

    private void aRenderTheGeneratorRefused() {
        reconcilingOne();
        queryAnswering(HttpStatus.OK.value(), refusedDocument());
        whateverItAnswers(reconciler::reconcile);
    }

    private void aRenderTheGeneratorHasNoVerdictFor() {
        reconcilingOne();
        queryAnswering(HttpStatus.OK.value(), "{}");
        whateverItAnswers(reconciler::reconcile);
    }

    private void aRendererThatWouldNotAnswerTheQuery() {
        reconcilingOne();
        queryFaulting();
        whateverItAnswers(reconciler::reconcile);
    }

    // --- the topic listener ----------------------------------------------------------------------

    private void theTopicListener() {
        whateverItAnswers(() -> listener.onPublicEvent(unreadableMessage()));
        deliver("public.somebodyelse.events.something-else", "{}");
        deliver(DocumentEventListener.GENERATION_FAILED, "{ not an envelope");
        deliver(DocumentEventListener.GENERATION_FAILED,
                envelope(DocumentEventListener.DOCUMENT_AVAILABLE, generationFailedPayload()));
        deliver(DocumentEventListener.DOCUMENT_AVAILABLE, envelope(
                DocumentEventListener.DOCUMENT_AVAILABLE,
                """
                  "originatingSource": "COURT_REGISTER"
                """));
        deliver(DocumentEventListener.DOCUMENT_AVAILABLE, envelope(
                DocumentEventListener.DOCUMENT_AVAILABLE,
                """
                  "originatingSource": "%s"
                """.formatted(DocumentEventListener.ORIGINATING_SOURCE)));
        deliver(DocumentEventListener.DOCUMENT_AVAILABLE, envelope(
                DocumentEventListener.DOCUMENT_AVAILABLE,
                """
                  "sourceCorrelationId": "%s",
                  "originatingSource": "%s"
                """.formatted(BATCH_ID, DocumentEventListener.ORIGINATING_SOURCE)));
        deliver(DocumentEventListener.DOCUMENT_AVAILABLE, envelope(
                DocumentEventListener.DOCUMENT_AVAILABLE,
                """
                  "sourceCorrelationId": "%s",
                  "payloadFileServiceId": "%s",
                  "originatingSource": "%s"
                """.formatted(BATCH_ID, PAYLOAD_FILE_ID,
                        DocumentEventListener.ORIGINATING_SOURCE)));
        deliver(DocumentEventListener.GENERATION_FAILED, envelope(
                DocumentEventListener.GENERATION_FAILED,
                """
                  "sourceCorrelationId": "%s",
                  "payloadFileServiceId": "%s",
                  "reason": "%s",
                  "originatingSource": "%s"
                """.formatted(BATCH_ID, PAYLOAD_FILE_ID, PersonalDataMarkers.GENERATOR_REASON,
                        DocumentEventListener.ORIGINATING_SOURCE)));
        deliver(DocumentEventListener.DOCUMENT_AVAILABLE, envelope(
                DocumentEventListener.DOCUMENT_AVAILABLE,
                """
                  "sourceCorrelationId": "%s",
                  "payloadFileServiceId": "not an identity",
                  "originatingSource": "%s"
                """.formatted(BATCH_ID, DocumentEventListener.ORIGINATING_SOURCE)));
        deliver(DocumentEventListener.DOCUMENT_AVAILABLE, envelope(
                DocumentEventListener.DOCUMENT_AVAILABLE,
                """
                  "sourceCorrelationId": "%s",
                  "payloadFileServiceId": "%s",
                  "documentFileServiceId": "%s",
                  "generatedTime": "not a date-time",
                  "originatingSource": "%s"
                """.formatted(BATCH_ID, PAYLOAD_FILE_ID, DOCUMENT_FILE_ID,
                        DocumentEventListener.ORIGINATING_SOURCE)));

        reset(batches);
        when(batches.findById(BATCH_ID)).thenThrow(new StoreUnavailableException(
                "the store could not be reached to read a batch",
                new IllegalStateException("the connection pool is empty")));
        deliver(DocumentEventListener.GENERATION_FAILED, envelope(
                DocumentEventListener.GENERATION_FAILED,
                """
                  "sourceCorrelationId": "%s",
                  "payloadFileServiceId": "%s",
                  "failedTime": "2026-03-02T18:06:23.004+00:00",
                  "reason": "%s",
                  "originatingSource": "%s"
                """.formatted(BATCH_ID, PAYLOAD_FILE_ID, PersonalDataMarkers.GENERATOR_REASON,
                        DocumentEventListener.ORIGINATING_SOURCE)));
    }

    // --- the outcome sink ------------------------------------------------------------------------

    private void theOutcomeSink() {
        anOutcomeForABatchNothingHolds();
        anOutcomeAboutAnotherPayload();
        anOutcomeThatArrivedTwice();
        anOutcomeTheStateMachineDoesNotDraw();
        anOutcomeThatClosesTheRoundTrip();
    }

    private void anOutcomeForABatchNothingHolds() {
        reset(batches);
        when(batches.findById(BATCH_ID)).thenReturn(Optional.empty());
        whateverItAnswers(() -> sink.generationFailed(BATCH_ID, PAYLOAD_FILE_ID,
                PersonalDataMarkers.GENERATOR_REASON, AT, CompletedBy.EVENT));
    }

    private void anOutcomeAboutAnotherPayload() {
        holding(batch(BatchStatus.GENERATING, PAYLOAD_FILE_ID, null));
        whateverItAnswers(() -> sink.documentAvailable(BATCH_ID, DOCUMENT_FILE_ID, DOCUMENT_FILE_ID,
                AT, CompletedBy.EVENT));
    }

    private void anOutcomeThatArrivedTwice() {
        holding(batch(BatchStatus.FAILED, PAYLOAD_FILE_ID, null));
        whateverItAnswers(() -> sink.generationFailed(BATCH_ID, PAYLOAD_FILE_ID,
                PersonalDataMarkers.GENERATOR_REASON, AT, CompletedBy.EVENT));
    }

    private void anOutcomeTheStateMachineDoesNotDraw() {
        holding(batch(BatchStatus.NOTIFIED, PAYLOAD_FILE_ID, DOCUMENT_FILE_ID));
        whateverItAnswers(() -> sink.generationFailed(BATCH_ID, PAYLOAD_FILE_ID,
                PersonalDataMarkers.GENERATOR_REASON, AT, CompletedBy.EVENT));
    }

    /**
     * The outcome that settles a batch, which is the one that closes a render's round trip.
     *
     * <p>Here so that {@code courtregister_generation_latency} has a series for the label sweep to
     * pass over: every other arrangement in this group is an outcome that moves nothing, and a
     * timer nothing recorded is a timer whose labels nothing swept. A refusal rather than a
     * document, because a document hands the batch on to the notifying leg and that leg has its own
     * group below; what is wanted here is the mark and the reading, and nothing after them.
     *
     * <p>The row is answered twice over because the sink reads it twice: GENERATING to decide what
     * the outcome means, and then back again for the two instants the reading is made of.
     */
    private void anOutcomeThatClosesTheRoundTrip() {
        reset(batches, store);
        when(batches.findById(BATCH_ID))
                .thenReturn(Optional.of(batch(BatchStatus.GENERATING, PAYLOAD_FILE_ID, null)))
                .thenReturn(Optional.of(refused()));
        whateverItAnswers(() -> sink.generationFailed(BATCH_ID, PAYLOAD_FILE_ID,
                PersonalDataMarkers.GENERATOR_REASON, AT, CompletedBy.EVENT));
    }

    // --- the notifying leg -----------------------------------------------------------------------

    private void theNotifyingLeg() {
        aBatchSomebodyElseIsTelling();
        aBatchWithNobodyToTell();
        aRecipientWhoseEmailWasAccepted();
        aRecipientWhoseEmailWasRefused();
        aRowAnotherMechanismHadAlreadyMinted();
        aSettlementThatArrivedTooLate();
        aRowTheStoreNoLongerHolds();
        aClaimThatWasTakenOverMidCycle();
        aTallyTheStateMachineDoesNotDraw();
        aBatchThatAlreadyStandsWhereItsTallyPutsIt();
        aWaitBeforeAnotherPostThatWasInterrupted();
    }

    private void aBatchSomebodyElseIsTelling() {
        notifyingOne(NotificationClaim.ALREADY_CLAIMED, BatchStatus.GENERATED);
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aBatchWithNobodyToTell() {
        notifyingOne(NotificationClaim.CLAIMED, BatchStatus.GENERATED);
        when(store.batched(BATCH_ID)).thenReturn(List.of(register(null)));
        when(notifications.findByBatchId(BATCH_ID)).thenReturn(List.of());
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aRecipientWhoseEmailWasAccepted() {
        oneRecipientOwedAnEmail(NotificationStatus.PENDING);
        emailCommandAnswering(HttpStatus.ACCEPTED.value());
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aRecipientWhoseEmailWasRefused() {
        oneRecipientOwedAnEmail(NotificationStatus.PENDING);
        emailCommandAnswering(HttpStatus.SERVICE_UNAVAILABLE.value());
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aRowAnotherMechanismHadAlreadyMinted() {
        notifyingOne(NotificationClaim.CLAIMED, BatchStatus.GENERATED);
        when(store.batched(BATCH_ID)).thenReturn(List.of(register(List.of(recipient()))));
        when(notifications.findByBatchId(BATCH_ID))
                .thenReturn(List.of())
                .thenReturn(List.of(row(NotificationStatus.PENDING)));
        doRefuseTheInsert();
        emailCommandAnswering(HttpStatus.ACCEPTED.value());
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aSettlementThatArrivedTooLate() {
        oneRecipientOwedAnEmail(NotificationStatus.PENDING);
        when(notifications.update(any(RegisterNotification.class), anyInt()))
                .thenReturn(NotificationSettlement.ATTEMPTS_ONLY);
        emailCommandAnswering(HttpStatus.ACCEPTED.value());
        whateverItAnswers(() -> notifying.notify(BATCH_ID));

        oneRecipientOwedAnEmail(NotificationStatus.PENDING);
        when(notifications.update(any(RegisterNotification.class), anyInt()))
                .thenReturn(NotificationSettlement.ATTEMPTS_ONLY);
        emailCommandAnswering(HttpStatus.BAD_REQUEST.value());
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aRowTheStoreNoLongerHolds() {
        oneRecipientOwedAnEmail(NotificationStatus.PENDING);
        when(notifications.update(any(RegisterNotification.class), anyInt()))
                .thenReturn(NotificationSettlement.ABSENT);
        emailCommandAnswering(HttpStatus.ACCEPTED.value());
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aClaimThatWasTakenOverMidCycle() {
        oneRecipientOwedAnEmail(NotificationStatus.PENDING);
        when(batches.renewNotificationClaim(any(UUID.class), any(UUID.class))).thenReturn(false);
        when(batches.releaseNotificationClaim(any(UUID.class), any(UUID.class)))
                .thenReturn(false);
        when(notifications.tallyAttempts(any(UUID.class), anyInt())).thenReturn(false);
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aTallyTheStateMachineDoesNotDraw() {
        notifyingOne(NotificationClaim.CLAIMED, BatchStatus.NOTIFIED);
        when(store.batched(BATCH_ID)).thenReturn(List.of(register(null)));
        when(notifications.findByBatchId(BATCH_ID))
                .thenReturn(List.of(row(NotificationStatus.FAILED)));
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aBatchThatAlreadyStandsWhereItsTallyPutsIt() {
        notifyingOne(NotificationClaim.CLAIMED, BatchStatus.NOTIFIED_NOBODY);
        when(store.batched(BATCH_ID)).thenReturn(List.of(register(null)));
        when(notifications.findByBatchId(BATCH_ID)).thenReturn(List.of());
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aWaitBeforeAnotherPostThatWasInterrupted() {
        oneRecipientOwedAnEmail(NotificationStatus.PENDING);
        emailCommandAnswering(HttpStatus.SERVICE_UNAVAILABLE.value());
        final RegisterNotifierService interruptible = new RegisterNotifierService(store, batches,
                notifications, notifier, metrics, TEMPLATE_ID, retryPolicy(), waited -> {
                    throw new InterruptedException("the run's thread was asked to stop");
                }, clock);
        whateverItAnswers(() -> interruptible.notify(BATCH_ID));
        Thread.interrupted();
    }

    // --- the notifier's client -------------------------------------------------------------------

    private void theNotifiersClient() {
        emailCommandAnswering(HttpStatus.OK.value());
        whateverItAnswers(this::askForAnEmail);

        emailCommandFaulting();
        whateverItAnswers(this::askForAnEmail);
    }

    // --- arrangements ----------------------------------------------------------------------------

    private void readyToGenerate() {
        reset(store, batches, assembler, gate, payloadFileStore);
        when(gate.decide(false)).thenReturn(new GateDecision.Proceed(false));
        when(store.activeUnbatched()).thenReturn(List.of(register(List.of(recipient()))));
        when(store.batched(BATCH_ID)).thenReturn(List.of(register(List.of(recipient()))));
        when(assembler.assemble(anyList(), anyList(), anyBoolean())).thenReturn(new BatchAssembly(
                List.of(new AssembledBatch(pending(), List.of(register(List.of(recipient()))))),
                List.of(new CourtCentreDay(COURT_CENTRE, REGISTER_DATE))));
        when(batches.generatingSince(any(Instant.class))).thenReturn(List.of());
        when(batches.pendingSince(any(Instant.class))).thenReturn(List.of());
        when(batches.generatedSince(any(Instant.class))).thenReturn(List.of());
    }

    private void aBatchOfOneRegister() {
        reset(store, payloadFileStore);
        when(store.batched(BATCH_ID)).thenReturn(List.of(register(List.of(recipient()))));
    }

    private void payloadStoreRefusing() {
        doRefuseThePayload();
    }

    private void reconcilingOne() {
        reset(batches, store);
        when(batches.generatingSince(any(Instant.class)))
                .thenReturn(List.of(batch(BatchStatus.GENERATING, PAYLOAD_FILE_ID, null)));
        when(batches.pendingSince(any(Instant.class))).thenReturn(List.of());
        when(batches.generatedSince(any(Instant.class))).thenReturn(List.of());
        when(batches.findById(BATCH_ID))
                .thenReturn(Optional.of(batch(BatchStatus.GENERATING, PAYLOAD_FILE_ID, null)));
    }

    private void notifyingOne(final NotificationClaim claim, final BatchStatus status) {
        reset(store, batches, notifications);
        when(batches.claimForNotification(any(UUID.class), any(UUID.class))).thenReturn(claim);
        when(batches.renewNotificationClaim(any(UUID.class), any(UUID.class))).thenReturn(true);
        when(batches.releaseNotificationClaim(any(UUID.class), any(UUID.class))).thenReturn(true);
        when(batches.findById(BATCH_ID))
                .thenReturn(Optional.of(batch(status, PAYLOAD_FILE_ID, DOCUMENT_FILE_ID)));
        when(notifications.update(any(RegisterNotification.class), anyInt()))
                .thenReturn(NotificationSettlement.APPLIED);
        when(notifications.tallyAttempts(any(UUID.class), anyInt())).thenReturn(true);
    }

    private void oneRecipientOwedAnEmail(final NotificationStatus status) {
        notifyingOne(NotificationClaim.CLAIMED, BatchStatus.GENERATED);
        when(store.batched(BATCH_ID)).thenReturn(List.of(register(List.of(recipient()))));
        when(notifications.findByBatchId(BATCH_ID)).thenReturn(List.of(row(status)));
    }

    private void holding(final RegisterBatch batch) {
        reset(batches, store);
        when(batches.findById(BATCH_ID)).thenReturn(Optional.of(batch));
        when(batches.compareAndSet(any(RegisterBatch.class), any(BatchStatus.class)))
                .thenReturn(true);
    }

    // --- the far ends ----------------------------------------------------------------------------

    private void renderCommandAnswering(final int status) {
        contexts.resetAll();
        contexts.stubFor(post(urlEqualTo(ANY_RENDER_COMMAND))
                .willReturn(aResponse().withStatus(status)));
    }

    private void renderCommandFaulting() {
        contexts.resetAll();
        contexts.stubFor(post(urlEqualTo(ANY_RENDER_COMMAND))
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
    }

    private void queryAnswering(final int status, final String body) {
        contexts.resetAll();
        contexts.stubFor(get(urlPathMatching(ANY_DOCUMENT_QUERY))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", SystemDocGeneratorClient.DOCUMENT_MEDIA_TYPE)
                        .withBody(body)));
    }

    private void queryFaulting() {
        contexts.resetAll();
        contexts.stubFor(get(urlPathMatching(ANY_DOCUMENT_QUERY))
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
    }

    private void emailCommandAnswering(final int status) {
        contexts.resetAll();
        contexts.stubFor(post(urlPathMatching(ANY_EMAIL_COMMAND))
                .willReturn(aResponse().withStatus(status)));
    }

    private void emailCommandFaulting() {
        contexts.resetAll();
        contexts.stubFor(post(urlPathMatching(ANY_EMAIL_COMMAND))
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
    }

    // --- the calls -------------------------------------------------------------------------------

    private void askForARender() {
        renderer.requestRender(
                new uk.gov.hmcts.cp.courtregister.domain.RenderRequest(PAYLOAD_FILE_ID, BATCH_ID,
                        "OEE_Layout5", "pdf", DocumentEventListener.ORIGINATING_SOURCE),
                uk.gov.hmcts.cp.courtregister.domain.CallerIdentity.SYSTEM);
    }

    private void askAboutTheDocument() {
        renderer.query(PAYLOAD_FILE_ID,
                uk.gov.hmcts.cp.courtregister.domain.CallerIdentity.SYSTEM);
    }

    private void askForAnEmail() {
        notifier.send(row(NotificationStatus.PENDING), DOCUMENT_FILE_ID,
                uk.gov.hmcts.cp.courtregister.domain.CallerIdentity.SYSTEM);
    }

    private void deliver(final String eventName, final String body) {
        whateverItAnswers(() -> listener.onPublicEvent(message(eventName, body)));
    }

    /**
     * Runs one arrangement and lets it answer however it answers.
     *
     * <p>Not a swallowed failure. Most of the arrangements above are the leg's failure paths, and a
     * refusal reaching the caller is what makes the line under sweep happen; what each of them
     * <em>answers</em> is owned and asserted by the eight suites for the eight classes, and this
     * fixture's whole subject is what they said out loud on the way. A drive that stopped at the
     * first refusal would cover the first line and none after it.
     *
     * @param arrangement the call to make
     */
    // PMD.AvoidCatchingGenericException / PMD.EmptyCatchBlock: see above - the answer is another
    // suite's assertion, and the capture is this one's.
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.EmptyCatchBlock"})
    private static void whateverItAnswers(final Runnable arrangement) {
        try {
            arrangement.run();
        } catch (RuntimeException answered) {
            // Deliberately not read: see the method's javadoc.
        }
    }

    // --- fixtures --------------------------------------------------------------------------------

    private static RestClient restClientFor(final String baseUrl) {
        final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    private static uk.gov.hmcts.cp.courtregister.adapter.http.RetryPolicy retryPolicy() {
        return new uk.gov.hmcts.cp.courtregister.adapter.http.RetryPolicy(
                MAX_ATTEMPTS, BACKOFF, BACKOFF, CONNECT_TIMEOUT.plus(READ_TIMEOUT));
    }

    private static GenerationProperties settings() {
        return new GenerationProperties(true, "0 0 18 * * MON-FRI", "Europe/London", false,
                RUN_DEADLINE, RUN_DEADLINE.plusMinutes(10), GRACE_PERIOD,
                GenerationProperties.COMPLETION_EVENT,
                GenerationProperties.SourceMode.LIVE, GenerationProperties.SourceMode.LIVE,
                GenerationProperties.SourceMode.LIVE, GenerationProperties.SourceMode.LIVE);
    }

    private DocumentOutcomeSinkImpl sinkOf() {
        return new DocumentOutcomeSinkImpl(store, batches, metrics, notifying);
    }

    private Deadline deadline() {
        return Deadline.startingAt(clock.instant(), RUN_DEADLINE);
    }

    private static RegisterBatch pending() {
        return batch(BatchStatus.PENDING, null, null);
    }

    private static RegisterBatch batch(final BatchStatus status, final UUID payloadFileId,
            final UUID documentFileId) {

        return new RegisterBatch(BATCH_ID, COURT_CENTRE, OU_CODE, COURT_HOUSE, REGISTER_DATE,
                FILE_NAME, payloadFileId, documentFileId, status, null, null, true, null, AT, AT,
                null, null, null, 1, null, 0);
    }

    /**
     * The row a refusal leaves behind: requested, and stamped as failed a minute and a half later.
     *
     * <p>The only arrangement in this class that carries an outcome stamp, because it is the only
     * one whose reading is a round trip rather than a state.
     *
     * @return the batch as the read after the mark answers it
     */
    private static RegisterBatch refused() {
        return new RegisterBatch(BATCH_ID, COURT_CENTRE, OU_CODE, COURT_HOUSE, REGISTER_DATE,
                FILE_NAME, PAYLOAD_FILE_ID, null, BatchStatus.FAILED,
                BatchFailureReason.GENERATION_FAILED, PersonalDataMarkers.GENERATOR_REASON, true,
                CompletedBy.EVENT, AT, AT, null, null, AT.plusSeconds(90), 1, null, 0);
    }

    /** One recorded register addressed to the given teams, about a child made of markers. */
    private static RegisterRecord register(final List<CourtRegisterRecipient> recipients) {
        final UUID hearingId = UUID.randomUUID();
        return new RegisterRecord(UUID.randomUUID(), hearingId, AT,
                new CourtCentreDay(COURT_CENTRE, REGISTER_DATE), AT, FILE_NAME, "Applicant",
                RecordedFlagState.ON,
                new CourtRegisterDocument("2026-03-02T18:04:00Z", "2026-03-02T09:00:00Z",
                        hearingId.toString(), COURT_CENTRE.toString(), FILE_NAME, "Applicant", null,
                        recipients,
                        List.of(new CourtRegisterDefendant(UUID.randomUUID().toString(),
                                PersonalDataMarkers.CHILD_NAME, PersonalDataMarkers.DATE_OF_BIRTH,
                                null, null, null, null, null, null, null, null,
                                List.of(new CourtRegisterCaseOrApplication("30GD1234521", null,
                                        null, null, null, null, null)),
                                null, null))));
    }

    /** The team the register goes to, made entirely of markers. */
    private static CourtRegisterRecipient recipient() {
        return new CourtRegisterRecipient(PersonalDataMarkers.RECIPIENT_ORGANISATION,
                PersonalDataMarkers.RECIPIENT_EMAIL, null, RegisterNotifierService.TEMPLATE_NAME);
    }

    /** One recipient's row, under the identity a suite looks for in the capture. */
    private static RegisterNotification row(final NotificationStatus status) {
        return new RegisterNotification(NOTIFICATION_ID, BATCH_ID,
                PersonalDataMarkers.RECIPIENT_EMAIL, PersonalDataMarkers.RECIPIENT_ORGANISATION,
                RegisterNotifierService.TEMPLATE_NAME, TEMPLATE_ID, status, null, null, 0);
    }

    private void doRefuseThePayload() {
        org.mockito.Mockito.doThrow(new PayloadStoreUnavailableException(
                        "the file service could not be reached to write the payload's content row"))
                .when(payloadFileStore).store(any(UUID.class),
                        any(tools.jackson.databind.JsonNode.class),
                        any(uk.gov.hmcts.cp.courtregister.application.PayloadMetadata.class));
    }

    private void doRefuseTheInsert() {
        org.mockito.Mockito.doThrow(new StoreRefusedRowException(
                        "a notification row for this batch and address is already held"))
                .when(notifications).insert(any(RegisterNotification.class));
    }

    /** The query answer that says the document exists. */
    private static String generatedDocument() {
        return """
                {
                  "documentFileServiceId": "%s",
                  "generatedTime": "2026-03-02T18:05:11.412+00:00"
                }""".formatted(DOCUMENT_FILE_ID);
    }

    /** The query answer that says the render was refused, in systemdocgenerator's own words. */
    private static String refusedDocument() {
        return """
                {
                  "failedTime": "2026-03-02T18:06:23.004+00:00",
                  "reason": "%s"
                }""".formatted(PersonalDataMarkers.GENERATOR_REASON);
    }

    private static String generationFailedPayload() {
        return """
                  "sourceCorrelationId": "%s",
                  "payloadFileServiceId": "%s",
                  "reason": "%s",
                  "originatingSource": "%s"
                """.formatted(BATCH_ID, PAYLOAD_FILE_ID, PersonalDataMarkers.GENERATOR_REASON,
                DocumentEventListener.ORIGINATING_SOURCE);
    }

    /** A framework {@code JsonEnvelope} carrying the given payload members. */
    private static String envelope(final String name, final String members) {
        return """
                {
                  "_metadata": {
                    "id": "3d7e5b21-6a04-4c19-8f52-71b0d9e3a4c8",
                    "name": "%s",
                    "createdAt": "2026-03-02T18:06:23.100Z",
                    "source": "systemdocgenerator",
                    "stream": {"id": "%s"},
                    "correlation": {"client": "systemdocgenerator"}
                  },
                %s}""".formatted(name, BATCH_ID, members);
    }

    private static TextMessage message(final String eventName, final String body) {
        try {
            final TextMessage message = mock(TextMessage.class);
            when(message.getStringProperty(DocumentEventListener.EVENT_NAME_PROPERTY))
                    .thenReturn(eventName);
            when(message.getText()).thenReturn(body);
            return message;
        } catch (JMSException never) {
            throw new IllegalStateException("a doubled message throws nothing", never);
        }
    }

    /** A delivery the broker cannot hand over, which is the listener's first refusal. */
    private static TextMessage unreadableMessage() {
        try {
            final TextMessage message = mock(TextMessage.class);
            when(message.getStringProperty(DocumentEventListener.EVENT_NAME_PROPERTY))
                    .thenThrow(new JMSException("the broker could not hand the message over"));
            return message;
        } catch (JMSException never) {
            throw new IllegalStateException("a doubled message throws nothing", never);
        }
    }
}
