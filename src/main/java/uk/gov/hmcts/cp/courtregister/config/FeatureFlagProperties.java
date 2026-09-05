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
 * <p><strong>Seam.</strong> {@link #validate()} is completed by T016, guarded by
 * {@code ConfigurationValidationTest} (T009).
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

    /**
     * Refuses a flag configuration that cannot answer the question the run asks.
     *
     * <p>Required whenever generation is enabled: a run that cannot read the flag skips every night,
     * which is safe and silent, and silence of that kind is what this service exists to end.
     */
    public void validate() {
        throw new UnsupportedOperationException(
                "T016 completes the feature-flag refusals; "
                        + "ConfigurationValidationTest (T009) guards them");
    }
}
