package uk.gov.hmcts.cp.courtregister.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import uk.gov.hmcts.cp.courtregister.batch.BatchAgeSweep;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;

/**
 * The three in-flight batch readings' refresh, on a thread of its own.
 *
 * <p>Behind {@code courtregister.generation.enabled}, and there for the reason
 * {@link IntakeSweepConfig} is not: these three gauges describe batches, and a pod that assembles
 * none has none to describe - three readings published by a pod that cannot hold a batch would be
 * three flat zeroes competing, under {@code max()}, with the readings of the pod that can.
 *
 * <p>This is the seam T031's cases are written against. T032 adds the scheduler bean and the
 * condition that keeps the sweep off a command JVM.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "courtregister.generation", name = "enabled",
        havingValue = "true")
public class BatchSweepConfig {

    /**
     * The bean name of the scheduler the sweep runs on.
     *
     * <p>Published for the reason {@link IntakeSweepConfig#INTAKE_SWEEP_SCHEDULER} is: the
     * {@code scheduler} attribute on the scheduled method names a constant rather than a string
     * spelled twice.
     */
    public static final String BATCH_SWEEP_SCHEDULER = "batchSweepScheduler";

    /**
     * The refresh itself, over the batches it reads and the instruments it publishes.
     *
     * @param batches the {@code register_batch} table, read for the oldest of each in-flight kind
     * @param metrics where the three gauges and the absorbed-failure counter live
     * @param clock   the clock the three ages are measured back from
     * @return the sweep the schedule fires
     */
    @Bean
    public BatchAgeSweep batchAgeSweep(final RegisterBatchRepository batches,
            final GenerationMetrics metrics, final Clock clock) {
        return new BatchAgeSweep(batches, metrics, clock);
    }
}
