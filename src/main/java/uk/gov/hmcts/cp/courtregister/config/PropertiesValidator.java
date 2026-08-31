package uk.gov.hmcts.cp.courtregister.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Refuses to let the application start on a configuration that cannot be operated safely.
 *
 * <p>The seam only at this point: the rules themselves land with the implementation task this
 * signature guards.
 */
@Component
@EnableConfigurationProperties(CourtRegisterProperties.class)
public class PropertiesValidator implements InitializingBean {

    private final CourtRegisterProperties properties;

    /**
     * Creates the validator over the bound properties.
     *
     * @param properties the bound settings
     */
    public PropertiesValidator(final CourtRegisterProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        validate(properties);
    }

    /**
     * Checks the settings that must hold for the service to be safe to run.
     *
     * @param properties the bound settings
     * @throws IllegalStateException if any rule is broken
     */
    public static void validate(final CourtRegisterProperties properties) {
        throw new UnsupportedOperationException("PropertiesValidator is not implemented yet");
    }
}
