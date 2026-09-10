package uk.gov.hmcts.cp.courtregister.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.courtregister.adapter.publicevents.DocumentEventListener;
import uk.gov.hmcts.cp.courtregister.batch.RegisterGenerationJob;
import uk.gov.hmcts.cp.courtregister.inbound.ConsumerLifecycleController;
import uk.gov.hmcts.cp.courtregister.support.WorkloadIdentityStub;

/**
 * What {@code courtregister.cli} turns off, asserted on two contexts that differ by that property
 * and by nothing else.
 *
 * <p>The three things an operations command must not bring up are each a different failure, and none
 * of them is visible from a unit test - only a context either holds the bean or it does not
 * ({@link CliModeConfig} says why for each). A consumer would take a delivery from the pod whose job
 * it is and record a register as a side effect of listing one; a scheduler would be a second replica
 * of the 18:00 run, and a command still running at 18:00 London would generate the night twice; a
 * listener container would take the durable subscription - which admits exactly one consumer - away
 * from the pod waiting for the outcomes and give it to a process about to exit.
 *
 * <p>Both contexts are a generating pod's: generation enabled, the four modes LIVE, intake enabled.
 * That is the point of the pairing. The property is <strong>not</strong> the inverse of
 * {@code courtregister.generation.enabled} and must not become a second cutover lever (constitution
 * Cutover Rule), so the ordinary-pod case asserts the same context holds all three, and both cases
 * assert the HTTP surface still serves no route of this service's (FR-016: no HTTP endpoint is
 * added).
 *
 * <p>Nothing here connects to anything. The store is pointed at a database that does not exist, so
 * the gated start's first probe fails and the processor - built but never started by
 * {@code ServiceBusConsumerConfig}, {@link ConsumerLifecycleController} being the only component
 * permitted to start it - is never started against a broker this suite does not stand up; the
 * {@code public.event} subscription is served by the embedded broker, which is the arrangement
 * {@code GenerationWiringContextTest} boots the downstream half with. The identity variables the
 * workload-identity credential is built from come from {@link WorkloadIdentityStub}, because a
 * generating pod is given all three by the AKS webhook.
 */
@DisplayName("what courtregister.cli turns off, and what it leaves alone")
class CliModeConfigTest {

    /** The master switch for the downstream half; both contexts here are a generating pod's. */
    private static final String GENERATION_ENABLED = "courtregister.generation.enabled=true";

    private static final String COMPLETION_EVENT = "courtregister.generation.completion=event";

    private static final String SDG_MODE = "courtregister.generation.sdg-mode=LIVE";

    private static final String NN_MODE = "courtregister.generation.nn-mode=LIVE";

    private static final String FILESERVICE_MODE =
            "courtregister.generation.fileservice-mode=LIVE";

    private static final String FLAG_MODE = "courtregister.generation.flag-mode=LIVE";

    private static final String FILESERVICE_URL =
            "courtregister.fileservice.url=jdbc:postgresql://fileservice.internal:5432/fileservice";

    private static final String FLAG_ENDPOINT =
            "courtregister.feature.endpoint=https://appconfig.internal";

    private static final String FLAG_LABEL = "courtregister.feature.label=ste86";

    private static final String SDG_ENDPOINT =
            "courtregister.endpoints.systemdocgenerator=http://systemdocgenerator.internal:8080";

    private static final String NN_ENDPOINT =
            "courtregister.endpoints.notificationnotify=http://notificationnotify.internal:8080";

    private static final String SYSTEM_USER_ID =
            "courtregister.endpoints.system-user-id=00000000-0000-4000-8000-000000000000";

    private static final String TEMPLATE_ID =
            "courtregister.email.templates.cr_standard=5c9a0e21-3d47-4f18-9b62-0a71c4e8d530";

    private static final String PAYLOAD_MODE = "courtregister.payload.mode=STUB";

    private static final String REFDATA_MODE = "courtregister.referencedata.mode=STUB";

    /**
     * Intake is <strong>on</strong> in both contexts, which is what makes the consumer's absence in
     * the CLI one attributable to {@code courtregister.cli} rather than to the switch beside it.
     */
    private static final String CONSUMER_ENABLED = "courtregister.consumer.enabled=true";

