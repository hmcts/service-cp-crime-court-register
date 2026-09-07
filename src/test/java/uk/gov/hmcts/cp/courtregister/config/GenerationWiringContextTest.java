package uk.gov.hmcts.cp.courtregister.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import uk.gov.hmcts.cp.courtregister.adapter.fileservice.FileServicePayloadStore;
import uk.gov.hmcts.cp.courtregister.adapter.publicevents.DocumentEventListener;
import uk.gov.hmcts.cp.courtregister.adapter.stub.StubDocumentRenderer;
import uk.gov.hmcts.cp.courtregister.adapter.stub.StubPayloadFileStore;
import uk.gov.hmcts.cp.courtregister.adapter.systemdocgenerator.SystemDocGeneratorClient;
import uk.gov.hmcts.cp.courtregister.application.DocumentOutcomeSink;
import uk.gov.hmcts.cp.courtregister.application.DocumentRenderer;
import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.application.PayloadFileStore;
import uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService;
import uk.gov.hmcts.cp.courtregister.batch.BatchAssembler;
import uk.gov.hmcts.cp.courtregister.batch.GenerationReconciler;
import uk.gov.hmcts.cp.courtregister.batch.RegisterGenerationJob;
import uk.gov.hmcts.cp.courtregister.domain.FlagDecision;
import uk.gov.hmcts.cp.courtregister.pipeline.PdfPayloadMapper;
import uk.gov.hmcts.cp.courtregister.support.WorkloadIdentityStub;

/**
 * The downstream half in a context that is actually a deployment's.
 *
 * <p>Every class Phase 5 landed is reachable from a unit test and none of them was reachable from
 * Spring. There was no {@code @Component} on and no {@code @Bean} constructing
 * {@code DocumentOutcomeSinkImpl}, {@link GenerationReconciler}, {@link RegisterGenerationService},
 * {@link BatchAssembler}, {@link PdfPayloadMapper}, {@link FileServicePayloadStore} or
 * {@link SystemDocGeneratorClient}; the only {@code PayloadFileStore} and {@code DocumentRenderer}
 * beans in the whole context were {@link StubGenerationConfig}'s stand-ins. So
 * {@code PublicEventsConfig.documentEventListener} found no sink and returned {@code null} - no
 * {@code @JmsListener}, no durable subscription - and {@code SchedulingConfig.registerGenerationJob}
 * found no gate, assembler, service or reconciler and returned {@code null} - nothing scheduled. A
 * pod deployed with {@code courtregister.generation.enabled=true} was inert, and the only trace of
 * it was two WARN lines.
 *
 * <p>Nothing below is a unit assertion restated. Each case is a bean this context either holds or
 * does not, which is the one property the whole of Phase 5's own suites cannot see and the one that
 * decides whether a register is ever generated.
 *
 * <p>The last case is constitution Principle V read as a context question: with generation enabled,
 * the stubs must not be what a deployment resolves. The four modes are LIVE here for that reason,
 * and {@link PropertiesValidator} refuses STUB outright wherever generation is enabled - so the
 * assertion is that what the context resolved is the real adapter and not merely that something
 * resolved.
 *
 * <p>The identity variables the workload-identity credential is built from are supplied by
 * {@link WorkloadIdentityStub}, because this context is a deployed pod's and a deployed pod is given
 * all three by the AKS webhook. Nothing here reads a flag, opens a connection to App Configuration,
 * or touches either database: both pools initialise lazily and the schedule's first fire is hours
 * away.
 */
