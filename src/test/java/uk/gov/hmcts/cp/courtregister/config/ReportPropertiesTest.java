package uk.gov.hmcts.cp.courtregister.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Holds the morning report's settings to the values the plan's configuration table documents, and
 * holds the one undefaulted duration to the resolution data-model.md writes down for it.
 *
 * <p>The binding half of the report's configuration surface. What the settings <em>refuse</em> is
 * {@code ConfigurationValidationTest.ReportRefusals}, in the suite that owns every other startup
 * refusal this service makes; what they bind to is here, because a default nobody reads back is a
 * default that moves without anybody noticing.
 *
 * <p>{@code courtregister.intake.gauge-refresh} is asserted here too, although it is not a
 * {@code courtregister.report} key. It is the intake half's, deliberately: the sweep that reads it
 * runs on a pod where both other halves are switched off, so a key on a record such a pod does not
 * bind is a key it cannot read - and that is exactly the kind of fact a test has to state.
 */
class ReportPropertiesTest {

    /** The emulator connection string, the local and CI credential. */
    private static final String CONNECTION_STRING_PROPERTY =
            "courtregister.servicebus.connection-string="
                    + "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;"
                    + "SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;";

    /** The identity the query-side payload fallback authorises with; the live source demands one. */
    private static final String PAYLOAD_IDENTITY_PROPERTY =
            "courtregister.results.system-user-id=9f61bdbb-6f1a-4c0f-9a3d-6b8f0f1c2a44";

    private static final String PROGRESSION_ENDPOINT_PROPERTY =
            "courtregister.progression.base-url=http://localhost:8080";

    private static final String PROGRESSION_IDENTITY_PROPERTY =
            "courtregister.progression.system-user-id=4d3c2b1a-9e8f-4a7b-8c6d-5e4f3a2b1c09";

    private static final String REFDATA_ENDPOINT_PROPERTY =
            "courtregister.referencedata.base-url=http://localhost:8080";

    private static final String REFDATA_IDENTITY_PROPERTY =
            "courtregister.referencedata.system-user-id=2c7b1e64-0f4a-4f0e-9b2c-8d1a6f3e5c07";

