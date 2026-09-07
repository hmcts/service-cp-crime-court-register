package uk.gov.hmcts.cp.courtregister.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

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
 * existing; {@code docker/startup.sh} sets it only on the invocations it dispatches to
 * {@link uk.gov.hmcts.cp.courtregister.batch.cli.CliMain}.
 *
 * <p>The property is wired here; the three conditionals it drives arrive with T065.
 */
@Configuration(proxyBeanMethods = false)
public class CliModeConfig {

    /**
     * The property, named here because three configurations condition on it and a name that only
     * agreed by convention would be a listener nobody stopped.
     */
    public static final String CLI_PROPERTY = "courtregister.cli";

    /** What the three conditionals require of it, which is that it is not on. */
    public static final String NOT_CLI = "false";

    private final boolean cli;

    /**
     * Reads the property once.
     *
     * @param cli whether this JVM was started to run one operations command and exit
     */
    public CliModeConfig(@Value("${" + CLI_PROPERTY + ":" + NOT_CLI + "}") final boolean cli) {
        this.cli = cli;
    }

    /**
     * Whether this JVM was started to run one operations command and exit.
     *
     * @return true where {@code courtregister.cli} is on
     */
    public boolean cliMode() {
        return cli;
    }
}
