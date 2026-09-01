package uk.gov.hmcts.cp.courtregister.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpErrorContext;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.courtregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.courtregister.application.HearingPayloadSource;
import uk.gov.hmcts.cp.courtregister.application.IdempotencyGuard;
import uk.gov.hmcts.cp.courtregister.application.NowSubscriptionsSource;
import uk.gov.hmcts.cp.courtregister.application.RegisterSubmission;
import uk.gov.hmcts.cp.courtregister.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.courtregister.application.SubmissionReceipt;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.CompletionReason;
import uk.gov.hmcts.cp.courtregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.GuardDecision;
import uk.gov.hmcts.cp.courtregister.domain.ReasonCode;
import uk.gov.hmcts.cp.courtregister.domain.RunClaim;
import uk.gov.hmcts.cp.courtregister.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.courtregister.inbound.CourtRegisterMessageListener;
import uk.gov.hmcts.cp.courtregister.inbound.DistributionCommandParser;
import uk.gov.hmcts.cp.courtregister.inbound.ServiceBusConsumerConfig;
import uk.gov.hmcts.cp.courtregister.support.CapturedLog;
import uk.gov.hmcts.cp.courtregister.support.LegacyFixtures;
import uk.gov.hmcts.cp.courtregister.support.NowSubscriptionFixtures;
import uk.gov.hmcts.cp.courtregister.support.QueueHealthTestSupport;
import uk.gov.hmcts.cp.courtregister.support.StoreGateTestSupport;

/**
 * What this service is allowed to write down (constitution Principle VII).
 *
 * <p>Every defendant on a court register is a child. That is not a nuance of this suite, it is its
 * whole reason to exist: the register carries names, dates of birth, addresses, ethnicity, contact
 * details and free-text facts about children, and this pod's log lines are shipped to an index the
 * whole estate can read. The rules are not the same at every level, and the difference matters:
 *
 * <ul>
 *   <li><strong>Correlation is required at INFO and above.</strong> Every line about processing
 *       carries {@code source}, {@code requestId}, {@code hearingId} and {@code hearingDay}, because
 *       a line that cannot be tied to a request is a line nobody can act on.</li>
 *   <li><strong>Personal data is forbidden at INFO and above</strong> — and this suite asserts the
 *       stronger claim, that it appears at <em>no</em> level, because the level a deployed
 *       environment runs at is not this repository's decision to rely on.</li>
 *   <li><strong>Reasons are bounded codes.</strong> {@code completion_reason} and
 *       {@code failure_reason} come from the four enumerations this service owns and never from an
 *       exception's text or a fragment of a message body — free text is how a name reaches a log
 *       index without anybody writing a line that logs a name.</li>
 *   <li><strong>Secrets are forbidden at every level</strong>, and a connection string is a secret
 *       whether or not the log line calls it one.</li>
 * </ul>
 *
 * <p><strong>The register is really assembled here.</strong> The delivery path runs over the bean
 * graph {@link PipelineConfig} builds, with doubles for the four outward ports only — so the fragment
 * builder, the subscription matcher, the twelve mappers and the contract validator all get to write
 * whatever they write, about a child whose every personal field is a marker. A suite that mocked the
 * transformation would prove nothing about the twelve classes that actually hold the child's details.
 *
 * <p>Every assertion is made against a capture of <em>everything</em>, at TRACE, including the
 * rendered text of any exception attached to a line. A stack trace reaches a log index exactly as a
 * message does, and an exception somebody else wrote is the commonest way a payload fragment or a
 * credential escapes.
 *
 * <p>The markers are deliberately implausible strings. A test looking for the word "name" would fail
 * on a field called {@code loggerName}; a test looking for a value nothing else could produce fails
 * only when that value really was written.
 */
@DisplayName("what the court register service may write down")
class TelemetryPrivacyTest {

    /** The correlation set every processing line must carry. */
    private static final Set<String> CORRELATION =
            Set.of("source", "requestId", "hearingId", "hearingDay");