    /**
     * A store that is not there, so the gated start's first probe fails and intake never begins.
     * A reachable database would start the processor against a broker nothing here stands up, and
     * which half of the pair a developer's laptop happened to boot would decide it.
     */
    private static final String NO_STORE =
            "spring.datasource.url=jdbc:postgresql://localhost:5432/courtregister-absent";

    private static final String BROKER_URL = "spring.artemis.broker-url=tcp://localhost:61616";

    private static final String EMBEDDED_BROKER = "spring.artemis.embedded.enabled=true";

    private static final String EMBEDDED_TOPIC = "spring.artemis.embedded.queues=public.event";

    /** The one property the two contexts differ by. */
    private static final String CLI_ON = "courtregister.cli=true";

    private static final String CLI_OFF = "courtregister.cli=false";

    /**
     * The one controller either context is permitted to hold, and it is Boot's own {@code /error}
     * fallback rather than a route of this service's - the same one {@code HttpSurfaceTest} names,
     * and for the same reason: "exactly this and nothing else" notices a controller arriving from a
     * dependency, which "nothing that looks like ours" would not.
     */
    private static final String ERROR_FALLBACK = "basicErrorController";

    /**
     * Whatever this context has scheduled, which is nothing at all where no scheduling
     * configuration was imported.
     *
     * <p>Asked of the annotation post-processor rather than of the job bean, because the job is not
     * the only {@code @Scheduled} on a generating context - {@code GenerationReconciler} carries one
     * too - and "no scheduled job" is a claim about both.
     *
     * @param context the context under assertion
     * @return the scheduled tasks, empty where nothing processes {@code @Scheduled}
     */
    private static Set<ScheduledTask> scheduledTasks(final ApplicationContext context) {
        final ScheduledAnnotationBeanPostProcessor processor =
                context.getBeanProvider(ScheduledAnnotationBeanPostProcessor.class)
                        .getIfAvailable();
        return processor == null ? Set.of() : processor.getScheduledTasks();
    }

    /**
     * The listener containers this context registered, which is what a {@code @JmsListener} becomes.
     *
     * @param context the context under assertion
     * @return the containers, empty where no endpoint was registered
     */
    private static Collection<MessageListenerContainer> listenerContainers(
            final ApplicationContext context) {
        final JmsListenerEndpointRegistry registry =
                context.getBeanProvider(JmsListenerEndpointRegistry.class).getIfAvailable();
        return registry == null ? List.of() : registry.getListenerContainers();
    }

    /**
     * Every bean on this context that serves HTTP, by the two annotations that make one.
     *
     * <p>{@code @RestController} carries {@code @Controller}, so the first name would find both;
     * both are asked for anyway, because this assertion is the one that notices an endpoint arriving
     * and it should not depend on a meta-annotation staying where it is.
     *
     * @param context the context under assertion
     * @return the bean names, which must be none
     */
    private static List<String> controllerBeans(final ApplicationContext context) {
        return Stream.concat(
                        Arrays.stream(context.getBeanNamesForAnnotation(Controller.class)),
                        Arrays.stream(context.getBeanNamesForAnnotation(RestController.class)))
                .distinct()
                .toList();
    }

    @Nested
    @ExtendWith(WorkloadIdentityStub.class)
    @SpringBootTest(properties = {
        GENERATION_ENABLED, COMPLETION_EVENT, SDG_MODE, NN_MODE, FILESERVICE_MODE, FLAG_MODE,
        FILESERVICE_URL, FLAG_ENDPOINT, FLAG_LABEL, SDG_ENDPOINT, NN_ENDPOINT, SYSTEM_USER_ID,
        TEMPLATE_ID, PAYLOAD_MODE, REFDATA_MODE, CONSUMER_ENABLED, NO_STORE, BROKER_URL,
        EMBEDDED_BROKER, EMBEDDED_TOPIC, CLI_ON})
    @DisplayName("a JVM started to run one operations command")
    class ACliContext {