@ExtendWith(WorkloadIdentityStub.class)
@SpringBootTest(properties = {
    "courtregister.generation.enabled=true",
    "courtregister.generation.completion=event",
    "courtregister.generation.sdg-mode=LIVE",
    "courtregister.generation.nn-mode=LIVE",
    "courtregister.generation.fileservice-mode=LIVE",
    "courtregister.generation.flag-mode=LIVE",
    "courtregister.fileservice.url=jdbc:postgresql://fileservice.internal:5432/fileservice",
    "courtregister.feature.endpoint=https://appconfig.internal",
    "courtregister.feature.label=ste86",
    "courtregister.endpoints.systemdocgenerator=http://systemdocgenerator.internal:8080",
    "courtregister.endpoints.notificationnotify=http://notificationnotify.internal:8080",
    "courtregister.endpoints.system-user-id=00000000-0000-4000-8000-000000000000",
    "courtregister.email.templates.cr_standard=5c9a0e21-3d47-4f18-9b62-0a71c4e8d530",
    "courtregister.payload.mode=STUB",
    "courtregister.referencedata.mode=STUB",
    // Intake is a different half and needs a broker this suite has no business standing up.
    "courtregister.consumer.enabled=false",
    "spring.artemis.broker-url=tcp://localhost:61616",
    "spring.artemis.embedded.enabled=true",
    "spring.artemis.embedded.queues=public.event"})
@DisplayName("the downstream half on a generating pod")
class GenerationWiringContextTest {

    private final ApplicationContext context;

    @Autowired
    GenerationWiringContextTest(final ApplicationContext context) {
        this.context = context;
    }

    @Test
    @DisplayName("holds the listener the durable subscription delivers to")
    void the_context_should_hold_a_document_event_listener() {
        assertThat(context.getBeanProvider(DocumentEventListener.class).getIfAvailable())
                .as("the bean is contributed only where an outcome has somewhere to be applied, so "
                        + "a null here is a pod holding no subscription at all: every "
                        + "document-available systemdocgenerator publishes for this service is "
                        + "delivered to nobody, and every batch waits for the reconciler")
                .isNotNull();
    }

    @Test
    @DisplayName("holds the sink an outcome is applied through")
    void the_context_should_hold_an_outcome_sink() {
        assertThat(context.getBeanProvider(DocumentOutcomeSink.class).getIfAvailable())
                .as("the listener and the reconciler drive one port, and it is what turns an "
                        + "announcement into batch state")
                .isNotNull();
    }

    @Test
    @DisplayName("holds the nightly run, with everything it asks in order")
    void the_context_should_hold_the_generation_job() {
        assertThat(context.getBeanProvider(RegisterGenerationJob.class).getIfAvailable())
                .as("a null here is a schedule nobody wired to anything: 18:00 comes and goes and "
                        + "the only trace is the WARN line the configuration wrote at startup")
                .isNotNull();
        assertThat(context.getBeanNamesForType(BatchAssembler.class))
                .as("the grouping the run hands its active registers to")
                .isNotEmpty();
        assertThat(context.getBeanNamesForType(RegisterGenerationService.class))
                .as("the requesting leg the run asks once per batch")
                .isNotEmpty();
        assertThat(context.getBeanNamesForType(GenerationReconciler.class))
                .as("the safety net, which carries a schedule of its own and therefore has to be a "
                        + "bean for that schedule to be seen at all")
                .isNotEmpty();
        assertThat(context.getBeanNamesForType(PdfPayloadMapper.class))
                .as("progression's payload generator, which the requesting leg maps every batch "
                        + "through")
                .isNotEmpty();
    }

    @Test
    @DisplayName("resolves the live renderer and the live payload store, not the stand-ins")
    void the_downstream_ports_should_resolve_to_the_live_adapters() {
        assertThat(context.getBean(DocumentRenderer.class))
                .as("systemdocgenerator, over the endpoint the deployment configured; the stub "
                        + "accepts every request and invents no document, so a pod resolving it "
                        + "would report a successful run every night and render nothing")
                .isInstanceOf(SystemDocGeneratorClient.class)
                .isNotInstanceOf(StubDocumentRenderer.class);
        assertThat(context.getBean(PayloadFileStore.class))
                .as("the framework file service, over the second datasource; the stub logs and "
                        + "returns, so systemdocgenerator would be asked to render a payload that "
                        + "was never stored")
                .isInstanceOf(FileServicePayloadStore.class)
                .isNotInstanceOf(StubPayloadFileStore.class);
    }

