package uk.gov.hmcts.cp.courtregister.inbound;

import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import java.time.Duration;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.config.ServiceBusHealthIndicator;
import uk.gov.hmcts.cp.courtregister.persistence.ProcessedLogProbe;

/**
 * The one component permitted to start or stop intake.
 *
 * <p><strong>A compile-safe seam, not the controller.</strong> Nothing here starts intake: the gated
 * start (probe the store, run the deferred migration, and only then consume), the suspension a store
 * outage asks for, and the resume that ends it are T022's, and every transport suite that pins them
 * is red until it lands.
 *
 * <p><strong>Why the seam starts nothing at all</strong>, rather than starting the processor over a
 * listener that refuses. Starting it produces no useful red: the listener seam throws inside the
 * SDK's message pump, the processor treats that as a fatal receiver error and rebuilds its
 * connection, the redelivered message throws again — and the loop ran two thousand AMQP connections
 * deep and exhausted the test JVM's heap before any assertion could be reached. A consumer that has
 * not been started is what the suites then observe, and it is the truthful state of a service whose
 * gated start has not been written.
 *
 * <p>What the finished controller owes, so the shape is not mistaken for the behaviour:
 *
 * <ul>
 *   <li><strong>Nothing is consumed before the store answers.</strong> A service that consumes
 *       without a processed log cannot tell a first delivery from a redelivery, so it abandons a
 *       queue's worth of work and the broker's delivery budget parks perfectly good requests.</li>
 *   <li><strong>The migration runs here</strong>, on the first successful probe, before the
 *       processor starts — {@code DeferredFlywayMigration} takes it off the context-refresh path so
 *       that a pod with no store can still start and say why it is not ready.</li>
 *   <li><strong>Suspension is carried out here</strong>, never inside the delivery callback that
 *       asked for it: stopping a processor from within its own callback deadlocks the shutdown,
 *       because the shutdown waits for that callback to return.</li>
 * </ul>
 */
public class ConsumerLifecycleController implements SmartLifecycle, StoreGate {

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerLifecycleController.class);

    private final ServiceBusProcessorClient processor;
    private final ProcessedLogProbe storeProbe;
    private final Supplier<Flyway> flyway;
    private final ProcessingMetrics metrics;
    private final ServiceBusHealthIndicator health;
    private final Duration probeInterval;

    private volatile boolean running;

    /**
     * Creates the controller over everything a gated start needs.
     *
     * @param processor     the client it owns and is the only thing to start
     * @param storeProbe    the availability question the gated start waits on
     * @param flyway        the deferred migration, run before intake starts
     * @param metrics       the instrument surface suspensions are counted on
     * @param health        told when intake actually starts
     * @param probeInterval how often the store is asked, driving the start and the resume
     */
    public ConsumerLifecycleController(
            final ServiceBusProcessorClient processor,
            final ProcessedLogProbe storeProbe,
            final Supplier<Flyway> flyway,
            final ProcessingMetrics metrics,
            final ServiceBusHealthIndicator health,
            final Duration probeInterval) {
        this.processor = processor;
        this.storeProbe = storeProbe;
        this.flyway = flyway;
        this.metrics = metrics;
        this.health = health;
        this.probeInterval = probeInterval;
    }

    @Override
    public void start() {
        LOG.warn("Intake is not started: the gated start, the deferred migration and the "
                + "suspend-on-store-outage behaviour are not implemented yet, so this pod consumes "
                + "nothing. probeInterval={}", probeInterval);
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        processor.stop();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Whether intake has actually started, which is what the startup health component reports on.
     *
     * @return whether the processor has been started
     */
    public boolean intakeStarted() {
        return false;
    }

    @Override
    public boolean storeAvailable() {
        throw new UnsupportedOperationException(
                "a delivery is examined only once the processed log has answered");
    }

    @Override
    public void suspendIntake() {
        throw new UnsupportedOperationException(
                "a store outage stops intake until the store returns");
    }
}