        private final ApplicationContext context;

        @Autowired
        ACliContext(final ApplicationContext context) {
            this.context = context;
        }

        @Test
        @DisplayName("holds no Service Bus consumer, so a command takes no delivery")
        void a_cli_context_should_hold_no_service_bus_consumer() {
            assertThat(context.getBeanNamesForType(ServiceBusProcessorClient.class))
                    .as("a command that consumed a delivery would record a register as a side "
                            + "effect of listing one, and would take that delivery from the pod "
                            + "whose job it is: max-concurrent-calls is shared by every consumer "
                            + "on the queue")
                    .isEmpty();
            assertThat(context.getBeanNamesForType(ConsumerLifecycleController.class))
                    .as("the one component permitted to start intake, which is why its absence is "
                            + "half the claim: a client nothing can start is still a client "
                            + "something later could")
                    .isEmpty();
        }

        @Test
        @DisplayName("schedules nothing, so a command cannot generate the night twice")
        void a_cli_context_should_schedule_nothing() {
            assertThat(scheduledTasks(context))
                    .as("the lock makes 18:00 one run; a command holding a scheduler is a second "
                            + "replica of it, and one running long enough to reach 18:00 London "
                            + "would generate the night twice")
                    .isEmpty();
            assertThat(context.getBeanNamesForType(RegisterGenerationJob.class))
                    .as("the nightly run itself, which a command has no business holding")
                    .isEmpty();
        }

        @Test
        @DisplayName("runs no listener container, so the durable subscription is left alone")
        void a_cli_context_should_run_no_jms_listener_container() {
            assertThat(listenerContainers(context))
                    .as("the durable subscription admits exactly one consumer, so a command that "
                            + "subscribed would take the topic away from the pod waiting for the "
                            + "outcomes and give it to a process about to exit")
                    .isEmpty();
            assertThat(context.getBeanNamesForType(DocumentEventListener.class))
                    .as("the listener the subscription would deliver to, and therefore the "
                            + "@JmsListener a container is created from")
                    .isEmpty();
        }

        @Test
        @DisplayName("still serves no controller but Boot's error fallback")
        void a_cli_context_should_hold_no_controller_but_the_error_fallback() {
            assertThat(controllerBeans(context))
                    .as("FR-016: the operations surface is a command in the image, and a CLI "
                            + "context is not the place a first endpoint arrives through")
                    .containsExactly(ERROR_FALLBACK);
        }
    }

    /**
     * The default the image ships with, which neither context above can see.
     *
     * <p>Both of those set {@code courtregister.cli} explicitly - that is what makes them a pair
     * differing by one property - so between them they say what {@code true} and {@code false} do
     * and nothing about what happens when nothing sets it at all. {@link CliModeConfig#NOT_CLI}
     * could be flipped to {@code "true"} and both would stay green, while every deployed pod that
     * does not name the property stopped consuming, stopped scheduling and stopped subscribing:
     * the quietest outage in the service, produced by a one-word change with a green suite behind
     * it. Carried as an open finding from Phase 7 and closed here.
     *
     * <p>Asserted on the condition rather than on a context, deliberately. A context loads
     * {@code application.yaml}, which sets {@code cli: false} itself, so a Spring test would pass
     * on the file's value whatever the constant said and would pin the wrong one of the two.
     * {@link org.springframework.core.env.StandardEnvironment} carries no such property, which is
     * the environment a deployed pod's condition is evaluated against where nothing sets it.
     */
    @Nested
    @DisplayName("the default the image ships, which nothing sets")
    class TheShippedDefault {

        /**
         * The condition as it is evaluated before any bean exists, over an environment that does
         * not carry the property.
         *
         * @return what the condition answered
         */
        private boolean matchesAnEnvironmentWithout(final String value) {
            final StandardEnvironment environment = new StandardEnvironment();
            if (value != null) {
                environment.getPropertySources().addFirst(new MapPropertySource("under-test",
                        Map.of(CliModeConfig.CLI_PROPERTY, value)));
            }
            final ConditionContext context = mock(ConditionContext.class);
            when(context.getEnvironment()).thenReturn(environment);
            return new CliModeConfig.NotCliMode().matches(context, null);
        }

