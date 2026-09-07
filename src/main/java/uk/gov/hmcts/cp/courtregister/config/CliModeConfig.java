package uk.gov.hmcts.cp.courtregister.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * The one place {@code courtregister.cli} is read, and what it turns off.
 *
 * <p>An operations command runs the deployed context - the same settings, the same startup
 * refusals, the same live generation adapters - because a regeneration against a stub would be a
 * night's registers nobody sent. Three things must not come up with it, and each for its own
 * reason:
 *
 * <ul>
 *   <li><strong>The Service Bus consumer.</strong> A command that consumed a delivery would record
 *       a register as a side effect of listing one, and it would take that delivery from the pod
 *       whose job it is - {@code max-concurrent-calls} is shared by every consumer on the
 *       queue.</li>
 *   <li><strong>The nightly scheduler.</strong> The lock makes the 18:00 run one run; a CLI pod
 *       that held a scheduler would be a second replica of it, and a command that ran long enough
 *       to reach 18:00 London would generate the night twice.</li>
 *   <li><strong>The public-event listener container.</strong> The durable subscription admits
 *       exactly one consumer, so a command that subscribed would take the topic away from the pod
 *       waiting for the outcomes and give them to a process about to exit.</li>
 * </ul>
 *
 * <p>It is deliberately not the inverse of {@code courtregister.generation.enabled}: the point of
 * the flag is that a command and the schedule read the same configuration, and a switch that also
 * decided which adapters were live would be a second cutover lever (constitution Cutover Rule). It
 * decides who starts, and nothing else.
 *
 * <p>Off wherever nothing says otherwise, so an ordinary pod is unaffected by the property
 * existing. {@link uk.gov.hmcts.cp.courtregister.batch.cli.CliMain} passes
 * {@code --courtregister.cli=true} on the invocations {@code docker/startup.sh} dispatches to it -
 * as a command-line property, so {@code application.yaml}'s {@code false} cannot win over it. The
 * script itself never names the property: what it decides is whether an invocation reaches
 * {@code CliMain} at all, and a JVM that got there sets this itself.
 *
 * <p><strong>The three conditionals are on the configurations rather than on the beans.</strong>
 * {@code ServiceBusConsumerConfig} owns both the processor client and the one component permitted
 * to start it, {@code SchedulingConfig} owns {@code @EnableScheduling} as well as the job, and
 * {@code PublicEventsConfig} owns the container factory as well as the listener - so switching the
 * configuration off is what makes the absence complete. A bean-level condition would leave a client
 * nothing can start, a scheduler with nothing on it, or a container factory with no listener to
 * create one from: three half-absences to reason about instead of three plain ones.
 *
 * <p>Readiness is unaffected by all three. {@code intakeStartup} is contributed by
 * {@link IntakeStartupHealth}, which is deliberately outside the consumer's own configuration and
 * asks for the lifecycle controller rather than requiring one - so a CLI JVM answers UP with
 * {@code no-consumer-configured}, exactly as an intake-only pod does, and the readiness group's
 * membership check still finds every name {@code application.yaml} lists.
 */
public final class CliModeConfig {

    /**
     * The property, named here because three configurations condition on it and a name that only
     * agreed by convention would be a listener nobody stopped.
     */
    public static final String CLI_PROPERTY = "courtregister.cli";

    /** What the three conditionals require of it, which is that it is not on. */
    public static final String NOT_CLI = "false";

    private CliModeConfig() {
        // The property's name, its default, and the one condition that reads it.
    }

    /**
     * Matches every context except a JVM started to run one operations command and exit.
     *
     * <p>A {@link Condition} rather than {@code @ConditionalOnProperty}, because the two
     * configurations it goes on beside {@code SchedulingConfig} already carry one of those and the
     * annotation is not repeatable: an intake-only pod and a CLI JVM are two different reasons for
     * the same class to be absent, and both have to be able to say so. It reads the environment
     * directly, which is all a condition can do - conditions are evaluated before any bean exists.
     *
     * <p>Absent means not in CLI mode, so an ordinary pod is unaffected by the property existing at
     * all, and anything other than {@code true} is read the same way: this switch decides who
     * starts, and an unparseable value must not be able to stop a pod consuming.
     */
    public static final class NotCliMode implements Condition {

        @Override
        public boolean matches(final ConditionContext context,
                final AnnotatedTypeMetadata metadata) {

            return !Boolean.parseBoolean(
                    context.getEnvironment().getProperty(CLI_PROPERTY, NOT_CLI));
        }
    }
}