    private static final String CHILD_NAME_MARKER = "CHILDNAMEMARKERZQX7";
    private static final String CHILD_ADDRESS_MARKER = "CHILDADDRESSMARKERZQX7";
    private static final String CHILD_NINO_MARKER = "CHILDNINOMARKERZQX7";
    private static final String CHILD_EMAIL_MARKER = "child.contact.marker.zqx7@example.invalid";
    private static final String ETHNICITY_MARKER = "ETHNICITYMARKERZQX7";
    private static final String GUARDIAN_MARKER = "GUARDIANMARKERZQX7";
    private static final String FACTS_MARKER = "STATEMENTOFFACTSMARKERZQX7";
    private static final String MESSAGE_ID_MARKER = "MESSAGEIDMARKERZQX7";
    private static final String FIELD_NAME_MARKER = "FIELDNAMEMARKERZQX7";
    private static final String TRANSPORT_MARKER = "TRANSPORTMARKERZQX7";
    private static final String SETTLEMENT_MARKER = "SETTLEMENTMARKERZQX7";
    private static final String ADAPTER_MARKER = "ADAPTERMARKERZQX7";
    private static final String BODY_MARKER = "BODYMARKERZQX7";
    private static final String SECRET_MARKER = "SECRETMARKERZQX7";

    /**
     * The child's date of birth — a real date rather than a marker word, because the mappers parse
     * it and a hearing whose child has no readable birthday would never reach the lines under test.
     * It is nonetheless a value nothing else in this repository produces.
     */
    private static final String DATE_OF_BIRTH_MARKER = "2009-11-23";

    /**
     * The sharing user, as a canonical uuid the eye can pick out of a log index. It has to be a real
     * one — a marker word would be rejected by the parser and the run would never start, which would
     * prove nothing about what a run that <em>does</em> carry a user writes down.
     */
    private static final String CALLER_MARKER = "0dd0dd0d-dead-beef-cafe-facade000001";

    /** Every marker that names or describes a person, and must never appear at any level. */
    private static final List<String> PERSONAL_MARKERS = List.of(
            CHILD_NAME_MARKER, CHILD_ADDRESS_MARKER, CHILD_NINO_MARKER, CHILD_EMAIL_MARKER,
            ETHNICITY_MARKER, GUARDIAN_MARKER, FACTS_MARKER, DATE_OF_BIRTH_MARKER);

    /** The hearing the base fixtures are built around, and the court house its centre carries. */
    private static final String HEARING_ID = "1828f356-f746-4f2d-932b-79ef2df95c80";
    private static final String OU_CODE = "B01LY00";

    private static final int MAX_DELIVERY_COUNT = 5;
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(4);

    /** Reads a {@code reason=} or {@code detail=} token out of a formatted line. */
    private static final Pattern REASON = Pattern.compile("\\b(?:reason|detail)=(\\S+)");

    /** Every bounded code this service is permitted to write as a reason. */
    private static final Set<String> BOUNDED_REASONS = Stream.of(
                    Arrays.stream(ReasonCode.values()).map(ReasonCode::code),
                    Arrays.stream(CompletionReason.values()).map(CompletionReason::value),
                    Arrays.stream(TransformationAnomaly.values()).map(TransformationAnomaly::value),
                    Arrays.stream(DeadLetterReason.values()).map(DeadLetterReason::label))
            .flatMap(codes -> codes)
            .collect(Collectors.toUnmodifiableSet());

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    private final UUID requestId = UUID.fromString("3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8");

    private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
    private final HearingPayloadSource payloadSource = mock(HearingPayloadSource.class);
    private final NowSubscriptionsSource subscriptionsSource = mock(NowSubscriptionsSource.class);
    private final RegisterSubmissionClient submissionClient = mock(RegisterSubmissionClient.class);

