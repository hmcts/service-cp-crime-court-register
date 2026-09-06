package uk.gov.hmcts.cp.courtregister.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.adapter.fileservice.FileServicePayloadStore;
import uk.gov.hmcts.cp.courtregister.adapter.systemdocgenerator.SystemDocGeneratorClient;
import uk.gov.hmcts.cp.courtregister.application.DocumentRenderer;
import uk.gov.hmcts.cp.courtregister.application.PayloadFileStore;

/**
 * The real downstream of the nightly run: systemdocgenerator, and the file service it reads from.
 *
 * <p>The counterpart of {@link StubGenerationConfig}'s two beans, chosen by the same two keys:
 * these are contributed where {@code courtregister.generation.sdg-mode} and
 * {@code .fileservice-mode} are LIVE, which is the default and is everywhere the service is
 * deployed, and the stand-ins where either says STUB. {@link PropertiesValidator} refuses STUB
 * outright wherever generation is enabled or a namespace is set, so the pair cannot be resolved the
 * wrong way round in an environment that matters (constitution Principle V).
 *
 * <p>Conditional on generation being enabled as well, because a deployment that runs no nightly job
 * has nothing to render: the client would hold an endpoint and an identity for a call nobody makes,
 * and the payload store would want a datasource {@link FileServiceDataSourceConfig} does not build.
 *
 * <p>Excluded from the {@code test} profile alongside the rest of the live wiring.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "courtregister.generation", name = "enabled", havingValue = "true")
public class LiveGenerationConfig {

    /** The prefix the two mode keys live under. */
    private static final String GENERATION = "courtregister.generation";

    /** The value each mode key holds where the real adapter is wanted, and the default. */
    private static final String LIVE = "LIVE";

    /**
     * The renderer port, served by systemdocgenerator's command and query APIs.
     *
     * <p>Both timeouts are set deliberately, as they are on every other client this service builds:
     * the render request is spent inside the run's deadline, and a POST with no read timeout can
     * outlive the bound that was supposed to hold it - which for this flow is a night that generates
     * nothing behind the batch it hung on.
     *
     * <p>The retry loop is not here. The client classifies one attempt through the shared
     * {@link uk.gov.hmcts.cp.courtregister.adapter.http.RetryPolicy} and
     * {@link uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService} decides whether
     * the run's budget holds another, because the budget is the run's knowledge and not
     * systemdocgenerator's.
     *
     * @param properties   the bound settings, for the endpoint, the identity and the two timeouts
     * @param objectMapper the shared mapper, so the command body is serialised exactly as
     *                     {@code SystemDocGeneratorClientTest} pins it
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = GENERATION, name = "sdg-mode", havingValue = LIVE,
            matchIfMissing = true)
    public DocumentRenderer documentRenderer(
            final CourtRegisterProperties properties, final ObjectMapper objectMapper) {

        final CourtRegisterProperties.Endpoints endpoints = properties.endpoints();
        return new SystemDocGeneratorClient(
                RestClient.builder()
                        .baseUrl(endpoints.systemdocgenerator())
                        .requestFactory(requestFactory(endpoints))
                        .build(),
                endpoints.systemUserId(),
                objectMapper);
    }

    /**
     * The payload-store port, served by the two inserts into the framework file service.
     *
     * <p>Over the second datasource by name. It is {@code defaultCandidate = false} precisely so
     * that nothing finds it by type, so this is the one place that asks for it - and asking for it
     * here rather than anywhere else is what keeps the processed log's own client the one everything
     * else injects.
     *
     * @param jdbcClient the file-service client, by name
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = GENERATION, name = "fileservice-mode", havingValue = LIVE,
            matchIfMissing = true)
    public PayloadFileStore payloadFileStore(
            @Qualifier(FileServiceDataSourceConfig.JDBC_CLIENT) final JdbcClient jdbcClient) {
        return new FileServicePayloadStore(jdbcClient);
    }

    /**
     * A request factory with both timeouts set.
     *
     * <p>The simple factory rather than a pooled client, for the reason the other clients use it:
     * this is one POST per batch on one evening, and a pooled client would hold background threads
     * for the lifetime of every context to save nothing.
     */
    private static ClientHttpRequestFactory requestFactory(
            final CourtRegisterProperties.Endpoints endpoints) {
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(endpoints.connectTimeout());
        factory.setReadTimeout(endpoints.readTimeout());
        return factory;
    }
}