    /** The key user story 5 scenario 3 is about: one threshold, changed in one environment. */
    private static final String THRESHOLD = "courtregister.report.request-terminal-within=";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PropertiesTestConfiguration.class)
                    .withPropertyValues(CONNECTION_STRING_PROPERTY, PAYLOAD_IDENTITY_PROPERTY,
                            PROGRESSION_ENDPOINT_PROPERTY, PROGRESSION_IDENTITY_PROPERTY,
                            REFDATA_ENDPOINT_PROPERTY, REFDATA_IDENTITY_PROPERTY);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CourtRegisterProperties.class, GenerationProperties.class,
        FeatureFlagProperties.class, ReportProperties.class})
    @Import(PropertiesValidator.class)
    static class PropertiesTestConfiguration {
    }

    @Test
    @DisplayName("the report ships the defaults the plan's configuration table documents")
    void report_defaults_are_the_documented_ones() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            final ReportProperties report = context.getBean(ReportProperties.class);

            assertThat(report.enabled())
                    .as("off locally, so a developer's run writes nobody a report")
                    .isFalse();
            assertThat(report.cron()).isEqualTo("0 0 7 * * MON-FRI");
            assertThat(report.zone()).isEqualTo("Europe/London");
            assertThat(report.zoneOverrideAcknowledged()).isFalse();
            assertThat(report.lockAtMostFor()).isEqualTo(Duration.ofMinutes(15));
            assertThat(report.requestTerminalWithin()).isEqualTo(Duration.ofMinutes(30));
            assertThat(report.notifiedWithin()).isEqualTo(Duration.ofMinutes(15));
            assertThat(report.batchGeneratedWithin())
                    .as("the one undefaulted duration: unset binds null and is resolved, never"
                            + " written twice")
                    .isNull();
            assertThat(report.email().enabled())
                    .as("false until the notificationnotify template is provided")
                    .isFalse();
            assertThat(report.email().templateId()).isNull();
            assertThat(report.email().recipients()).isEmpty();

            assertThat(context.getBean(CourtRegisterProperties.class).intake().gaugeRefresh())
                    .as("the sweep's own interval, on the intake record a pod with both other"
                            + " halves off still binds")
                    .isEqualTo(Duration.ofMinutes(10));
        });
    }

    @Test
    @DisplayName("an unset rendering limit is the generation half's grace period, not a second copy")
    void an_unset_batch_generated_within_resolves_to_the_generation_grace_period() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            final ReportProperties report = context.getBean(ReportProperties.class);
            final GenerationProperties generation = context.getBean(GenerationProperties.class);

            assertThat(PropertiesValidator.resolvedBatchGeneratedWithin(report, generation))
                    .isEqualTo(generation.gracePeriod())
                    .isEqualTo(Duration.ofMinutes(10));
        });

        // And it follows the grace period rather than a literal ten minutes, which is the whole
        // reason the key has no default: one answer to "how long is too long for a render", moved
        // in one place.
        runner.withPropertyValues("courtregister.generation.grace-period=25m").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(PropertiesValidator.resolvedBatchGeneratedWithin(
                    context.getBean(ReportProperties.class),
                    context.getBean(GenerationProperties.class)))
                    .isEqualTo(Duration.ofMinutes(25));
        });
    }

    /**
     * The other half of the resolution rule, and the half that says the borrowing is a default
     * rather than an override: a deployment that states its own rendering limit gets its own, and
     * the grace period sitting beside it does not quietly win. One answer applies at a time, which
     * is the whole reason the key has no default of its own.
     */
    @Test
    @DisplayName("an explicitly set rendering limit is the deployment's own, not the grace period")
    void an_explicit_batch_generated_within_is_honoured() {
        runner.withPropertyValues("courtregister.report.batch-generated-within=20m",
                "courtregister.generation.grace-period=10m").run(context -> {
                    assertThat(context).hasNotFailed();
                    final GenerationProperties generation =
                            context.getBean(GenerationProperties.class);

                    assertThat(PropertiesValidator.resolvedBatchGeneratedWithin(
                            context.getBean(ReportProperties.class), generation))
                            .isEqualTo(Duration.ofMinutes(20))
                            .as("the deployment's own limit, not the grace period beside it")
                            .isNotEqualTo(generation.gracePeriod());
                });
    }

    /**
     * SC-005, user story 5 scenario 3: a threshold is per environment and takes effect on the next
     * run, without a release.
     *
     * <p>Asserted rather than asserted about. Two contexts differing in exactly one property yield
     * two resolved thresholds that differ and nothing else that does - which is what "changed in
     * that environment alone" means when it is a claim a test can fail on.
     */
    @Test
    @DisplayName("a threshold changed in one environment changes that environment and nothing else")
    void a_changed_threshold_takes_effect_in_that_environment_alone() {
        final ReportProperties shipped = boundWith(THRESHOLD + "30m");
        final ReportProperties widened = boundWith(THRESHOLD + "90m");

        assertThat(shipped.requestTerminalWithin()).isEqualTo(Duration.ofMinutes(30));
        assertThat(widened.requestTerminalWithin()).isEqualTo(Duration.ofMinutes(90));
        assertThat(widened).usingRecursiveComparison()
                .ignoringFields("requestTerminalWithin")
                .as("one setting moved, and the schedule, the lock, the other two limits and the"
                        + " e-mail output did not")
                .isEqualTo(shipped);
    }

    /** Binds the report record in a context carrying the given extra properties. */
    private ReportProperties boundWith(final String... properties) {
        final AtomicReference<ReportProperties> bound = new AtomicReference<>();
        runner.withPropertyValues(properties).run(context -> {
            assertThat(context).hasNotFailed();
            bound.set(context.getBean(ReportProperties.class));
        });
        return bound.get();
    }
}
