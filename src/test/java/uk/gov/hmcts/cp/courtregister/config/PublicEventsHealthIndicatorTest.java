package uk.gov.hmcts.cp.courtregister.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import uk.gov.hmcts.cp.courtregister.support.AdjustableClock;

/**
 * The public-event subscription, reported without ever being allowed to roll a pod.
 *
 * <p>Two claims, and they pull in opposite directions on purpose.
 *
 * <p><strong>It reports.</strong> A nightly flow whose outcomes arrive on somebody else's topic has
 * exactly one observable that says the arrangement is still working, and it is the age of the last
 * delivery. A subscription that is connected and has heard nothing since a run began is a broker
 * problem the reconciler is about to hide, and this component is where an operator sees it before
 * the reconciled count says the same thing an hour later.
 *
 * <p><strong>It never gates readiness.</strong> A pod cannot heal a broker by restarting, so putting
 * the subscription in the readiness group converts a blip into a rolling restart of every replica
 * while the broker stays exactly as broken (spec FR-011, constitution). The membership is asserted
 * against the shipped {@code application.yaml}, because the group is configuration rather than code
 * and the only way to break this rule is to add a name to that line.
 *
 * <p>The corollary of reporting rather than judging is the case that looks like an omission and is
 * not: silence with the subscription up is <strong>not</strong> an outage. This service hears from
 * {@code public.event} only when somebody renders something, so a quiet Tuesday and a dead broker
 * look identical from here, and a component that reported DOWN for the first would be reporting the
 * working day.
 *
 * <p>Every case reaches the indicator through {@link #answer()} rather than calling
 * {@code health()} directly. A health check that throws is worse than one that answers DOWN - it
 * takes the endpoint that would have reported the problem down with it - so "it answers at all" is
 * the first assertion of every case rather than an assumption underneath them.
 */
@DisplayName("the public-event subscription's health component")
class PublicEventsHealthIndicatorTest {

    /**
     * The component's name in the health endpoint: Spring derives it from the bean name with the
     * {@code HealthIndicator} suffix removed, so this is the name a readiness group would have to
     * carry for the broker to gate a pod.
     */
    private static final String COMPONENT = "publicEvents";

    /** The two components the readiness group is allowed to name, from 001. */
    private static final String STORE_COMPONENT = "db";
    private static final String STARTUP_COMPONENT = "intakeStartup";

    /** The shipped configuration, read as text: what the group names is what the file says. */
    private static final Path APPLICATION_YAML =
            Path.of("src", "main", "resources", "application.yaml");

    private static final Instant STARTED = Instant.parse("2026-09-04T17:00:00Z");

    private final AdjustableClock clock = AdjustableClock.startingAt(STARTED);

    /** The listener container's state, which the indicator asks for rather than remembers. */
    private final AtomicBoolean running = new AtomicBoolean(true);

    private final PublicEventsHealthIndicator indicator =
            new PublicEventsHealthIndicator(running::get, clock);

    // --- what it reports --------------------------------------------------------------------

    @Test
    @DisplayName("a running subscription is UP and says so")
    void should_report_a_running_subscription_as_up() {
        assertThat(answer().getStatus()).isEqualTo(Status.UP);
        assertThat(answer().getDetails())
                .as("the subscription's own state, in one bounded word")
                .containsEntry("subscription", "running");
    }

    @Test
    @DisplayName("a stopped subscription is DOWN and says so")
    void should_report_a_stopped_subscription_as_down() {
        running.set(false);

        assertThat(answer().getStatus())
                .as("a subscription that is not running receives nothing, and the reconciler is a "
                        + "safety net rather than a second delivery route")
                .isEqualTo(Status.DOWN);
        assertThat(answer().getDetails()).containsEntry("subscription", "stopped");
    }

