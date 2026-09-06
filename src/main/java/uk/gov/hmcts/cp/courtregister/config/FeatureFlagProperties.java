package uk.gov.hmcts.cp.courtregister.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the one lever is read from.
 *
 * <p>Bound from the {@code courtregister.feature} keys. The endpoint and the label are bindings
 * rather than values - they arrive from the deployment, and a service that invented either would
 * read a different stack's flag or none at all - so neither has a default worth having and both
 * become required once generation is enabled.
 *
 * <p>The key is the exception: it is the same setting the legacy reads, spelled the same way, and
 * that sameness is what makes the flag one lever rather than three. Changing it here without
 * changing it there leaves two implementations disagreeing about the cutover.
 *
 * <p>{@link #validate()} holds the two settings that have defaults to what those defaults have to
 * be. The endpoint and the label have none, so whether they are present is a question about
 * generation rather than about this record, and {@link PropertiesValidator} asks it.
 *
 * @param endpoint the App Configuration store, empty where none is configured
 * @param key      the setting key, in App Configuration's feature-flag form
 * @param label    the stack's label, which is how one store serves every stack
 * @param timeout  the whole budget for the read; a timeout is UNREADABLE, which is OFF
 */
@ConfigurationProperties(prefix = "courtregister.feature")
public record FeatureFlagProperties(
        String endpoint,
        @DefaultValue(".appconfig.featureflag/CourtRegisterService") String key,
        String label,
        @DefaultValue("2s") Duration timeout) {

    private static final String PREFIX = "courtregister.feature";
    private static final String KEY = PREFIX + ".key";
    private static final String TIMEOUT = PREFIX + ".timeout";

    /**
     * Refuses a flag configuration that cannot answer the question the run asks.
     *
     * <p>The key is the lever's identity - it is the same string the producer and the legacy read,
     * and an empty one reads a setting nobody writes, which is UNREADABLE, which is a run skipped
     * every night. The timeout gates the start of a run, so one that never expires is a run held
     * open by a slow store rather than a run that decided to skip.
     *
     * @throws IllegalStateException if the key is blank or the timeout cannot expire
     */
    public void validate() {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    KEY + " must be the App Configuration key the flag lives under, the same one"
                            + " the producer and the legacy read - an empty key reads a setting"
                            + " nobody writes, and every run would skip");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException(
                    TIMEOUT + " (" + timeout + ") must be positive - the flag gates the start of a"
                            + " run, and a read that never expires holds the run open instead of"
                            + " deciding it");
        }
    }
}
