package uk.gov.hmcts.cp.courtregister.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Whether the file service is reachable, which matters only while a run is in progress.
 *
 * <p>The second datasource is used for a few seconds a night and not at all for the rest of it, so a
 * file service that is unreachable at 09:00 is not a pod that should roll: the intake half is still
 * recording registers, which is the half that must not stop. It gates readiness only while a
 * generation run is actually in progress, when an unreachable payload store means the run about to
 * fail every batch PAYLOAD_STORE_UNAVAILABLE.
 *
 * <p>That is a narrower rule than "in the readiness group" and a wider one than "never", and it is
 * stated here rather than by the group's membership because the condition is a fact about the run
 * rather than about the configuration.
 *
 * <p><strong>Seam only.</strong> The indicator lands with T051; until then this throws, so that
 * {@code FileServiceRunHealthIndicatorTest} records a failing assertion rather than a compile error.
 * It is annotated with nothing, so no context registers a component that would answer by throwing.
 */
public class FileServiceRunHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        throw new UnsupportedOperationException("T051");
    }
}