        @Test
        @DisplayName("is not CLI mode, so a pod nothing configured still consumes")
        void a_property_nothing_sets_at_all_should_not_be_read_as_cli_mode() {
            assertThat(matchesAnEnvironmentWithout(null))
                    .as("an ordinary pod must be unaffected by the property existing; a default of "
                            + "true would take the consumer, the scheduler and the subscription "
                            + "off every pod that does not name it, and say nothing while doing it")
                    .isTrue();
        }

        @Test
        @DisplayName("and neither is a value that will not parse")
        void a_value_that_will_not_parse_should_not_be_read_as_cli_mode() {
            assertThat(matchesAnEnvironmentWithout("yes-please"))
                    .as("this switch decides who starts, so a typo in a Helm value must not be "
                            + "able to stop a pod consuming; anything other than true is read the "
                            + "same way as absent")
                    .isTrue();
        }

        @Test
        @DisplayName("while true, and only true, is")
        void the_one_value_that_should_be_read_as_cli_mode_is_true() {
            assertThat(matchesAnEnvironmentWithout("true"))
                    .as("the other half of the claim: a default that could never be overridden "
                            + "would leave every command holding a scheduler")
                    .isFalse();
        }
    }

    @Nested
    @ExtendWith(WorkloadIdentityStub.class)
    @SpringBootTest(properties = {
        GENERATION_ENABLED, COMPLETION_EVENT, SDG_MODE, NN_MODE, FILESERVICE_MODE, FLAG_MODE,
        FILESERVICE_URL, FLAG_ENDPOINT, FLAG_LABEL, SDG_ENDPOINT, NN_ENDPOINT, SYSTEM_USER_ID,
        TEMPLATE_ID, PAYLOAD_MODE, REFDATA_MODE, CONSUMER_ENABLED, NO_STORE, BROKER_URL,
        EMBEDDED_BROKER, EMBEDDED_TOPIC, CLI_OFF})
    @DisplayName("an ordinary pod, which the property must leave exactly as it was")
    class AnOrdinaryPod {

        private final ApplicationContext context;

        @Autowired
        AnOrdinaryPod(final ApplicationContext context) {
            this.context = context;
        }

        @Test
        @DisplayName("holds the Service Bus consumer and the component that starts it")
        void an_ordinary_pod_should_hold_the_service_bus_consumer() {
            assertThat(context.getBeanNamesForType(ServiceBusProcessorClient.class))
                    .as("intake is this service's reason for existing; a property that turned it "
                            + "off everywhere would be the quietest outage there is")
                    .isNotEmpty();
            assertThat(context.getBeanNamesForType(ConsumerLifecycleController.class))
                    .as("the gated start, which is what begins consuming once the store answers")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("holds the nightly run, scheduled")
        void an_ordinary_pod_should_schedule_the_nightly_run() {
            assertThat(scheduledTasks(context))
                    .as("18:00 comes and goes on a context with nothing scheduled, and the only "
                            + "trace is a startup line nobody reads")
                    .isNotEmpty();
            assertThat(context.getBeanNamesForType(RegisterGenerationJob.class))
                    .as("the run the schedule fires")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("runs the public-event listener container")
        void an_ordinary_pod_should_run_the_jms_listener_container() {
            assertThat(listenerContainers(context))
                    .as("the durable subscription this pod holds; with nothing subscribed, every "
                            + "outcome waits for the grace-period reconciler")
                    .isNotEmpty()
                    .allSatisfy(container -> assertThat(container.isRunning())
                            .as("event-driven completion means the container starts with the pod")
                            .isTrue());
            assertThat(context.getBeanNamesForType(DocumentEventListener.class))
                    .as("the listener the subscription delivers to")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("serves no controller either")
        void an_ordinary_pod_should_hold_no_controller_but_the_error_fallback() {
            assertThat(controllerBeans(context))
                    .as("actuator only, on every shape of this service (constitution Principle III)")
                    .containsExactly(ERROR_FALLBACK);
        }
    }
}
