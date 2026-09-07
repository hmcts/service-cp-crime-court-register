package uk.gov.hmcts.cp.courtregister.config;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.policy.FixedDelayOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.identity.WorkloadIdentityCredentialBuilder;
import java.time.Duration;
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
 * <p><strong>Which identity the read is authorised with is {@code courtregister.feature.credential}
 * and nothing else.</strong> Both values contribute the same reader over the same properties, so
 * the endpoint, the key, the label, the budget and the fail-closed parsing are the deployed ones
 * either way; only the credential inside differs. {@code workload-identity} is the deployed value
 * and the default. {@code local-test} exists because a bearer credential cannot read a stand-in at
 * all - Azure refuses to send a token over anything but TLS - and {@link PropertiesValidator}
 * refuses it wherever the endpoint names a real store or the pod is a deployed one.
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
     * The identity {@code local-test} reads under: invented here and a secret of nothing.
     *
     * <p>The same fixed, published pair {@code AppConfigurationFlagReaderTest} and
     * {@code GenerationStackConfiguration} already read the stub through. It authorises nothing: no
     * Azure store has ever been given it, and the only thing that answers it is a WireMock mapping
     * that checks no credential at all.
     */
    private static final String LOCAL_TEST_ID = "0-l0-s0:courtregisterlocal";

    /** The HMAC secret that identity signs with; Base64 of {@code not-a-secret}. */
    private static final String LOCAL_TEST_SECRET = "bm90LWEtc2VjcmV0";

    /** The read is the whole budget, so the SDK is asked once and never asked again. */
    private static final RetryOptions NO_RETRIES =
            new RetryOptions(new FixedDelayOptions(0, Duration.ZERO));

    /**
     * The flag port, served by the App Configuration reader.
     *
     * <p>One reader, two identities, and the setting decides which:
     * {@link FeatureFlagProperties.Credential#WORKLOAD_IDENTITY} is the pod's own and is what every
     * deployment gets, {@link FeatureFlagProperties.Credential#LOCAL_TEST} is the compose loop's
     * and is refused at startup anywhere the reading could matter.
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
        return switch (properties.credential()) {
            case WORKLOAD_IDENTITY ->
                new AppConfigurationFlagReader(properties, workloadIdentity(environment));
            case LOCAL_TEST -> new AppConfigurationFlagReader(properties, localTestClient(properties));
        };
    }

    /**
     * The client the local loop reads through: this stack's stub, on an identity nothing authorises.
     *
     * <p>A connection string rather than a credential, and that is the whole of the difference. The
     * SDK sends a {@code TokenCredential}'s token only over TLS - {@code
     * BearerTokenAuthenticationPolicy} refuses any other URL before a socket is opened - so a
     * WireMock stand-in for App Configuration, which speaks plain HTTP, cannot be read on a pod's
     * workload identity at all. Signing the read with a fixed HMAC pair instead is what leaves the
     * <em>reader</em> real: the key in the path, the label in the query, the media type, the
     * fail-closed reading of a store that answers 500 and the budget the whole read is bounded at
     * are all the deployed ones, which is exactly the trade
     * {@code GenerationStackConfiguration} already makes for the end-to-end suites.
     *
     * <p>Retries off, for the reason the reader's own client has them off: the budget is the whole
     * of the read, and three SDK attempts inside it would spend the run's decision on the first
     * attempt's back-off. The outer deadline is the reader's, so it holds here too.
     *
     * <p>No endpoint is no client, which the reader reads as {@code NOT_CONFIGURED} - a skipped run
     * with a cause on it. {@link PropertiesValidator} has already refused the case that matters,
     * generation enabled with no endpoint, but bean order is not a thing to rely on for it.
     *
     * @param properties where the flag is read from
     * @return the client, or {@code null} where no store is configured
     */
    private static ConfigurationClient localTestClient(final FeatureFlagProperties properties) {
        ConfigurationClient client = null;
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            client = new ConfigurationClientBuilder()
                    .connectionString("Endpoint=" + properties.endpoint()
                            + ";Id=" + LOCAL_TEST_ID + ";Secret=" + LOCAL_TEST_SECRET)
                    .retryOptions(NO_RETRIES)
                    .buildClient();
        }
        return client;
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
     * missing, and names both local alternatives - the real reader on the {@code local-test}
     * credential, and the stub - because the alternative is the thing a reader of the failure
     * actually needs.
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
                            + " webhook; a local run reads the compose stub through the same"
                            + " reader with courtregister.feature.credential=local-test, or"
                            + " answers itself with courtregister.generation.flag-mode=STUB");
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
