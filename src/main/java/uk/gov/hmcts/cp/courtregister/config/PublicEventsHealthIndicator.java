package uk.gov.hmcts.cp.courtregister.config;

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
 * <p><strong>Seam only.</strong> The indicator lands with T051; until then this throws, so that
 * {@code PublicEventsHealthIndicatorTest} records a failing assertion rather than a compile error.
 * It is annotated with nothing, so no context registers a component that would answer by throwing.
 */
public class PublicEventsHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        throw new UnsupportedOperationException("T051");
    }
}
