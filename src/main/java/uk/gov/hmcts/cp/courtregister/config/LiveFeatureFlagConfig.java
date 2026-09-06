package uk.gov.hmcts.cp.courtregister.config;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.WorkloadIdentityCredentialBuilder;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
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

    /** The pod's own client id, projected by the AKS workload-identity webhook. */
    private static final String CLIENT_ID = "AZURE_CLIENT_ID";

    /** The directory the pod's identity lives in, projected by the same webhook. */
    private static final String TENANT_ID = "AZURE_TENANT_ID";

    /** Where the projected federated token is mounted. */
    private static final String TOKEN_FILE = "AZURE_FEDERATED_TOKEN_FILE";

    /**
     * The flag port, served by the App Configuration reader.
     *
     * @param properties  where the flag is read from, and under which key, label and budget
     * @param environment the deployment's own environment, which carries the pod's identity
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = "courtregister.generation", name = "flag-mode",
            havingValue = "LIVE", matchIfMissing = true)
    public FeatureFlagReader featureFlagReader(
            final FeatureFlagProperties properties, final Environment environment) {
        return new AppConfigurationFlagReader(properties, workloadIdentity(environment));
    }

    /**
     * The identity the store authorises the read against, built the way the producer builds its own.
     *
     * <p>Workload identity and never a secret: the client id, the tenant and the path to the
     * projected federated token, exactly the three facts {@code InformantRegisterQueuePublisher}
     * builds its Service Bus credential from in the service that produces this service's inbound
     * commands. Reading one flag the same way the producer reaches one queue is what keeps the
     * platform ask (an {@code App Configuration Data Reader} role assignment for the
     * {@code courtregister} identity, design §8) a single, checkable statement.
     *
     * <p><strong>A missing variable is a refusal to start, not a skipped night.</strong> The three
     * are projected into a deployed pod by the AKS webhook and are absent from a laptop, so a local
     * run that asks for a LIVE reader is a run that would have failed at 18:00 with an unreadable
     * flag every night, looking exactly like a store outage. The message names each variable that is
     * missing, and names the stub as the local alternative, because the alternative is the thing a
     * reader of the failure actually needs.
     */
    private static TokenCredential workloadIdentity(final Environment environment) {
        final String clientId = environment.getProperty(CLIENT_ID);
        final String tenantId = environment.getProperty(TENANT_ID);
        final String tokenFile = environment.getProperty(TOKEN_FILE);
        final List<String> missing = new ArrayList<>();
        addIfBlank(missing, CLIENT_ID, clientId);
        addIfBlank(missing, TENANT_ID, tenantId);
        addIfBlank(missing, TOKEN_FILE, tokenFile);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    String.join(", ", missing) + " must be set when"
                            + " courtregister.generation.enabled is true and"
                            + " courtregister.generation.flag-mode is LIVE - the App Configuration"
                            + " read is made on this pod's workload identity, and without it every"
                            + " nightly run would skip on an unreadable flag and look like a store"
                            + " outage. A deployed pod is given all three by the workload-identity"
                            + " webhook; a local run sets"
                            + " courtregister.generation.flag-mode=STUB instead");
        }
        return new WorkloadIdentityCredentialBuilder()
                .clientId(clientId)
                .tenantId(tenantId)
                .tokenFilePath(tokenFile)
                .build();
    }

    private static void addIfBlank(
            final List<String> missing, final String name, final String value) {
        if (value == null || value.isBlank()) {
            missing.add(name);
        }
    }
}