    private final RunClaim claim =
            new RunClaim("RESULTS", requestId, "runner-1", UUID.randomUUID(), "msg-1");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PipelineConfig.class, PrivacyTestConfiguration.class)
            .withBean(ObjectMapper.class, JacksonConfig::contractObjectMapper)
            .withBean(IdempotencyGuard.class, () -> guard)
            .withBean(HearingPayloadSource.class, () -> payloadSource)
            .withBean(NowSubscriptionsSource.class, () -> subscriptionsSource)
            .withBean(RegisterSubmissionClient.class, () -> submissionClient);

    /**
     * The settings and the instrument surface the wiring under test consumes and does not own.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CourtRegisterProperties.class)
    static class PrivacyTestConfiguration {

        /**
         * The metrics component, a {@code @Component} in the running service and not
         * component-scanned here.
         *
         * @return the instrument surface
         */
        @Bean
        @ConditionalOnMissingBean
        ProcessingMetrics processingMetrics() {
            return new ProcessingMetrics(new SimpleMeterRegistry());
        }
    }

    // --- a real register, about a child made entirely of markers --------------------------------

    @Nested
    @DisplayName("a hearing that produces a register")
    class ProducingARegister {

        @Test
        @DisplayName("no line, at any level, carries anything that identifies the child")
        void should_never_write_a_child_s_details_at_any_level() {
            admitOneRun();
            when(payloadSource.fetch(any(DistributionCommand.class))).thenReturn(markedHearing());
            answerWith(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));
            when(submissionClient.submit(any(RegisterSubmission.class)))
                    .thenReturn(new SubmissionReceipt(202, true));

            runner.run(context -> {
                try (CapturedLog log = CapturedLog.everything()) {
                    listenerOver(context.getBean(DistributionPipeline.class))
                            .onMessage(deliveryOf(validBody()));

                    assertThat(processingLines(log))
                            .as("a run that logged nothing would satisfy the assertion below "
                                    + "vacuously")
                            .isNotEmpty();
                    for (final String marker : PERSONAL_MARKERS) {
                        assertThat(log.renderings())
                                .as("a child's own details reached the log index: %s", marker)
                                .noneMatch(line -> line.contains(marker));
                    }
                }
            });
        }

        @Test
        @DisplayName("every processing line at INFO and above carries the correlation identifiers")
        void should_correlate_every_processing_line() {
            admitOneRun();
            when(payloadSource.fetch(any(DistributionCommand.class))).thenReturn(markedHearing());
            answerWith(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));
            when(submissionClient.submit(any(RegisterSubmission.class)))
                    .thenReturn(new SubmissionReceipt(202, true));

            runner.run(context -> {
                try (CapturedLog log = CapturedLog.everything()) {
                    listenerOver(context.getBean(DistributionPipeline.class))
                            .onMessage(deliveryOf(validBody()));

                    final List<ILoggingEvent> lines = processingLines(log);
                    assertThat(lines).isNotEmpty();
                    for (final ILoggingEvent line : lines) {
                        assertThat(line.getMDCPropertyMap())
                                .as("uncorrelated line: %s", line.getFormattedMessage())
                                .containsKeys(CORRELATION.toArray(String[]::new));
                    }
                }
            });
        }

        @Test
        @DisplayName("every reason written out is one of this service's bounded codes")
        void should_report_only_bounded_reason_codes() {
            admitOneRun();
            // Two runs, because the vocabulary is used by both halves of the state machine: a
            // register that goes, and a register the frozen contract refuses.
            answerWith(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));
            when(submissionClient.submit(any(RegisterSubmission.class)))
                    .thenReturn(new SubmissionReceipt(202, true));

            runner.run(context -> {
                final CourtRegisterMessageListener listener =
                        listenerOver(context.getBean(DistributionPipeline.class));
                try (CapturedLog log = CapturedLog.everything()) {
                    when(payloadSource.fetch(any(DistributionCommand.class)))
                            .thenReturn(markedHearing());
                    listener.onMessage(deliveryOf(validBody()));

                    when(payloadSource.fetch(any(DistributionCommand.class)))
                            .thenReturn(payload("hearing-with-address-less-youth-and-parent.json"));
                    listener.onMessage(deliveryOf(validBody()));

                    final List<String> reasons = reasonsIn(log);
                    assertThat(reasons)
                            .as("a run that named no reason would satisfy the assertion below "
                                    + "vacuously")
                            .isNotEmpty();
                    assertThat(reasons)
                            .as("a reason outside the bounded vocabulary is free text, and free "
                                    + "text from this pipeline is made of somebody's hearing")
                            .allMatch(BOUNDED_REASONS::contains);
                }
            });
        }
    }

    // --- everything the outside world chooses the text of ----------------------------------------

    @Nested
    @DisplayName("text somebody else wrote")
    class SomebodyElsesText {

        @Test
        @DisplayName("a message body is never quoted back, whether it validates or not")
        void should_never_log_the_message_body() {
            final String unparseable = "{ this is not json " + BODY_MARKER;
            final String unknownField = bodyWithExtraField("extra", BODY_MARKER);

            runner.run(context -> {
                final CourtRegisterMessageListener listener =
                        listenerOver(context.getBean(DistributionPipeline.class));
                try (CapturedLog log = CapturedLog.everything()) {
                    listener.onMessage(deliveryOf(unparseable));
                    listener.onMessage(deliveryOf(unknownField));

                    assertThat(log.renderings())
                            .as("a rejection says what rule was broken, never what the producer "
                                    + "sent")
                            .noneMatch(line -> line.contains(BODY_MARKER));
                }
            });
        }

        @Test
        @DisplayName("a producer-chosen field name is reported as a placeholder, never as itself")
        void should_never_log_the_name_of_an_unknown_field() {
            final String unknownField = bodyWithExtraField(FIELD_NAME_MARKER, "anything");

            runner.run(context -> {
                try (CapturedLog log = CapturedLog.everything()) {
                    listenerOver(context.getBean(DistributionPipeline.class))
                            .onMessage(deliveryOf(unknownField));

                    assertThat(log.renderings())
                            .as("a name that looks harmless is still a name somebody else chose")
                            .noneMatch(line -> line.contains(FIELD_NAME_MARKER));
                }
            });
        }

        @Test
        @DisplayName("a broker-chosen message identity is never written out")
        void should_never_log_a_message_identity_the_producer_chose() {
            runner.run(context -> {
                final DistributionPipeline pipeline = context.getBean(DistributionPipeline.class);
                try (CapturedLog log = CapturedLog.everything()) {
                    // Two paths that would be tempted to quote it: a body that cannot validate, and
                    // a store that is not there to check the body against.
                    listenerOver(pipeline).onMessage(
                            deliveryOf("{ not json", "RESULTS:" + MESSAGE_ID_MARKER));
                    listenerWithNoStore(pipeline).onMessage(
                            deliveryOf(validBody(), "RESULTS:" + MESSAGE_ID_MARKER));

                    assertThat(log.renderings())
                            .as("the identity is the producer's text, and it would land in the log "
                                    + "index verbatim")
                            .noneMatch(line -> line.contains(MESSAGE_ID_MARKER));
                }
            });
        }

        @Test
        @DisplayName("the user a run is attributed to is not written at any level")
        void should_never_log_the_caller_a_run_is_attributed_to() {
            admitOneRun();
            when(payloadSource.fetch(any(DistributionCommand.class))).thenReturn(markedHearing());
            answerWith(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));
            when(submissionClient.submit(any(RegisterSubmission.class)))
                    .thenReturn(new SubmissionReceipt(202, true));

            runner.run(context -> {
                try (CapturedLog log = CapturedLog.everything()) {
                    listenerOver(context.getBean(DistributionPipeline.class))
                            .onMessage(deliveryOf(bodyNamingTheSharingUser()));

                    assertThat(processingLines(log))
                            .as("the run has to have happened for its silence to mean anything")
                            .isNotEmpty();
                    assertThat(log.renderings())
                            .as("the identity has one destination — a CJSCPPUID header — and the "
                                    + "log is the third way out")
                            .noneMatch(line -> line.contains(CALLER_MARKER));
                }
            });
        }

        @Test
        @DisplayName("a transport fault is reported by its condition, never by its words")
        void should_never_log_the_text_of_a_transport_failure() {
            try (CapturedLog log = CapturedLog.everything()) {
                QueueHealthTestSupport.unwatched().recordProcessorError(
                        "RECEIVE",
                        "courtregister.requests",
                        new AmqpException(true, AmqpErrorCondition.CONNECTION_FORCED,
                                "the broker said " + TRANSPORT_MARKER,
                                new AmqpErrorContext("sbemulatorns")));

                assertThat(log.renderings())
                        .as("a transport fault's message is written by the far end, not by us")
                        .noneMatch(line -> line.contains(TRANSPORT_MARKER));
            }
        }

        @Test
        @DisplayName("a settlement the broker refuses is reported by its operation, never by its "
                + "words")
        void should_never_log_the_text_of_a_refused_settlement() {
            admitOneRun();
            when(payloadSource.fetch(any(DistributionCommand.class))).thenReturn(markedHearing());
            answerWith(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));
            when(submissionClient.submit(any(RegisterSubmission.class)))
                    .thenReturn(new SubmissionReceipt(202, true));

            final ServiceBusReceivedMessageContext refusing = deliveryOf(validBody());
            doThrow(new IllegalStateException("the broker said " + SETTLEMENT_MARKER))
                    .when(refusing).complete();

            runner.run(context -> {
                try (CapturedLog log = CapturedLog.everything()) {
                    listenerOver(context.getBean(DistributionPipeline.class)).onMessage(refusing);

                    assertThat(log.renderings())
                            .noneMatch(line -> line.contains(SETTLEMENT_MARKER));
                }
            });
        }

        @Test
        @DisplayName("a payload adapter that fails is reported by its type only")
        void should_never_log_the_text_of_a_payload_adapter_failure() {
            admitOneRun();
            // A real cache or query client routinely quotes the key it was asked for and, on a parse
            // failure, the bytes it choked on — which is the payload, arriving by the back door.
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenThrow(new IllegalStateException(
                            "failed reading INT_" + ADAPTER_MARKER));

            runner.run(context -> {
                try (CapturedLog log = CapturedLog.everything()) {
                    listenerOver(context.getBean(DistributionPipeline.class))
                            .onMessage(deliveryOf(validBody()));

                    assertThat(log.renderings())
                            .noneMatch(line -> line.contains(ADAPTER_MARKER));
                }
            });
        }
    }

    // --- secrets ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a connection string is never written out, not even while it is being used")
    void should_never_log_the_broker_credential() {
        final CourtRegisterProperties properties = credentialledWith(
                "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;"
                        + "SharedAccessKey=" + SECRET_MARKER + ";UseDevelopmentEmulator=true;");

        runner.run(context -> {
            try (CapturedLog log = CapturedLog.everything()) {
                // Building the client is where the settings are read and announced. Nothing
                // connects: the processor client is lazy, so this exercises the logging and not the
                // broker.
                new ServiceBusConsumerConfig()
                        .courtRegisterProcessorClient(
                                properties,
                                listenerOver(context.getBean(DistributionPipeline.class)),
                                QueueHealthTestSupport.unwatched())
                        .close();

                assertThat(log.renderings())
                        .as("the startup line names which credential source was chosen, never the "
                                + "credential")
                        .noneMatch(line -> line.contains(SECRET_MARKER));
            }
        });
    }

    // --- the configuration that decides what reaches the index -----------------------------------

    @Nested
    @DisplayName("the shipped logging configuration")
    class ShippedConfiguration {

        @Test
        @DisplayName("emits the MDC, without which the correlation fields are thrown away")
        void should_ship_a_logging_configuration_that_carries_the_correlation_fields()
                throws Exception {
            final String logback = Files.readString(
                    Path.of("src", "main", "resources", "logback.xml"));

            assertThat(logback)
                    .as("without the MDC provider the identifiers are put in place and then thrown "
                            + "away, and every line above becomes uncorrelated")
                    .contains("<mdc/>");
        }

        @Test
        @DisplayName("turns no logger below INFO, so no deployed pod writes a payload dump")
        void should_ship_no_logger_below_info() throws Exception {
            final String logging = loggingSectionOf(Files.readString(
                    Path.of("src", "main", "resources", "application.yaml")));

            assertThat(logging)
                    .as("the whole-payload rules above assume a deployed pod runs at INFO; a "
                            + "shipped DEBUG level is how that assumption stops being true")
                    .doesNotContain("DEBUG")
                    .doesNotContain("TRACE");
            assertThat(logging).contains("root: INFO");
        }

        /**
         * The settings in the shipped {@code logging:} block, from its key to the end of the
         * document, with the comments taken out.
         *
         * <p>Read as text rather than bound as properties on purpose: what is being asserted is what
         * the file <em>ships</em>, and a binder would hand back the merged view of every source,
         * including whatever the test harness itself set.
         *
         * <p>Comments are dropped because the claim is about configured levels. A block that
         * documents why nothing here may be set to DEBUG is the opposite of the block this refuses,
         * and a matcher that could not tell the two apart would punish the file for explaining
         * itself.
         */
        private String loggingSectionOf(final String applicationYaml) {
            final int start = applicationYaml.indexOf("\nlogging:");
            assertThat(start).as("application.yaml declares a logging section").isNotNegative();
            return applicationYaml.substring(start).lines()
                    .filter(line -> !line.strip().startsWith("#"))
                    .collect(Collectors.joining("\n"));
        }
    }

    // --- fixtures --------------------------------------------------------------------------------

    /**
     * The base hearing, with every field that identifies its child replaced by a marker.
     *
     * @return the claim-check envelope the payload source answers with
     */
    private JsonNode markedHearing() {
        final JsonNode envelope = payload("hearing-with-surviving-youth-defendant.json");
        final ObjectNode prosecutionCase = (ObjectNode) envelope
                .get("hearing").get("prosecutionCases").get(0);
        prosecutionCase.put("statementOfFacts", FACTS_MARKER);

        final JsonNode defendant = prosecutionCase.get("defendants").get(0);
        final ObjectNode personDetails =
                (ObjectNode) defendant.get("personDefendant").get("personDetails");
        personDetails.put("firstName", CHILD_NAME_MARKER);
        personDetails.put("lastName", CHILD_NAME_MARKER);
        personDetails.put("dateOfBirth", DATE_OF_BIRTH_MARKER);
        personDetails.put("nationalInsuranceNumber", CHILD_NINO_MARKER);
        ((ObjectNode) personDetails.get("address")).put("address1", CHILD_ADDRESS_MARKER);
        ((ObjectNode) personDetails.get("contact")).put("primaryEmail", CHILD_EMAIL_MARKER);
        ((ObjectNode) personDetails.get("ethnicity"))
                .put("selfDefinedEthnicityDescription", ETHNICITY_MARKER);

        final ObjectNode guardian =
                (ObjectNode) defendant.get("associatedPersons").get(0).get("person");
        guardian.put("firstName", GUARDIAN_MARKER);
        guardian.put("lastName", GUARDIAN_MARKER);
        return envelope;
    }

    /**
     * One base payload, deep-copied so a case cannot change it for the next one.
     *
     * @param fixture the file name below {@code fixtures/base/}
     * @return the claim-check envelope
     */
    private JsonNode payload(final String fixture) {
        return LegacyFixtures.readBase(fixture).deepCopy();
    }

    /** The guard admits the run and accepts whatever the run reports. */
    private void admitOneRun() {
        when(guard.admit(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenReturn(new GuardDecision.Run(claim));
        when(guard.recordCompletion(any(RunClaim.class), any(CompletionReason.class)))
                .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
        when(guard.recordTransientFailure(any(RunClaim.class), any(ReasonCode.class)))
                .thenAnswer(call -> new GuardDecision.Abandon(call.getArgument(1)));
        when(guard.recordNonTransientFailure(any(RunClaim.class), any(ReasonCode.class)))
                .thenAnswer(call -> new GuardDecision.DeadLetter(
                        DeadLetterReason.NON_TRANSIENT, call.getArgument(1)));
    }

    /**
     * Reference data's answer for the register's day.
     *
     * @param subscriptions the subscriptions in force
     */
    private void answerWith(final JsonNode... subscriptions) {
        when(subscriptionsSource.subscriptionsOn(any(LocalDate.class), any(CallerIdentity.class)))
                .thenReturn(NowSubscriptionFixtures.answerOf(subscriptions));
    }

    /** The whole delivery path, over the pipeline the configuration under test assembled. */
    private CourtRegisterMessageListener listenerOver(final DistributionPipeline pipeline) {
        return new CourtRegisterMessageListener(
                new DistributionCommandParser(mapper),
                pipeline,
                new ProcessingMetrics(new SimpleMeterRegistry()),
                QueueHealthTestSupport.unwatched(),
                StoreGateTestSupport.open(),
                MAX_DELIVERY_COUNT);
    }

    /** The same listener, over a store that is not there. */
    private CourtRegisterMessageListener listenerWithNoStore(final DistributionPipeline pipeline) {
        return new CourtRegisterMessageListener(
                new DistributionCommandParser(mapper),
                pipeline,
                new ProcessingMetrics(new SimpleMeterRegistry()),
                QueueHealthTestSupport.unwatched(),
                StoreGateTestSupport.closed(),
                MAX_DELIVERY_COUNT);
    }

    private String validBody() {
        return """
                {
                  "source": "RESULTS",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "2020-01-20",
                  "sharedTime": "2020-06-01T10:00:00Z",
                  "eventType": "Hearing_Resulted"
                }
                """.formatted(requestId, HEARING_ID);
    }

    private String bodyNamingTheSharingUser() {
        return """
                {
                  "source": "RESULTS",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "2020-01-20",
                  "sharedTime": "2020-06-01T10:00:00Z",
                  "eventType": "Hearing_Resulted",
                  "userId": "%s"
                }
                """.formatted(requestId, HEARING_ID, CALLER_MARKER);
    }

    private String bodyWithExtraField(final String name, final String value) {
        return """
                {
                  "source": "RESULTS",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "2020-01-20",
                  "sharedTime": "2020-06-01T10:00:00Z",
                  "eventType": "Hearing_Resulted",
                  "%s": "%s"
                }
                """.formatted(requestId, HEARING_ID, name, value);
    }

    private static ServiceBusReceivedMessageContext deliveryOf(final String body) {
        return deliveryOf(body, "RESULTS:" + UUID.randomUUID());
    }

    private static ServiceBusReceivedMessageContext deliveryOf(
            final String body, final String messageId) {
        final ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(message.getBody()).thenReturn(BinaryData.fromString(body));
        when(message.getMessageId()).thenReturn(messageId);
        when(message.getLockToken()).thenReturn(UUID.randomUUID().toString());
        when(message.getDeliveryCount()).thenReturn(0L);

        final ServiceBusReceivedMessageContext context =
                mock(ServiceBusReceivedMessageContext.class);
        when(context.getMessage()).thenReturn(message);
        return context;
    }

    /** Every line this service wrote at INFO or above. */
    private static List<ILoggingEvent> processingLines(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> event.getLoggerName()
                        .startsWith("uk.gov.hmcts.cp.courtregister"))
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                .toList();
    }

    /** Every {@code reason=} and {@code detail=} value this service wrote. */
    private static List<String> reasonsIn(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> event.getLoggerName()
                        .startsWith("uk.gov.hmcts.cp.courtregister"))
                .map(ILoggingEvent::getFormattedMessage)
                .flatMap(line -> {
                    final Matcher matcher = REASON.matcher(line);
                    return matcher.results().map(result -> result.group(1));
                })
                .toList();
    }

    /** The whole settings object, carrying a broker credential nobody may write down. */
    private static CourtRegisterProperties credentialledWith(final String connectionString) {
        return new CourtRegisterProperties(
                new CourtRegisterProperties.Consumer(true),
                new CourtRegisterProperties.Servicebus(
                        connectionString, null, "courtregister.requests", 2, MAX_DELIVERY_COUNT,
                        Duration.ofMinutes(5), Duration.ofSeconds(60)),
                new CourtRegisterProperties.Claim(Duration.ofMinutes(5), RUN_DEADLINE),
                new CourtRegisterProperties.Store(Duration.ofSeconds(10)),
                new CourtRegisterProperties.Stub(PayloadFailureMode.NONE),
                new CourtRegisterProperties.Payload(
                        PayloadSourceMode.STUB,
                        new CourtRegisterProperties.Redis("localhost", 6379, null, false,
                                "INT_", Duration.ofSeconds(5), Duration.ofSeconds(5)),
                        new CourtRegisterProperties.Fallback(3, Duration.ofSeconds(1),
                                Duration.ofSeconds(2), Duration.ofSeconds(5),
                                Duration.ofSeconds(10))),
                new CourtRegisterProperties.Results("http://localhost:8080", null),
                new CourtRegisterProperties.Referencedata(
                        SubscriptionsSourceMode.STUB, "http://localhost:8080", null, null, 3,
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5),
                        Duration.ofSeconds(10)),
                new CourtRegisterProperties.Progression(
                        "http://localhost:8080", null, null, 4, Duration.ofMillis(500),
                        Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(10)),
                new CourtRegisterProperties.Submission(true));
    }
}