    @Test
    @DisplayName("resolves no stand-in at all where generation is enabled")
    void no_generation_stub_should_be_reachable_on_a_generating_pod() {
        assertThat(context.getBeanNamesForType(StubDocumentRenderer.class))
                .as("constitution Principle V: a stub reachable in a production profile is the pod "
                        + "that skips every night while its metrics say the run succeeded")
                .isEmpty();
        assertThat(context.getBeanNamesForType(StubPayloadFileStore.class))
                .as("and the same for the payload store")
                .isEmpty();
    }

    /**
     * Which identity {@link LiveFeatureFlagConfig} authorises the flag read with, and what each one
     * can therefore read.
     *
     * <p>{@code courtregister.feature.credential} chooses between the two, and the choice is
     * invisible in the bean: both modes contribute the same {@code AppConfigurationFlagReader} over
     * the same {@link FeatureFlagProperties}, and only the credential inside it differs. What
     * distinguishes them is what each can read, so that is what is asserted - one endpoint, one
     * stub, two modes, two outcomes.
     *
     * <p><strong>A bearer token is only ever sent over TLS.</strong> Azure's own
     * {@code BearerTokenAuthenticationPolicy} refuses a request whose URL is not {@code https},
     * before any socket is opened, which is exactly why {@code local-test} exists: a WireMock stand
     * -in for App Configuration speaks plain HTTP, so a pod's workload identity cannot read one at
     * all and the local loop had nothing but the STUB reader to fall back on. {@code local-test}
     * authorises with a fixed, published HMAC identity instead - the same shape
     * {@code GenerationStackConfiguration} and {@code AppConfigurationFlagReaderTest} already read
     * through - so the real reader, the real SDK client and the real fail-closed parsing are all
     * exercised against the compose stub.
     *
     * <p>The environment is a {@link MockEnvironment} rather than the process's own, so which of the
     * three projected variables a case holds is the case's own statement and not
     * {@link WorkloadIdentityStub}'s.
     */
    @Nested
    @DisplayName("the credential the flag read is authorised with")
    class FlagCredential {

        /** The pod's own client id, projected by the AKS workload-identity webhook. */
        private static final String CLIENT_ID = "AZURE_CLIENT_ID";

        /** The directory that identity lives in, projected by the same webhook. */
        private static final String TENANT_ID = "AZURE_TENANT_ID";

        /** Where the projected federated token is mounted. */
        private static final String TOKEN_FILE = "AZURE_FEDERATED_TOKEN_FILE";

        /** The key the flag is read under, as every reader of this one lever spells it. */
        private static final String FLAG_KEY = ".appconfig.featureflag/CourtRegisterService";

        /** The label the compose stub answers under. */
        private static final String FLAG_LABEL = "LOCAL";

        /** Any {@code kv} read, whatever key and label the reader asks under. */
        private static final String ANY_KEY_PATH = "/kv/.*";

        /** The media type App Configuration answers a key-value read with. */
        private static final String KV_MEDIA_TYPE =
                "application/vnd.microsoft.appconfig.kv+json";

        /** App Configuration's feature-flag JSON, as the vendored value schema declares it. */
        private static final String FLAG_ON = "{\\\"id\\\":\\\"CourtRegisterService\\\","
                + "\\\"enabled\\\":true,\\\"conditions\\\":{\\\"client_filters\\\":[]}}";

        /** The store's answer for the flag, switched on. */
        private static final String SETTING_ON = "{\"key\":\"" + FLAG_KEY + "\",\"label\":\""
                + FLAG_LABEL + "\",\"content_type\":"
                + "\"application/vnd.microsoft.appconfig.ff+json\",\"value\":\"" + FLAG_ON
                + "\",\"tags\":{},\"locked\":false,\"etag\":\"cr-local\"}";

        private WireMockServer store;

        @BeforeEach
        void storeAnsweringTheFlagSwitchedOn() {
            store = new WireMockServer(wireMockConfig().dynamicPort());
            store.start();
            store.stubFor(get(urlPathMatching(ANY_KEY_PATH)).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", KV_MEDIA_TYPE)
                    .withBody(SETTING_ON)));
        }

        @AfterEach
        void stopTheStore() {
            store.stop();
        }

