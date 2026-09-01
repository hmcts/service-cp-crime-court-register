package uk.gov.hmcts.cp.courtregister.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpErrorContext;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.messaging.servicebus.ServiceBusErrorSource;
import com.azure.messaging.servicebus.ServiceBusException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.application.HearingPayloadSource;
import uk.gov.hmcts.cp.courtregister.config.JacksonConfig;
import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.config.ServiceBusHealthIndicator;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.support.AdjustableClock;
import uk.gov.hmcts.cp.courtregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.courtregister.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.courtregister.support.ServiceTestSupport;

/**
 * The readiness policy, proven against both dependencies going away (spec FR-011).
 *
 * <p>The policy is asymmetric on purpose, and the asymmetry is the whole test:
 *
 * <ul>
 *   <li><strong>The store gates readiness.</strong> Processing is unsafe without the processed log —
 *       a pod that cannot record what it has done must not be sent work — and so does this pod's own
 *       gated start, because a database that replies is not the same thing as a service in a
 *       position to use it.</li>
 *   <li><strong>The queue never gates readiness.</strong> A pod cannot heal a broker by restarting,
 *       so putting the broker in readiness converts a blip into a rolling restart. It is reported as
 *       its own health component and its own gauge, and that is all.</li>
 * </ul>
 *
 * <p>The staleness rule is asserted against the indicator directly, with a clock the test moves. Its
 * content is entirely "how long ago was that error?", and both interesting cases sit a millisecond
 * either side of the window: a suite that slept could not land on either deliberately, and one that
 * waited a real minute would trade an exact assertion for a slow, approximate one.
 *
 * <p>Both container suites here freeze a dependency the whole build shares. That is safe because
 * Gradle runs this build's suites sequentially in one JVM — no other suite is running while a
 * container is paused — and because every freeze is undone in {@code @AfterEach}, including when an
 * assertion fails.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// The context owns a running consumer on the shared emulator queue, and this suite deliberately
// breaks that consumer's dependencies. Closing it with the class keeps both facts local.
class ReadinessPolicyIT {

    private static final String STORE_COMPONENT = "db";
    private static final String STARTUP_COMPONENT = "intakeStartup";
    private static final String BROKER_COMPONENT = "servicebus";

    private static final Duration OBSERVED_WITHIN = Duration.ofSeconds(120);
    private static final Duration POLL = Duration.ofSeconds(1);

    /** The default window, so the boundary asserted below is the one the service ships with. */
    private static final Duration STALENESS = Duration.ofSeconds(60);

    /** So a failure is reported as a failure rather than as a hang. */
    private static final Duration HELD_AT_MOST = Duration.ofMinutes(2);

    /**
     * The empty claim-check envelope: a hearing that gathered nobody, so a run completes
     * {@code no-defendants}. Nothing in it resembles hearing content — every defendant on a court
     * register is a youth — but it is an envelope, because the pipeline reads the hearing and the
     * share instant out of what the payload port returns.
     */
    private static final JsonNode PLACEHOLDER =
            JacksonConfig.contractObjectMapper().readTree(
                    "{\"stub\":true,\"sharedTime\":\"1970-01-01T00:00:00Z\",\"hearing\":{\"courtCentre\":{}}}");

    private static final String SOURCE = "RECEIVE";
    private static final String ENTITY_PATH = "courtregister.requests";

    private static String connectionString;

    @MockitoBean
    private HearingPayloadSource payloadSource;

    @Autowired
    private HealthEndpoint healthEndpoint;

    /** The request whose run is held open across the transport cut. */
    private final UUID held = UUID.randomUUID();

    /** Raised when that run has genuinely started. */
    private final CountDownLatch inFlight = new CountDownLatch(1);

    /** Lowered once the transport has been cut underneath it. */
    private final CountDownLatch release = new CountDownLatch(1);

    @DynamicPropertySource
    static void wireTheContainers(final DynamicPropertyRegistry registry) {
        connectionString = ServiceBusEmulatorTestSupport.connectionString();
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
        registry.add("courtregister.servicebus.connection-string", () -> connectionString);
        ServiceTestSupport.stubPayloadSource(registry);
        registry.add("courtregister.progression.system-user-id",
                () -> ServiceTestSupport.SYSTEM_USER_ID);
        registry.add("courtregister.results.system-user-id",
                () -> ServiceTestSupport.SYSTEM_USER_ID);
        // A frozen container swallows the connection attempt rather than refusing it, so the driver
        // waits out its connect timeout. The deployed default is thirty seconds, which would make
        // every health poll in this suite a thirty-second block; three keeps the outage observable
        // without changing what is being observed.
        registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "2000");
        // And a socket timeout, because a frozen container stops answering on connections it never
        // closes: a query over a connection the pool already holds would otherwise wait for ever.
        registry.add("spring.datasource.hikari.data-source-properties.socketTimeout", () -> "5");
    }

    @BeforeEach
    void holdOneRunOpen() {
        when(payloadSource.fetch(any(DistributionCommand.class))).thenAnswer(this::payloadFor);
    }

    @AfterEach
    void thawEverything() {
        release.countDown();
        PostgresTestSupport.unpause();
        ServiceBusEmulatorTestSupport.restore();
    }

    /**
     * The payload port. A neighbouring suite's message is handed the placeholder and passes through.
     */
    private JsonNode payloadFor(final InvocationOnMock invocation) throws InterruptedException {
        final DistributionCommand command = invocation.getArgument(0);
        if (held.equals(command.requestId())) {
            inFlight.countDown();
            release.await(HELD_AT_MOST.toSeconds(), TimeUnit.SECONDS);
        }
        return PLACEHOLDER;
    }

    // --- helpers ---------------------------------------------------------------------------

    private Status readinessStatus() {
        return healthEndpoint.healthForPath("readiness").getStatus();
    }

    private CompositeHealthDescriptor readiness() {
        return (CompositeHealthDescriptor) healthEndpoint.healthForPath("readiness");
    }

    private CompositeHealthDescriptor overall() {
        return (CompositeHealthDescriptor) healthEndpoint.health();
    }

    private Status brokerComponentStatus() {
        final HealthDescriptor component = overall().getComponents().get(BROKER_COMPONENT);
        return component == null ? Status.UNKNOWN : component.getStatus();
    }

    /**
     * A connection-class fault: the link the receiver was using was torn down under it.
     */
    private static Throwable connectionFailure() {
        return new ServiceBusException(
                new AmqpException(true, AmqpErrorCondition.CONNECTION_FORCED,
                        "the connection was forced closed",
                        new AmqpErrorContext("sbemulatorns")),
                ServiceBusErrorSource.RECEIVE);
    }

    private static ServiceBusHealthIndicator indicatorOn(final Clock clock) {
        return new ServiceBusHealthIndicator(
                STALENESS, new ProcessingMetrics(new SimpleMeterRegistry()), clock);
    }

    // --- the policy ------------------------------------------------------------------------

    @Test
    @DisplayName("readiness names the store and never the broker")
    void should_gate_readiness_on_the_store_alone() {
        assertThat(readiness().getComponents())
                .as("the store gates readiness, and so does this pod's own gated start — a database "
                        + "that replies is not a service in a position to use it")
                .containsOnlyKeys(STORE_COMPONENT, STARTUP_COMPONENT);

        assertThat(overall().getComponents())
                .as("the broker is still observable — as its own component, outside readiness")
                .containsKey(BROKER_COMPONENT);
    }

    @Test
    @DisplayName("a store outage takes readiness down, and readiness comes back with the store")
    void should_report_readiness_down_while_the_store_is_unreachable() {
        // Waited for rather than assumed: readiness also covers this pod's own gated start, and the
        // start is on a probe interval. A test that asserted UP the instant the context came up
        // would be asserting that the start had already happened, which is a different claim.
        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.UP.equals(readinessStatus()));

        PostgresTestSupport.pause();
        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.DOWN.equals(readinessStatus()));

        PostgresTestSupport.unpause();
        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.UP.equals(readinessStatus()));
    }

    @Test
    @DisplayName("a queue outage leaves readiness up and shows itself in the broker component")
    void should_keep_readiness_up_and_report_the_broker_down_during_a_queue_outage()
            throws InterruptedException {
        // The outage is staged with work in hand, because that is the only kind the SDK reports: a
        // processor with nothing to do treats a lost connection as retryable and rolls its message
        // pump silently, and five minutes against a broker that had been stopped outright produced
        // no callback of any kind. The evidence the health component is built on is a round trip
        // that failed, so there has to be one.
        //
        // Cutting the transport through the proxy makes that a sequence rather than a race: hold a
        // delivery, cut, then let it finish into a settlement that is refused at once.
        ServiceTestSupport.publish(ServiceTestSupport.validBody(held, UUID.randomUUID()));
        assertThat(inFlight.await(OBSERVED_WITHIN.toSeconds(), TimeUnit.SECONDS))
                .as("the run must genuinely be in flight before the transport is cut")
                .isTrue();

        ServiceBusEmulatorTestSupport.disconnect();
        release.countDown();
        try {
            await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                    .until(() -> Status.DOWN.equals(brokerComponentStatus()));

            assertThat(readinessStatus())
                    .as("a broker blip must never roll the pods")
                    .isEqualTo(Status.UP);
        } finally {
            ServiceBusEmulatorTestSupport.restore();
        }

        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.UP.equals(brokerComponentStatus()));
    }

    @Test
    @DisplayName("an unresolved error older than the staleness window, on an idle queue, is not an outage")
    void should_stop_reporting_an_error_that_nothing_has_contradicted_or_repeated() {
        final AdjustableClock clock = AdjustableClock.startingAt(Instant.parse("2026-08-31T09:00:00Z"));
        final ServiceBusHealthIndicator indicator = indicatorOn(clock);

        // The broker has answered this consumer before: that is what entitles a later silence to the
        // idle-queue reading. A consumer never answered at all keeps reporting the fault — the SDK
        // will not repeat it, so aging it out would hide a total outage — and that case is asserted
        // in ServiceBusHealthIndicatorTest.
        indicator.recordTraffic();
        clock.advance(Duration.ofMinutes(5));

        indicator.recordProcessorError(SOURCE, ENTITY_PATH, connectionFailure());
        assertThat(indicator.health().getStatus())
                .as("a fresh, unresolved connection failure is an outage")
                .isEqualTo(Status.DOWN);

        clock.advance(STALENESS.minusSeconds(1));
        assertThat(indicator.health().getStatus())
                .as("still inside the window, and still unresolved")
                .isEqualTo(Status.DOWN);

        clock.advance(Duration.ofSeconds(2));
        assertThat(indicator.health().getStatus())
                .as("an idle queue produces no traffic, and absence of traffic is not an outage")
                .isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("the gauge answers a scrape correctly without the health endpoint being asked first")
    void should_expose_the_broker_gauge_to_a_scrape_that_never_calls_health() {
        final AdjustableClock clock = AdjustableClock.startingAt(Instant.parse("2026-08-31T09:00:00Z"));
        final SimpleMeterRegistry scraped = new SimpleMeterRegistry();
        final ServiceBusHealthIndicator indicator =
                new ServiceBusHealthIndicator(STALENESS, new ProcessingMetrics(scraped), clock);

        indicator.recordIntakeStarted();
        indicator.recordProcessorError(SOURCE, ENTITY_PATH, connectionFailure());

        // Deliberately no health() call. Prometheus does not visit the health endpoint on its way
        // past, and a gauge that is only correct after somebody else has asked the same question is
        // a dashboard that disagrees with the probe for as long as nobody probes.
        assertThat(scraped.find(ProcessingMetrics.SERVICEBUS_UP).gauge().value())
                .as("the gauge and the component answer from the same live state")
                .isEqualTo(0);
    }
}
