package uk.gov.hmcts.cp.courtregister.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

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

    private final MockMvc mockMvc;

    @Autowired
    HttpSurfaceTest(final MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    @DisplayName("the actuator publishes exactly health, info, metrics and prometheus")
    void should_publish_exactly_the_permitted_operational_endpoints() throws Exception {
        assertThat(publishedEndpoints())
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

    /**
     * The endpoint ids the actuator index advertises.
     *
     * <p>Templated variants — {@code health-path}, {@code metrics-requiredMetricName} — are the same
     * endpoint reached with a path variable, so they are folded back onto the endpoint they belong
     * to; {@code self} is the index itself.
     */
    private Set<String> publishedEndpoints() throws Exception {
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
