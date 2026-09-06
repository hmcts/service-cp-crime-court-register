package uk.gov.hmcts.cp.courtregister.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import uk.gov.hmcts.cp.courtregister.adapter.appconfig.AppConfigurationFlagReader;
import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;

/**
 * The real reader of the one lever.
 *
 * <p>The counterpart of {@link StubGenerationConfig}'s flag bean, and chosen by the same key: this
 * one is contributed where {@code courtregister.generation.flag-mode} is LIVE, which is the default
 * and is everywhere the service is deployed, and the stub is contributed where it says STUB.
 * {@link PropertiesValidator} refuses STUB outright wherever the deployed credential source is in
 * use, so the pair cannot be resolved the wrong way round in an environment that matters.
 *
 * <p>It is also conditional on generation being enabled, because a deployment that runs no nightly
 * job has nothing to ask the flag: the reader would hold an endpoint and a credential for a question
 * nobody asks, and {@link PropertiesValidator} only requires the endpoint and the label once
 * generation is on. The two conditions are the same sentence as the properties' own rule, stated
 * where the bean is.
 *
 * <p>Excluded from the {@code test} profile alongside the rest of the live wiring.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "courtregister.generation", name = "enabled", havingValue = "true")
public class LiveFeatureFlagConfig {

    /**
     * The flag port, served by the App Configuration reader.
     *
     * @param properties where the flag is read from, and under which key, label and budget
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = "courtregister.generation", name = "flag-mode",
            havingValue = "LIVE", matchIfMissing = true)
    public FeatureFlagReader featureFlagReader(final FeatureFlagProperties properties) {
        return new AppConfigurationFlagReader(properties);
    }
}
