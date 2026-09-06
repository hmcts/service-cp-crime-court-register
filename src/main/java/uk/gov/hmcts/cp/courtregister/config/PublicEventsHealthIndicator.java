package uk.gov.hmcts.cp.courtregister.config;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Whether outcomes are still arriving, reported loudly and never allowed to gate readiness.
 *
 * <p>The same rule {@link ServiceBusHealthIndicator} keeps for the intake broker, for the same
 * reason: a pod cannot heal a broker by restarting, so a broker in the readiness group turns a blip
 * into a rolling restart of every consumer at once while the thing that was actually wrong stays
 * exactly as wrong. It is its own health component instead, outside the group (spec FR-011).
 *
 * <p>What it reports is the subscription's state and the age of the last delivery. The second is the
 * one that matters at 18:30: a subscription that is connected and has heard nothing since the run
 * began is a broker problem the reconciler is about to paper over, and the {@code reconciled} count
 * beside it is the same story told from the other end.
 *
 * <p><strong>Silence is reported, never judged.</strong> The status follows the subscription alone,
 * because this service hears from {@code public.event} only when somebody else renders something:
 * a night with no registers to generate produces no events at all, and a component that read that
 * as an outage would be reporting the working day rather than the broker. The age is published so
 * that a human, or an alert rule that also knows a run started, can draw the conclusion this
 * component has no business drawing on its own.
 *
 * <p>The details are three, and none of them carries anything but a bounded word, an instant and a
 * number:
 *
 * <ul>
 *   <li>{@code subscription} - {@code running} or {@code stopped};</li>
 *   <li>{@code lastDeliveryAt} - when an event last arrived, or {@code none};</li>
 *   <li>{@code lastDeliveryAgeSeconds} - how long ago that was, or {@code none}.</li>
 * </ul>
 *
 * <p><strong>Seam only.</strong> The rule lands with T051; until then {@link #health()} throws, so
 * that {@code PublicEventsHealthIndicatorTest} records a failing assertion rather than a compile
 * error. It is annotated with nothing, so no context registers a component that would answer by
 * throwing.
 */
public class PublicEventsHealthIndicator implements HealthIndicator {

    /**
     * Whether the durable subscription's container is running.
     *
     * <p>Asked rather than remembered: the listener container is the only thing that knows, it
     * already answers the question, and a copy of its state kept here would be a second answer to
     * drift from the first.
     *
     * <p>Suppressed only while this is a seam: {@link #health()} is the one thing that reads it, and
     * {@link #health()} lands with T051. The suppression goes with the throw.
     */
    @SuppressWarnings("PMD.UnusedPrivateField")
    private final BooleanSupplier subscriptionRunning;

    /** The clock the delivery age is measured against. */
    private final Clock clock;

    /** When an event was last delivered on the subscription; null until one has been. */
    private final AtomicReference<Instant> lastDelivery = new AtomicReference<>();

    /**
     * Creates the indicator.
     *
     * @param subscriptionRunning whether the durable subscription's container is running
     * @param clock               the clock the delivery age is measured against
     */
    public PublicEventsHealthIndicator(
            final BooleanSupplier subscriptionRunning, final Clock clock) {
        this.subscriptionRunning = subscriptionRunning;
        this.clock = clock;
    }

    /**
     * Records that an event was delivered on the subscription.
     *
     * <p>Every delivery, not only the two this service acts on: a document belonging to
     * progression's still-deployed leg arriving here is proof the subscription is being served, and
     * a component that counted only our own events would report a broker outage every evening the
     * legacy generated and this service did not.
     */
    public void recordDelivery() {
        lastDelivery.set(clock.instant());
    }

    @Override
    public Health health() {
        throw new UnsupportedOperationException("T051");
    }
}
