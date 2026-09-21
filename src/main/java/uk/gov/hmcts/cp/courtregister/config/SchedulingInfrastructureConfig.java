package uk.gov.hmcts.cp.courtregister.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import uk.gov.hmcts.cp.courtregister.batch.RegisterGenerationJob;

/**
 * What makes a schedule happen at all on this JVM, and the lock the locked ones take.
 *
 * <p>Three declarations, and Spring permits exactly one of each per context:
 * {@code @EnableScheduling}, without which nothing reads {@code @Scheduled} off a bean;
 * {@code @EnableSchedulerLock}, without which {@code @SchedulerLock} is an annotation nothing
 * intercepts; and the one {@link LockProvider} both of those rest on. They lived in
 * {@link SchedulingConfig} until this increment, which meant they lived behind
 * {@code courtregister.generation.enabled} - so a pod deployed without the downstream half
 * processed no schedule of any kind.
 *
 * <p><strong>Conditional on nothing but not being a command JVM.</strong> No enabled-flag
 * condition at all, and that is the decision rather than an omission. {@code IntakeAgeSweep} must
 * refresh the two intake gauges wherever the intake half runs, which includes a pod with the
 * report and the generation half both switched off - and that pod is exactly the one whose stuck
 * requests nothing else would report. A condition of generation-or-report was considered and
 * rejected for that reason: it would leave the deployment the gauges were added for without
 * {@code @Scheduled} processing, which is the failure wearing the instrument that was supposed to
 * reveal it. The cost of being unconditional is an idle scheduler on a pod that schedules nothing;
 * {@code CliModeConfigTest} proves it is not even that on a command JVM.
 *
 * <p><strong>And not on a JVM started to run one operations command.</strong> The CLI condition is
 * the one that does matter here, and it is the one {@link CliModeConfig} has always described: a
 * command that held a scheduler would be a second replica of every schedule in the service - the
 * 18:00 run, the 07:00 report, the intake gauge refresh and the batch-age refresh - and a command
 * that ran long enough to reach any of their hours would fire it. The annotation is here now, so the
 * condition is here too, and the two configurations that sit on top of this one carry it as well:
 * a bean-level condition would leave a scheduler with nothing on it rather than a plain absence.
 *
 * <p>{@code @Profile("!test")} because the provider needs a {@code DataSource} and that profile
 * deliberately has none: the plain context-load tests must keep running with no broker, no database
 * and therefore without Docker.
 *
 * <p><strong>The lock default moves verbatim.</strong> {@code defaultLockAtMostFor} is the fallback
 * for a {@code @SchedulerLock} that states no duration of its own, and both locked methods on this
 * context state theirs - the nightly run and the morning report - so the value it carries is
 * inert. That is precisely why it is left alone rather than tidied to the report's
 * budget on the way past: changing it here would be a change to generation's lock semantics made in
 * a commit about the report's wiring.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@Conditional(CliModeConfig.NotCliMode.class)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = RegisterGenerationJob.LOCK_AT_MOST_FOR)
public class SchedulingInfrastructureConfig {

    /** The table V2 creates for the lock, named here because the provider will not guess it. */
    private static final String SHEDLOCK_TABLE = "shedlock";

    /**
     * The lock every locked schedule in this service takes, over the processed log's own store.
     *
     * <p>One provider, because two over one {@code shedlock} table is a race dressed as
     * configuration. The lock is taken on the database's clock rather than on the pods', because
     * two JVMs a few seconds apart is exactly the skew a lock is supposed to survive.
     *
     * @param dataSource the processed log's own datasource, which is where {@code shedlock} is
     * @return the JDBC lock provider, taking the lock on the database's clock
     */
    @Bean
    public LockProvider lockProvider(final DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(SHEDLOCK_TABLE)
                .usingDbTime()
                .build());
    }
}
