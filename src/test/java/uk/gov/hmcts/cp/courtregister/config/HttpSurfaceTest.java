package uk.gov.hmcts.cp.courtregister.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.support.WorkloadIdentityStub;

/**
 * The whole HTTP surface of this service, asserted rather than assumed (constitution Principle III).
 *
 * <p>This service has no business API at all. Its inbound contract is a queue message and its
 * outbound one is a POST it makes; the only HTTP it <em>serves</em> is the operational actuator set —
 * health with its liveness and readiness groups, info, metrics and the Prometheus scrape. There is
 * deliberately no replay endpoint: replay is resubmitting a parked message, not calling a URL.
 *
 * <p>The surface is asserted from what the application actually publishes rather than from the
 * property that configures it. A property assertion re-states the configuration file; the link list
 * is the thing an operator, a scanner and an attacker all see, and it is what changes when somebody
 * adds an endpoint by adding a dependency. Principle III says a business endpoint here needs a
 * constitution amendment rather than a spec — this is the test that notices one arriving.
 *
 * <p>Run under the {@code test} profile: the surface is a property of the application, not of the
 * broker or the store, so it needs neither.
 *
 * <p><strong>Asserted on both shapes of the service.</strong> The link list and the controller beans
 * are checked here on a plain context, and again in {@link OnAGeneratingPod} on a context with the
 * downstream half switched on - because that is the half whose operations (regenerate, resend, list,
 * supersede, check the flag) an API would have been the obvious home for, and FR-016 puts them in a
 * command in the image instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("the service's whole HTTP surface")
class HttpSurfaceTest {

    /** Exactly the endpoints {@code application.yaml} exposes, and the only ones permitted. */
    private static final Set<String> PERMITTED_ENDPOINTS =
            Set.of("health", "info", "metrics", "prometheus");

    /**
     * Endpoints the template's dependencies would happily publish and this service must not: each
     * one leaks either configuration, secrets or a control surface. {@code env} and
     * {@code configprops} would publish the broker connection string and the {@code CJSCPPUID}
     * identities; {@code loggers} would let a caller turn on a level this repository's privacy rules
     * assume nobody can turn on.
     */
    private static final Set<String> FORBIDDEN_ENDPOINTS =
            Set.of("env", "beans", "configprops", "loggers", "threaddump", "heapdump",
                    "mappings", "shutdown", "conditions", "scheduledtasks", "caches");

    /**
     * The one controller a context of this service is permitted to hold, and it is Boot's own.
     *
     * <p>{@code BasicErrorController} is the framework's {@code /error} fallback: it is what renders
     * the 404 body the case above asserts for {@code /} and for every forbidden actuator path, and
     * it publishes no route of this service's. It is named rather than filtered out, so that the
     * assertion is "exactly this and nothing else" rather than "nothing that looks like ours" -
     * a controller arriving from a dependency is the surface change hardest to notice.
     */
    private static final String ERROR_FALLBACK = "basicErrorController";

    /**
     * The controllers a pod that serves the operations API holds, and the only ones it may.
     *
     * <p>Increment 005 replaced the six operations commands with seven endpoints under
     * {@code /operations/**}, so "no controller of ours exists" stopped being true the moment the
     * first one landed. What has not changed is the force of the assertion: it is still "exactly
     * these and nothing else", so a controller arriving from a dependency, or a business endpoint
     * arriving without the constitution amendment Principle III requires, still fails here.
     *
     * <p>The list grows with the phases; T056 re-points this suite from naming beans to asserting
     * that every mapped path is under {@code /actuator} or {@code /operations}, which is the form
     * that stops caring how many controllers there are.
     */
    private static final List<String> OPERATIONS_CONTROLLERS =
            List.of("flagController", "batchesController", "registersController");

    private final MockMvc mockMvc;

    private final ApplicationContext context;

    @Autowired
    HttpSurfaceTest(final MockMvc mockMvc, final ApplicationContext context) {
        this.mockMvc = mockMvc;
        this.context = context;
    }

    @Test
    @DisplayName("the actuator publishes exactly health, info, metrics and prometheus")
    void should_publish_exactly_the_permitted_operational_endpoints() throws Exception {
        assertThat(publishedEndpoints(mockMvc))
                .as("the whole HTTP surface of a service with no business API")
                .isEqualTo(PERMITTED_ENDPOINTS);
    }

    @Test
    @DisplayName("nothing else is reachable, including the endpoints a dependency could publish")
    void should_not_serve_any_endpoint_outside_the_permitted_set() throws Exception {
        for (final String forbidden : FORBIDDEN_ENDPOINTS) {
            mockMvc.perform(get("/actuator/" + forbidden))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("health publishes the liveness and readiness groups the platform probes")
    void should_publish_the_liveness_and_readiness_probe_paths() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("no bean on the context serves a route of this service's")
    void should_hold_no_controller_but_the_error_fallback() {
        assertThat(controllerBeans(context))
                .as("the link list says what is reachable; this says there is nothing of ours to "
                        + "reach. Principle III makes a business endpoint here a constitution "
                        + "amendment and a controller is how one arrives - the one class in this "
                        + "service whose name ends in Controller is a lifecycle component and is "
                        + "not annotated")
                .containsExactly(ERROR_FALLBACK);
    }

    /**
     * The same surface on a pod with the downstream half switched on (FR-016).
     *
     * <p>Phase 7 adds regeneration, resending, listing, supersession and a flag read, and every one
     * of them is an operator asking this service to do something - which is exactly the shape a
     * REST resource is usually reached for. They are commands in the image instead, dispatched by
     * {@code docker/startup.sh}, and this is the case that says so: the answer to "is there an
     * endpoint for it" has to be asserted on the context that has the machinery, because a
     * controller wired behind {@code courtregister.generation.enabled} would be invisible to the
     * enclosing class's {@code test}-profile context.
     *
     * <p>A generating pod's configuration, and the properties are
     * {@code GenerationWiringContextTest}'s: the four modes LIVE so the real adapters resolve, the
     * embedded broker for the {@code public.event} subscription, and intake off, which is a
     * different half and needs a broker this suite has no business standing up. The context is
     * fresh rather than inherited - {@link NestedTestConfiguration} OVERRIDE - because the
     * enclosing class's {@code test} profile is the absence of everything this case is about.
     */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @ExtendWith(WorkloadIdentityStub.class)
    @SpringBootTest(properties = {
        "courtregister.generation.enabled=true",
        "courtregister.generation.completion=event",
        "courtregister.generation.sdg-mode=LIVE",
        "courtregister.generation.nn-mode=LIVE",
        "courtregister.generation.fileservice-mode=LIVE",
        "courtregister.generation.flag-mode=LIVE",
        "courtregister.fileservice.url=jdbc:postgresql://fileservice.internal:5432/fileservice",
        "courtregister.feature.endpoint=https://appconfig.internal",
        "courtregister.feature.label=ste86",
        "courtregister.endpoints.systemdocgenerator=http://systemdocgenerator.internal:8080",
        "courtregister.endpoints.notificationnotify=http://notificationnotify.internal:8080",
        "courtregister.endpoints.system-user-id=00000000-0000-4000-8000-000000000000",
        "courtregister.email.templates.cr_standard=5c9a0e21-3d47-4f18-9b62-0a71c4e8d530",
        "courtregister.payload.mode=STUB",
        "courtregister.referencedata.mode=STUB",
        "courtregister.consumer.enabled=false",
        "spring.artemis.broker-url=tcp://localhost:61616",
        "spring.artemis.embedded.enabled=true",
        "spring.artemis.embedded.queues=public.event"})
    @AutoConfigureMockMvc
    @DisplayName("with the downstream half switched on")
    class OnAGeneratingPod {

        private final MockMvc mockMvc;

        private final ApplicationContext context;

        @Autowired
        OnAGeneratingPod(final MockMvc mockMvc, final ApplicationContext context) {
            this.mockMvc = mockMvc;
            this.context = context;
        }

        @Test
        @DisplayName("serves the operations controllers, and nothing else of ours")
        void a_generating_pod_should_hold_the_operations_controllers_and_nothing_else() {
            assertThat(controllerBeans(context))
                    .as("the operations API is the named operator actions and nothing else "
                            + "(constitution Principle III): a controller here that is not one of "
                            + "them is a business endpoint arriving without the amendment that "
                            + "would have to precede it, and this is where it is noticed")
                    .containsExactlyInAnyOrderElementsOf(Stream.concat(
                            OPERATIONS_CONTROLLERS.stream(), Stream.of(ERROR_FALLBACK)).toList());
        }

        @Test
        @DisplayName("publishes the same four operational endpoints and nothing more")
        void a_generating_pod_should_publish_the_same_operational_endpoints() throws Exception {
            assertThat(publishedEndpoints(mockMvc))
                    .as("the downstream half adds health components, metrics and a job - it must "
                            + "add no endpoint, and a new one appearing here is a surface change "
                            + "nobody asked for")
                    .isEqualTo(PERMITTED_ENDPOINTS);
        }
    }

    /**
     * The same pod with the operations API switched off (FR-044).
     *
     * <p>{@code courtregister.operations.enabled} is deployment shape rather than a cutover lever,
     * so it has one obligation above every other: <strong>turning it off must cost the pod
     * nothing</strong>. The controllers, the action filter and the listings they call go away
     * together; a controller left behind over a listing nothing contributes would be an
     * {@code UnsatisfiedDependencyException} at refresh, and a switch that crashes the pod is not a
     * switch.
     *
     * <p>Asserted over the real component scan and on a real profile, because that is the only
     * shape the crash has: a slice test that never scans the controllers, and the {@code test}
     * profile on which they are not registered at all, are both blind to it.
     *
     * <p>The error body is asserted too. The bounded error attributes are deliberately
     * <em>not</em> conditional: a pod that serves none of the seven paths still answers whatever an
     * operator tried, and Boot's own body for a 404 echoes the path they typed (FR-025, FR-027).
     */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @ExtendWith(WorkloadIdentityStub.class)
    @SpringBootTest(properties = {
        "courtregister.operations.enabled=false",
        "courtregister.generation.enabled=true",
        "courtregister.generation.completion=event",
        "courtregister.generation.sdg-mode=LIVE",
        "courtregister.generation.nn-mode=LIVE",
        "courtregister.generation.fileservice-mode=LIVE",
        "courtregister.generation.flag-mode=LIVE",
        "courtregister.fileservice.url=jdbc:postgresql://fileservice.internal:5432/fileservice",
        "courtregister.feature.endpoint=https://appconfig.internal",
        "courtregister.feature.label=ste86",
        "courtregister.endpoints.systemdocgenerator=http://systemdocgenerator.internal:8080",
        "courtregister.endpoints.notificationnotify=http://notificationnotify.internal:8080",
        "courtregister.endpoints.system-user-id=00000000-0000-4000-8000-000000000000",
        "courtregister.email.templates.cr_standard=5c9a0e21-3d47-4f18-9b62-0a71c4e8d530",
        "courtregister.payload.mode=STUB",
        "courtregister.referencedata.mode=STUB",
        "courtregister.consumer.enabled=false",
        "spring.artemis.broker-url=tcp://localhost:61616",
        "spring.artemis.embedded.enabled=true",
        "spring.artemis.embedded.queues=public.event"})
    @AutoConfigureMockMvc
    @DisplayName("with the operations API switched off")
    class WithTheOperationsApiSwitchedOff {

        /** The bean that registers the action filter, by the name its factory method gives it. */
        private static final String ACTION_FILTER = "operationsActionFilter";

        /** The bean that renders every body this service does not write itself. */
        private static final String ERROR_ATTRIBUTES = "operationsErrorAttributes";

        private final MockMvc mockMvc;

        private final ApplicationContext context;

        @Autowired
        WithTheOperationsApiSwitchedOff(final MockMvc mockMvc, final ApplicationContext context) {
            this.mockMvc = mockMvc;
            this.context = context;
        }

        @Test
        @DisplayName("the pod starts and holds no operations controller")
        void the_switch_should_take_the_controllers_away_without_taking_the_pod_down() {
            assertThat(controllerBeans(context))
                    .as("the context refreshed at all, which is the first half of the assertion, "
                            + "and what is left of our HTTP surface is nothing")
                    .containsExactly(ERROR_FALLBACK);
        }

        @Test
        @DisplayName("the action filter goes with them and the bounded error body stays")
        void the_switch_should_withdraw_the_filter_and_keep_the_error_body() {
            assertThat(context.containsBean(ACTION_FILTER))
                    .as("a pod that answers none of the seven paths has no action to name")
                    .isFalse();
            assertThat(context.containsBean(ERROR_ATTRIBUTES))
                    .as("a 404 is still answered, and Boot's own body for one echoes the path the "
                            + "caller typed")
                    .isTrue();
        }

        @Test
        @DisplayName("an operations path is refused in bounded fields, echoing nothing")
        void an_operations_path_should_be_refused_without_echoing_what_was_asked_for()
                throws Exception {
            final String body = mockMvc.perform(get("/operations/flag"))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .as("nothing of ours is mapped there any more and no action is derived for it, "
                            + "so the authorisation filter refuses an unidentified caller before "
                            + "the container gets as far as saying nothing is served - and "
                            + "whatever comes back, none of it is what the caller asked for. What "
                            + "the bounded body itself carries is OperationsErrorAttributesTest's")
                    .doesNotContain("/operations");
        }
    }

    /**
     * Every bean that serves HTTP, by the two annotations that make one.
     *
     * <p>{@code @RestController} carries {@code @Controller}, so the first name would find both;
     * both are asked for anyway, because this is the assertion that notices an endpoint arriving and
     * it should not depend on a meta-annotation staying where it is.
     *
     * <p>Bean names rather than types, so the one permitted answer can be stated without naming a
     * framework package that a Boot upgrade may move.
     */
    private static List<String> controllerBeans(final ApplicationContext context) {
        return Stream.concat(
                        Arrays.stream(context.getBeanNamesForAnnotation(Controller.class)),
                        Arrays.stream(context.getBeanNamesForAnnotation(RestController.class)))
                .distinct()
                .toList();
    }

    /**
     * The endpoint ids the actuator index advertises.
     *
     * <p>Templated variants — {@code health-path}, {@code metrics-requiredMetricName} — are the same
     * endpoint reached with a path variable, so they are folded back onto the endpoint they belong
     * to; {@code self} is the index itself.
     */
    private static Set<String> publishedEndpoints(final MockMvc mockMvc) throws Exception {
        final String body = mockMvc.perform(get("/actuator"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final JsonNode links = JacksonConfig.contractObjectMapper().readTree(body).get("_links");
        return links.propertyNames().stream()
                .filter(name -> !"self".equals(name))
                .map(name -> name.split("-", 2)[0])
                .collect(Collectors.toUnmodifiableSet());
    }
}
