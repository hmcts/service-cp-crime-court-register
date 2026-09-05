package uk.gov.hmcts.cp.courtregister.config;

import java.time.Duration;
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
 * <p><strong>Seam.</strong> {@link #validate()} carries the startup refusals of research §11 and is
 * completed by T016, guarded by {@code ConfigurationValidationTest} (T009) - including the P9 pin,
 * {@code blank_email_template_refuses_to_start_in_live_mode}.
 *
 * @param enabled                  master switch for the job, the listener and the second datasource
 * @param cron                     the schedule, in Spring's six-field dialect
 * @param zone                     the zone the cron is read in; {@code Europe/London} unless
 *                                 acknowledged otherwise
 * @param zoneOverrideAcknowledged the deliberate acknowledgement that permits another zone
 * @param runDeadline              the bound on how long one run may go on requesting renders
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
        @DefaultValue("10m") Duration gracePeriod,
        @DefaultValue("event") String completion,
        @DefaultValue(LIVE) SourceMode sdgMode,
        @DefaultValue(LIVE) SourceMode nnMode,
        @DefaultValue(LIVE) SourceMode fileserviceMode,
        @DefaultValue(LIVE) SourceMode flagMode) {

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
     * Refuses a generation configuration that cannot be operated safely.
     *
     * <p>The rules of research §11: the zone, the modes, and everything that becomes required once
     * {@link #enabled} is true. All of them fail a deploy rather than a night.
     */
    public void validate() {
        throw new UnsupportedOperationException(
                "T016 completes the generation refusals; "
                        + "ConfigurationValidationTest (T009) guards them");
    }
}
