package uk.gov.hmcts.cp.courtregister.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Whether the broker is reachable — reported loudly, and never allowed to gate readiness.
 *
 * <p><strong>A compile-safe seam, not the indicator.</strong> T022 implements the reachability rule
 * this class exists to hold; what is fixed here is its surface, because the transport adapter
 * reports refusals and accepted settlements to it and the consumer configuration registers it as the
 * {@code servicebus} health component.
 *
 * <p>The rule it will hold, stated so the tests that pin it have something to be about. Nothing
 * polls the broker: a health check that sent a probe message would cost a delivery every time it ran
 * and would race the support tooling that drains the queue. It reads what the SDK already reports —
 * the {@code processError} callback, the fact that a delivery arrived, and the fate of a settlement
 * — and answers from the relationship between them:
 *
 * <ul>
 *   <li>a <strong>connection-class</strong> failure with nothing since means the queue is
 *       unreachable; a message-level failure — a lock lost, a message not found — does not, because
 *       those arrive over a connection that plainly worked;</li>
 *   <li>traffic <strong>after</strong> the failure is the answer to the failure;</li>
 *   <li>a failure older than {@code courtregister.servicebus.health-staleness} with nothing since is
 *       <strong>not</strong> an outage <em>for a consumer the broker has answered before</em>: an
 *       idle queue produces no traffic, and absence of traffic is not evidence of failure. For a
 *       consumer that has never once been answered it is the only evidence there is, so that one
 *       says DOWN until first contact.</li>
 * </ul>
 *
 * <p>Registered <strong>outside</strong> the readiness group on purpose (spec FR-011): a pod cannot
 * heal a broker by restarting, so a broker in readiness turns a blip into a rolling restart of every
 * consumer at once, while the queue stays exactly as wrong as it was.
 *
 * <p><strong>Why the recorders are silent rather than refusing.</strong> Every one of them is called
 * from inside an SDK callback — the message pump and the {@code processError} handler — and a seam
 * that threw from there did not produce a red test, it produced a transport that never stabilised:
 * the processor tore its connection down and rebuilt it on every error, thousands of reactor threads
 * deep, until the test JVM ran out of heap. So the recorders accept what they are told and remember
 * none of it, and the component answers {@code UNKNOWN} — which is exactly what every suite asserting
 * UP or DOWN fails on, and is the truthful answer from a component that has not been written.
 */
public class ServiceBusHealthIndicator implements HealthIndicator {

    private final Duration staleness;
    private final ProcessingMetrics metrics;
    private final Clock clock;

    /**
     * Creates the indicator.
     *
     * @param staleness how old an unanswered connection failure may get before an idle queue is the
     *                  better explanation of the silence
     * @param metrics   the instrument surface the broker gauge lives on
     * @param clock     the clock every age in the rule is measured against
     */
    public ServiceBusHealthIndicator(
            final Duration staleness, final ProcessingMetrics metrics, final Clock clock) {
        this.staleness = staleness;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Records a fault the processor reported outside a delivery.
     *
     * @param errorSource where the SDK says the fault arose
     * @param entityPath  the queue the processor was working
     * @param failure     what the processor reported
     */
    public void recordProcessorError(
            final String errorSource, final String entityPath, final Throwable failure) {
        // Accepted and forgotten: only a connection-class failure means the queue is unreachable,
        // and telling one from another is what T022 writes here.
    }

    /**
     * Records a settlement the broker refused.
     *
     * @param refusal what the broker answered the settlement call with
     */
    public void recordSettlementRefusal(final Throwable refusal) {
        // Accepted and forgotten: a refusal about one message is not an outage of the queue.
    }

    /**
     * Records that the broker answered: a delivery arrived.
     */
    public void recordTraffic() {
        // Accepted and forgotten: traffic answers the failure that preceded it.
    }

    /**
     * Records that the broker accepted a settlement, which is a round trip like any other.
     */
    public void recordSettlementAccepted() {
        // Accepted and forgotten: a settlement the broker took is traffic like any other.
    }

    /**
     * Records that intake has started, which is when this component starts having an opinion about a
     * broker nobody has yet spoken to.
     */
    public void recordIntakeStarted() {
        // Accepted and forgotten: until intake starts there is no broker to have an opinion about.
    }

    /**
     * Whether the broker is reachable, evaluated now.
     *
     * @return whether the broker is reachable
     */
    public boolean reachableNow() {
        return false;
    }

    @Override
    public Health health() {
        return Health.unknown()
                .withDetail("condition", "not-yet-evaluated")
                .withDetail("stalenessWindow", staleness.toString())
                .build();
    }
}
