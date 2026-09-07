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
        @DisplayName("still serves no controller: the operations surface is a CLI, not an API")
        void a_generating_pod_should_hold_no_controller_but_the_error_fallback() {
            assertThat(controllerBeans(context))
                    .as("FR-016 says the tool ships in the image and that no HTTP endpoint is "
                            + "added; every command Phase 7 lands is one an API would have been "
                            + "the obvious home for, so this is where that decision is held")
                    .containsExactly(ERROR_FALLBACK);
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