    @Test
    @DisplayName("the state is read when the question is asked, not when the component was built")
    void should_read_the_subscription_state_at_the_moment_it_is_asked() {
        assertThat(answer().getStatus()).isEqualTo(Status.UP);

        running.set(false);

        assertThat(answer().getStatus())
                .as("a copy of the container's state taken at construction would report a "
                        + "subscription that stopped an hour ago as running")
                .isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("the age of the last delivery is reported, and each delivery resets it")
    void should_report_how_long_ago_the_last_event_arrived() {
        indicator.recordDelivery();
        clock.advance(Duration.ofMinutes(30));

        assertThat(answer().getDetails())
                .as("the one observable that says the arrangement is still working")
                .containsEntry("lastDeliveryAt", STARTED.toString())
                .containsEntry("lastDeliveryAgeSeconds", Duration.ofMinutes(30).toSeconds());

        indicator.recordDelivery();

        assertThat(answer().getDetails())
                .as("a delivery answers the silence before it")
                .containsEntry("lastDeliveryAt", STARTED.plus(Duration.ofMinutes(30)).toString())
                .containsEntry("lastDeliveryAgeSeconds", 0L);
    }

    @Test
    @DisplayName("a subscription nothing has yet been delivered on says so rather than guessing")
    void should_report_no_delivery_at_all_before_the_first_event() {
        assertThat(answer().getDetails())
                .as("a pod that has just started has no age to report, and zero would read as a "
                        + "delivery that has just arrived")
                .containsEntry("lastDeliveryAt", "none")
                .containsEntry("lastDeliveryAgeSeconds", "none");
        assertThat(answer().getStatus())
                .as("having heard nothing yet is how every pod starts")
                .isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("silence on a running subscription is reported, never called an outage")
    void should_not_read_silence_as_an_outage() {
        indicator.recordDelivery();
        clock.advance(Duration.ofHours(18));

        assertThat(answer().getStatus())
                .as("this service hears from public.event only when somebody renders something, so "
                        + "a quiet night and a dead broker look identical from here")
                .isEqualTo(Status.UP);
        assertThat(answer().getDetails())
                .as("and the age is published so that a human, or an alert rule that also knows a "
                        + "run started, can draw the conclusion this component may not")
                .containsEntry("lastDeliveryAgeSeconds", Duration.ofHours(18).toSeconds());
    }

    @Test
    @DisplayName("nothing in the details identifies a hearing, a defendant or a recipient")
    void should_report_only_bounded_words_instants_and_numbers() {
        indicator.recordDelivery();

        assertThat(answer().getDetails())
                .as("a health endpoint is scraped and indexed like any other surface "
                        + "(constitution Principle VII)")
                .containsOnlyKeys("subscription", "lastDeliveryAt", "lastDeliveryAgeSeconds");
    }

    // --- what it must never gate -------------------------------------------------------------

    @Test
    @DisplayName("the readiness group never names the subscription")
    void should_stay_out_of_the_readiness_group() throws IOException {
        assertThat(readinessGroup())
                .as("a broker blip must never roll the pods: the subscription is its own component, "
                        + "outside the group (spec FR-011)")
                .doesNotContain(COMPONENT)
                .contains(STORE_COMPONENT, STARTUP_COMPONENT);
    }

    @Test
    @DisplayName("the auto-configured jms indicator stays off, so silence cannot take the aggregate down")
    void should_keep_the_auto_configured_jms_indicator_disabled() throws IOException {
        assertThat(jmsHealthSetting())
                .as("Boot contributes a `jms` indicator the moment a ConnectionFactory is on the "
                        + "context, and it goes DOWN whenever the broker is quiet - which would put "
                        + "the whole service DOWN for the silence this component reports calmly")
                .isEqualTo("enabled: false");
    }

    // --- helpers ------------------------------------------------------------------------------

    /**
     * The indicator's answer, obtained through an assertion.
     *
     * <p>A component that throws takes the health endpoint with it, which is the one thing a health
     * check may never do, so every case states that first and reads the details second.
     *
     * @return what the indicator answered
     */
    private Health answer() {
        final AtomicReference<Health> answered = new AtomicReference<>();
        assertThatCode(() -> answered.set(indicator.health()))
                .as("a health check answers; one that throws takes down the endpoint that would "
                        + "have reported the problem")
                .doesNotThrowAnyException();
        return answered.get();
    }

    /**
     * The components the shipped readiness group names.
     *
     * <p>Read as text rather than bound as properties, for the reason {@code TelemetryPrivacyTest}
     * reads the logging block that way: the claim is about what the file <em>ships</em>, and a
     * binder would hand back the merged view of every source, including whatever the harness set.
     *
     * @return the names on the group's {@code include} line
     */
    private static List<String> readinessGroup() throws IOException {
        final String yaml = Files.readString(APPLICATION_YAML);
        final int group = yaml.indexOf("\n        readiness:");
        assertThat(group).as("application.yaml declares a readiness group").isNotNegative();
        final String include = yaml.substring(group).lines()
                .map(String::strip)
                .filter(line -> line.startsWith("include:"))
                .findFirst()
                .orElse("");
        assertThat(include).as("the readiness group names its members").startsWith("include:");
        return Arrays.stream(include.substring("include:".length()).split(","))
                .map(String::strip)
                .toList();
    }

    /**
     * The one setting under the shipped {@code management.health.jms} block.
     *
     * @return the setting, with its comments taken out
     */
    private static String jmsHealthSetting() throws IOException {
        final String yaml = Files.readString(APPLICATION_YAML);
        final int jms = yaml.indexOf("\n    jms:");
        assertThat(jms).as("application.yaml settles the auto-configured jms indicator")
                .isNotNegative();
        return yaml.substring(jms).lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .skip(1)
                .findFirst()
                .orElse("");
    }
}
