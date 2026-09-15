package uk.gov.hmcts.cp.courtregister.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.application.DocumentRenderer;
import uk.gov.hmcts.cp.courtregister.application.ExceptionReportService;
import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.application.PayloadFileStore;
import uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService;
import uk.gov.hmcts.cp.courtregister.application.RegisterNotifier;
import uk.gov.hmcts.cp.courtregister.application.RegisterNotifierService;
import uk.gov.hmcts.cp.courtregister.batch.BatchAssembler;
import uk.gov.hmcts.cp.courtregister.batch.ExceptionReportJob;
import uk.gov.hmcts.cp.courtregister.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.courtregister.batch.GenerationReconciler;
import uk.gov.hmcts.cp.courtregister.batch.IntakeAgeSweep;
import uk.gov.hmcts.cp.courtregister.batch.RegisterGenerationJob;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterNotificationRepository;

/**
 * What a given pod wires, read in one place.
 *
 * <p>Every infrastructure-presence case this increment adds lives here on purpose. There are three
 * configurations and two switches between them, and the question an operator actually asks is not
 * "is this class conditional on that property" but "what does the pod I am about to deploy run" -
 * so there is one class that answers it and one class to change when the answer changes.
 *
 * <p>The three shapes that matter are all below. A <strong>report pod</strong> generates nothing
 * and must still run the 07:00 report, which is FR-004 and the MVP's own deployment. An
 * <strong>intake-only pod</strong> has the report and the generation half both switched off and
 * must still refresh the two intake gauges, which is user story 2 scenario 4 - and that is the case
 * that decides the scheduling infrastructure carries no flag condition at all, because a condition
 * of generation-or-report would leave exactly that pod processing no {@code @Scheduled} and
 * therefore publishing no reading. A <strong>generating pod</strong> keeps everything it has today.
 * The fourth combination is here because four is what two switches have, and a table with a hole in
 * it is a deployment nobody checked.
 *
 * <p>The one shape that is <strong>not</strong> here is a command JVM, and it is not here because
 * it belongs beside the other two things a command must not start: {@code CliModeConfigTest} owns
 * the consumer, the public-event listener and now all three of these.
 *
 * <p>Nothing below connects to anything. The store, the two transports and the flag reader are
 * doubles, because what is under assertion is which beans a set of conditions contributes and not
 * what any of them would do - and a suite about wiring that needed a database would be a suite that
 * stopped being run.
 */
@DisplayName("what a pod wires, and which scheduler each half runs on")
class ReportSchedulingConfigTest {

    /** The two switches, as the properties a deployment actually sets. */
    private static final String REPORT_ENABLED = "courtregister.report.enabled=";

    private static final String GENERATION_ENABLED = "courtregister.generation.enabled=";

    /**
     * Every placeholder a {@code @Scheduled} on this context resolves.
     *
     * <p>Written out rather than left to {@code application.yaml}: a context runner carries no
     * config data, and a schedule whose cron could not be resolved fails the context for a reason
     * that has nothing to do with what is under assertion.
     */
    private static final String[] THE_SCHEDULES = {
        "courtregister.generation.cron=0 0 18 * * MON-FRI",
        "courtregister.generation.zone=Europe/London",
        "courtregister.generation.grace-period=10m",
        "courtregister.generation.lock-at-most-for=70m",
        "courtregister.report.cron=0 0 7 * * MON-FRI",
        "courtregister.report.zone=Europe/London",
        "courtregister.report.lock-at-most-for=15m",
        "courtregister.intake.gauge-refresh=10m",
        "courtregister.email.templates.cr_standard=5c9a0e21-3d47-4f18-9b62-0a71c4e8d530",
    };

    /** The thread name a single-threaded report scheduler is read by in a thread dump. */
    private static final String REPORT_THREAD_PREFIX = "exception-report-";

    /** And the sweep's, which must not be the report's or the run's. */
    private static final String SWEEP_THREAD_PREFIX = "intake-sweep-";

    /** One thread each, because both units of work are sequential by design. */
    private static final int ONE_THREAD = 1;

