package uk.gov.hmcts.cp.courtregister.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The settings the downstream half of the service runs under.
 *
 * <p>Bound from the {@code courtregister.generation} keys, which already exist in
 * {@code application.yaml}. Defaults live here as well as there for the same reason 001's do: the
 * values are visible to the code that depends on them, and a missing configuration file cannot
 * silently change when the night's registers are generated.
 *
 * <p><strong>{@code enabled} is the master switch and not the cutover lever.</strong> It decides
 * whether the job, the listener and the second datasource exist at all in this deployment; the one
 * thing that decides which implementation generates is the App Configuration flag
 * {@code CourtRegisterService}, read per run (constitution Cutover Rule).
 *
 * <p>{@link #validate()} carries the two refusals this record can reach on its own - the zone the
 * schedule is read in and the completion mechanism's vocabulary. Everything that becomes required
 * once {@link #enabled} is true is a relationship between records, so {@link PropertiesValidator}
 * owns it, including the P9 pin
 * {@code ConfigurationValidationTest.blank_email_template_refuses_to_start_in_live_mode}.
 *
 * @param enabled                  master switch for the job, the listener and the second datasource
 * @param cron                     the schedule, in Spring's six-field dialect
 * @param zone                     the zone the cron is read in; {@code Europe/London} unless
 *                                 acknowledged otherwise
 * @param zoneOverrideAcknowledged the deliberate acknowledgement that permits another zone
 * @param runDeadline              the bound on how long one run may go on requesting renders
 * @param lockAtMostFor            how long the ShedLock lock is held for, which must outlast the
 *                                 run deadline by {@link PropertiesValidator#SCHEDULER_LOCK_MARGIN}
 * @param gracePeriod              how long a batch may stay GENERATING before the reconciler asks
 * @param completion               {@code event} or the loudly logged {@code poll-only} escape hatch
 * @param sdgMode                  live or stubbed systemdocgenerator
 * @param nnMode                   live or stubbed notificationnotify
 * @param fileserviceMode          live or stubbed payload store
 * @param flagMode                 live or stubbed feature-flag reader
 */
@ConfigurationProperties(prefix = "courtregister.generation")
public record GenerationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("0 0 18 * * MON-FRI") String cron,
        @DefaultValue("Europe/London") String zone,
        @DefaultValue("false") boolean zoneOverrideAcknowledged,
        @DefaultValue("60m") Duration runDeadline,
        @DefaultValue("70m") Duration lockAtMostFor,
        @DefaultValue("10m") Duration gracePeriod,
        @DefaultValue("event") String completion,
        @DefaultValue(LIVE) SourceMode sdgMode,
        @DefaultValue(LIVE) SourceMode nnMode,
        @DefaultValue(LIVE) SourceMode fileserviceMode,
        @DefaultValue(LIVE) SourceMode flagMode) {

    /**
     * The one zone the requirement is written in: 18:00 wall clock, in BST and GMT alike.
     *
     * <p>The legacy fires in the scheduling JVM's default zone because its trigger is built without
     * one. That ambiguity is not inherited - the zone is a value this service states, and startup
     * holds it to this one unless an operator says otherwise deliberately.
     */
    public static final String COURTS_ZONE = "Europe/London";

    /** Outcomes learned from systemdocgenerator's public events - the platform pattern. */
    public static final String COMPLETION_EVENT = "event";

    /** Outcomes learned by asking the query API - the escape hatch for a broker outage. */
    public static final String COMPLETION_POLL_ONLY = "poll-only";

    private static final String PREFIX = "courtregister.generation";
    private static final String ZONE = PREFIX + ".zone";
    private static final String ZONE_OVERRIDE_ACKNOWLEDGED = PREFIX + ".zone-override-acknowledged";
    private static final String COMPLETION = PREFIX + ".completion";

    /**
     * The default every downstream mode takes, named once.
     *
     * <p>One constant rather than four literals, which is the opposite of the choice
     * {@link CourtRegisterProperties} makes for its four ten-second timeouts: those agree by
     * coincidence and are four independent settings, whereas these four are one rule - LIVE is what
     * an environment that says nothing gets, and no deployment ever wants three of them live and one
     * stubbed.
     */
    private static final String LIVE = "LIVE";

    /**
     * Which implementation of one downstream the service runs with.
     *
     * <p>A setting rather than a Spring profile, for the reason {@link PayloadSourceMode} gives:
     * "which adapter is deployed" is a question an operator must be able to answer from the
     * configuration in front of them.
     */
    public enum SourceMode {

        /** The real downstream. The deployed value, and the default. */
        LIVE,

        /** The local and container-suite stand-in, refused wherever generation is enabled. */
        STUB
    }

    /**
     * Refuses a schedule that would run at the wrong hour and a completion mechanism nothing
     * implements.
     *
     * <p>Both are unconditional, and deliberately so: a job that happens to be disabled in this
     * deployment is not a reason to accept a schedule that would run at the wrong hour in the next
     * one.
     *
     * @throws IllegalStateException if the zone is not the court's and nobody has said so, if an
     *                               acknowledged override names a zone the JVM does not know, or if
     *                               the completion mechanism is not one this service implements
     */
    public void validate() {
        validateTheScheduleIsReadInTheCourtsZone();
        validateTheCompletionMechanismIsOneThisServiceImplements();
    }

    /**
     * The schedule is a wall-clock requirement, so the zone it is read in is part of it.
     *
     * <p>The override exists so that moving the run is a deliberate, reviewable act rather than a
     * typo nobody notices until the registers arrive an hour late - and an acknowledged override is
     * still held to naming a zone that exists, because {@code @Scheduled} would otherwise fail at
     * refresh with nothing pointing at the setting that caused it.
     */
    private void validateTheScheduleIsReadInTheCourtsZone() {
        if (!COURTS_ZONE.equals(zone)) {
            if (zoneOverrideAcknowledged) {
                if (!ZoneId.getAvailableZoneIds().contains(zone)) {
                    throw new IllegalStateException(
                            ZONE + " (" + zone + ") is not a zone this JVM knows, so the"
                                    + " acknowledged override names no schedule at all");
                }
            } else {
                throw new IllegalStateException(
                        ZONE + " (" + zone + ") must be " + COURTS_ZONE + ", because the run is"
                                + " 18:00 wall clock in BST and GMT alike; set "
                                + ZONE_OVERRIDE_ACKNOWLEDGED + "=true to run in another zone"
                                + " deliberately");
            }
        }
    }

    /**
     * A value neither branch recognises is the worst of the three outcomes.
     *
     * <p>{@code poll-only} asks for no broker, so an unrecognised value that fell through to it
     * would take the broker's own startup rule with it - and then every batch would wait for an
     * event on a subscription this deployment was never told to make.
     */
    private void validateTheCompletionMechanismIsOneThisServiceImplements() {
        if (!COMPLETION_EVENT.equals(completion) && !COMPLETION_POLL_ONLY.equals(completion)) {
            throw new IllegalStateException(
                    COMPLETION + " (" + completion + ") must be " + COMPLETION_EVENT + " or "
                            + COMPLETION_POLL_ONLY + " - there is no third way for a batch to learn"
                            + " what became of its render");
        }
    }
}