        @Test
        @DisplayName("local-test reads a plain-HTTP App Configuration stub, and asks for no pod")
        void the_local_test_credential_should_read_the_compose_stub() {
            final FeatureFlagReader reader = new LiveFeatureFlagConfig().featureFlagReader(
                    flagAt(store.baseUrl(), FeatureFlagProperties.Credential.LOCAL_TEST),
                    new MockEnvironment());

            assertThat(reader.read())
                    .as("the whole point of the mode: the real reader, over the real SDK client, "
                            + "against the stub the compose loop and the container smoke run "
                            + "against - and on a laptop, which holds none of the three variables "
                            + "the webhook projects")
                    .isEqualTo(FlagDecision.ON);
            assertThat(store.findAll(getRequestedFor(urlPathMatching(ANY_KEY_PATH))))
                    .as("and the store was actually asked, so the reading is a read and not a "
                            + "default")
                    .isNotEmpty();
        }

        /**
         * <strong>[A]</strong> A characterisation of behaviour that already exists: the deployed
         * credential is unchanged by the new setting, and Azure's refusal to send a bearer token
         * over plain HTTP is the whole reason a second mode was needed. Green on introduction.
         */
        @Test
        @DisplayName("workload-identity cannot read one at all, which is why the mode exists")
        void the_workload_identity_credential_should_not_reach_a_plain_http_store() {
            final FeatureFlagReader reader = new LiveFeatureFlagConfig().featureFlagReader(
                    flagAt(store.baseUrl(), FeatureFlagProperties.Credential.WORKLOAD_IDENTITY),
                    podHoldingEveryProjectedVariable());

            assertThat(reader.read())
                    .as("a bearer credential is refused on a URL that is not https, so the same "
                            + "endpoint the mode above reads is unreadable on this one - and the "
                            + "run would skip, fail-closed, exactly as it does on a store outage")
                    .isInstanceOf(FlagDecision.Unreadable.class);
            assertThat(store.findAll(getRequestedFor(urlPathMatching(ANY_KEY_PATH))))
                    .as("refused before the socket, not by the store: nothing was asked")
                    .isEmpty();
        }

        /**
         * <strong>[A]</strong> A characterisation of behaviour that already exists, pinned here
         * because a new mode beside it is exactly how a refusal gets softened by accident: the
         * deployed credential still refuses a pod holding two of the three. Green on introduction.
         */
        @Test
        @DisplayName("workload-identity still refuses to start on a pod missing a projected "
                + "variable")
        void the_workload_identity_credential_should_still_refuse_an_incomplete_pod() {
            final MockEnvironment incomplete = podHoldingEveryProjectedVariable();
            incomplete.setProperty(TOKEN_FILE, "");

            assertThatThrownBy(() -> new LiveFeatureFlagConfig().featureFlagReader(
                    flagAt(store.baseUrl(), FeatureFlagProperties.Credential.WORKLOAD_IDENTITY),
                    incomplete))
                    .as("unchanged by the new mode: a deployed pod that had lost one of the three "
                            + "would skip every night on an unreadable flag and look like a store "
                            + "outage, so it does not start")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(TOKEN_FILE);
        }

        /** The flag settings, pointed at this case's store and asked for under one credential. */
        private FeatureFlagProperties flagAt(
                final String endpoint, final FeatureFlagProperties.Credential credential) {
            return new FeatureFlagProperties(
                    endpoint, FLAG_KEY, FLAG_LABEL, Duration.ofSeconds(5), credential);
        }

        /** An environment holding exactly what the AKS webhook projects, and nothing else. */
        private MockEnvironment podHoldingEveryProjectedVariable() {
            final MockEnvironment pod = new MockEnvironment();
            pod.setProperty(CLIENT_ID, "8f2c1d47-0b93-4e5a-9c31-6d0a7b4e2f18");
            pod.setProperty(TENANT_ID, "531ff96d-0ae9-462a-8d2d-bec7c0b42082");
            pod.setProperty(TOKEN_FILE, "/var/run/secrets/azure/tokens/azure-identity-token");
            return pod;
        }
    }
}