    /** The generation scheduler, the report's and the sweep's: three, and never a fourth. */
    private static final int THREE_SCHEDULERS = 3;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingInfrastructureConfig.class, SchedulingConfig.class,
                    ReportSchedulingConfig.class, IntakeSweepConfig.class, ProcessedLogConfig.class,
                    GenerationConfig.class, SchedulingWiringTestConfiguration.class)
            .withPropertyValues(THE_SCHEDULES);

    /**
     * Everything the six configurations under assertion ask of the world outside them.
     *
     * <p>Doubles for the store, the two outward transports and the flag reader; the real metrics
     * facades, the real clock type and the real settings records, because those are what the
     * configurations read values out of.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CourtRegisterProperties.class, GenerationProperties.class,
        ReportProperties.class})
    static class SchedulingWiringTestConfiguration {

        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean
        JdbcClient jdbcClient() {
            return mock(JdbcClient.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        Clock courtRegisterClock() {
            return Clock.fixed(Instant.parse("2026-09-15T07:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ProcessingMetrics processingMetrics(final MeterRegistry registry) {
            return new ProcessingMetrics(registry);
        }

        @Bean
        GenerationMetrics generationMetrics(final MeterRegistry registry) {
            return new GenerationMetrics(registry);
        }

        @Bean
        RunProgress runProgress() {
            return RunProgress.NONE;
        }

        @Bean
        ObjectMapper objectMapper() {
            return JacksonConfig.contractObjectMapper();
        }

        @Bean
        FeatureFlagReader featureFlagReader() {
            return mock(FeatureFlagReader.class);
        }

        @Bean
        DocumentRenderer documentRenderer() {
            return mock(DocumentRenderer.class);
        }

        @Bean
        PayloadFileStore payloadFileStore() {
            return mock(PayloadFileStore.class);
        }

        @Bean
        RegisterNotifier registerNotifier() {
            return mock(RegisterNotifier.class);
        }
    }

    @ParameterizedTest(name = "report={0} generation={1}")
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    @DisplayName("the scheduling infrastructure is there whatever the two switches say")
    void the_scheduling_infrastructure_is_present_whatever_the_two_flags_say(
            final boolean report, final boolean generation) {

        podWith(report, generation).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(SchedulingInfrastructureConfig.class))
                    .as("the annotation, the lock configuration and the provider carry no "
                            + "enabled-flag condition at all: a pod with both halves switched off "
                            + "still consumes the queue, and it is exactly the pod whose stuck "
                            + "requests nothing else would report")
                    .isNotEmpty();
            assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class))
                    .as("without @EnableScheduling nothing processes @Scheduled at all, so every "
                            + "schedule on the context is a comment")
                    .isNotEmpty();
        });
    }

    @ParameterizedTest(name = "report={0} generation={1}")
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    @DisplayName("and so is the sweep, on a single thread of its own")
    void the_sweep_is_declared_whatever_the_two_flags_say(
            final boolean report, final boolean generation) {

        podWith(report, generation).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(IntakeAgeSweep.class))
                    .as("the gauges belong to the intake half, so they refresh wherever the intake "
                            + "half runs - which is every service JVM (user story 2 scenario 4)")
                    .isNotEmpty();
            assertThat(scheduler(context, IntakeSweepConfig.INTAKE_SWEEP_SCHEDULER))
                    .as("its own scheduler, so the fixed-delay refresh cannot land on the thread "
                            + "the 18:00 run, the reconciler or the 07:00 report is using (SC-008)")
                    .isNotNull()
                    .satisfies(sweeps -> {
                        assertThat(sweeps.getThreadNamePrefix()).isEqualTo(SWEEP_THREAD_PREFIX);
                        assertThat(sweeps.getPoolSize()).isEqualTo(ONE_THREAD);
                    });
        });
    }

    @Test
    @DisplayName("the report job is declared only where the report is switched on")
    void the_report_job_is_declared_only_when_the_report_is_enabled() {
        podWith(true, false).run(context -> assertThat(
                context.getBeanNamesForType(ExceptionReportJob.class))
                .as("FR-004: the report runs on a pod that generates nothing, so its switch is its "
                        + "own and not the generation half's")
                .isNotEmpty());

        podWith(false, true).run(context -> assertThat(
                context.getBeanNamesForType(ExceptionReportJob.class))
                .as("and a pod the report is switched off on holds no job: a schedule nobody asked "
                        + "for is an e-mail nobody asked for")
                .isEmpty());
    }

    @Test
    @DisplayName("the report runs on a single thread named after itself")
    void the_report_has_its_own_single_thread_task_scheduler_named_exception_report() {
        podWith(true, true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(scheduler(context, ReportSchedulingConfig.REPORT_SCHEDULER))
                    .as("one thread, because the run is sequential by design and a pool would only "
                            + "make it look otherwise")
                    .isNotNull()
                    .satisfies(reports -> {
                        assertThat(reports.getThreadNamePrefix()).isEqualTo(REPORT_THREAD_PREFIX);
                        assertThat(reports.getPoolSize()).isEqualTo(ONE_THREAD);
                    });
        });
    }

    @Test
    @DisplayName("and it is neither the generation scheduler nor the sweep's")
    void the_report_scheduler_is_neither_the_generation_scheduler_nor_the_sweeps() {
        podWith(true, true).run(context -> {
            assertThat(context).hasNotFailed();
            final TaskScheduler reports =
                    scheduler(context, ReportSchedulingConfig.REPORT_SCHEDULER);

            assertThat(reports)
                    .as("SC-008: a 07:00 report queued behind an 18:00 run that overran is a "
                            + "report that does not happen")
                    .isNotSameAs(scheduler(context, SchedulingConfig.GENERATION_SCHEDULER))
                    .isNotSameAs(scheduler(context, IntakeSweepConfig.INTAKE_SWEEP_SCHEDULER));
            assertThat(context.getBeanNamesForType(TaskScheduler.class))
                    .as("three, and never a fourth: a scheduler nothing names is a thread nothing "
                            + "runs on")
                    .hasSize(THREE_SCHEDULERS);
        });
    }

    @Test
    @DisplayName("each configuration publishes its scheduler's bean name as a constant")
    void each_configuration_publishes_its_schedulers_bean_name_as_a_constant() {
        assertThat(SchedulingConfig.GENERATION_SCHEDULER)
                .as("the bean that already exists, named rather than declared again - no bean is "
                        + "added, renamed or moved by the attribute that reads this")
                .isEqualTo("registerGenerationScheduler");

        podWith(true, true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(scheduler(context, SchedulingConfig.GENERATION_SCHEDULER))
                    .as("a bean name spelled twice is a bean name that can be renamed once")
                    .isNotNull();
            assertThat(scheduler(context, ReportSchedulingConfig.REPORT_SCHEDULER)).isNotNull();
            assertThat(scheduler(context, IntakeSweepConfig.INTAKE_SWEEP_SCHEDULER)).isNotNull();
        });
    }

    @Test
    @DisplayName("there is exactly one lock provider over the one shedlock table")
    void there_is_exactly_one_lock_provider() {
        podWith(true, false).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(LockProvider.class))
                    .as("two providers over one shedlock table is a race dressed as configuration, "
                            + "and none at all is a report pod whose 07:00 run is unlocked")
                    .hasSize(1);
        });

        podWith(false, false).run(context -> assertThat(
                context.getBeanNamesForType(LockProvider.class))
                .as("the pod that schedules only the sweep still holds the provider, because the "
                        + "annotation that needs one is unconditional")
                .hasSize(1));
    }

    @Test
    @DisplayName("and the scheduler-lock default moved across unchanged")
    void the_scheduler_lock_default_is_unchanged() {
        final EnableSchedulerLock moved =
                SchedulingInfrastructureConfig.class.getAnnotation(EnableSchedulerLock.class);

        assertThat(moved)
                .as("the lock configuration moves with the annotation it belongs to; left behind "
                        + "on a configuration conditional on the generation half, an intake-only "
                        + "pod would process @SchedulerLock with nothing to lock against")
                .isNotNull();
        assertThat(moved == null ? null : moved.defaultLockAtMostFor())
                .as("verbatim: the default is the fallback for a @SchedulerLock that states no "
                        + "duration, all three locked methods state their own, so it is inert - "
                        + "and tidying an inert value to the report's budget would be a change to "
                        + "generation's lock semantics made in a task about the report's wiring")
                .isEqualTo(RegisterGenerationJob.LOCK_AT_MOST_FOR);
        assertThat(SchedulingConfig.class.getAnnotation(EnableSchedulerLock.class))
                .as("and it is not in both places, or a pod would carry two lock configurations")
                .isNull();
    }

    @Test
    @DisplayName("the generation beans are exactly what they were")
    void the_generation_beans_are_unchanged() {
        podWith(false, true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(RegisterGenerationJob.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(GenerationReconciler.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(BatchAssembler.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(FeatureFlagGate.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(RegisterGenerationService.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(RegisterNotifierService.class))
                    .as("everything generation-only stays where it was; only the two repositories "
                            + "the report reads leave, and they leave because the report reads them")
                    .isNotEmpty();
            assertThat(scheduler(context, SchedulingConfig.GENERATION_SCHEDULER)).isNotNull();
        });

        podWith(true, false).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(RegisterGenerationJob.class))
                    .as("and a report pod generates nothing, which is the whole of FR-004")
                    .isEmpty();
            assertThat(context.getBeanNamesForType(GenerationReconciler.class)).isEmpty();
        });
    }

    @Test
    @DisplayName("the report wires end to end on a pod with the generation half switched off")
    void the_report_wires_with_generation_disabled() {
        podWith(true, false).run(context -> {
            assertThat(context)
                    .as("FR-004's deployment and the MVP's own shape: the two repositories the "
                            + "report reads are declared inside a configuration conditional on the "
                            + "generation half, so this context cannot start until they move")
                    .hasNotFailed();
            assertThat(context.getBeanNamesForType(ExceptionReportService.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(ExceptionReportJob.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(RegisterBatchRepository.class))
                    .as("BATCH_LATE and BATCH_FAILED are read through it, on a pod that assembles "
                            + "no batch of its own")
                    .isNotEmpty();
            assertThat(context.getBeanNamesForType(RegisterNotificationRepository.class))
                    .as("and NOTIFICATION_FAILED through this one, on a pod that sends no register")
                    .isNotEmpty();
        });
    }

    /**
     * The runner for one pod's pair of switches.
     *
     * @param report     whether the morning report is switched on
     * @param generation whether the downstream half is
     * @return the runner, ready to run one context
     */
    private ApplicationContextRunner podWith(final boolean report, final boolean generation) {
        return runner.withPropertyValues(REPORT_ENABLED + report, GENERATION_ENABLED + generation);
    }

    /**
     * One scheduler by the bean name its own configuration publishes.
     *
     * @param context the context under assertion
     * @param name    the published constant
     * @return the scheduler, or {@code null} where this pod declares none under that name
     */
    private static ThreadPoolTaskScheduler scheduler(
            final ApplicationContext context, final String name) {
        return context.containsBean(name)
                ? context.getBean(name, ThreadPoolTaskScheduler.class)
                : null;
    }
}
