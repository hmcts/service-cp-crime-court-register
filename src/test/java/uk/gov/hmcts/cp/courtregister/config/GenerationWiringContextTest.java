package uk.gov.hmcts.cp.courtregister.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import uk.gov.hmcts.cp.courtregister.adapter.fileservice.FileServicePayloadStore;
import uk.gov.hmcts.cp.courtregister.adapter.publicevents.DocumentEventListener;
import uk.gov.hmcts.cp.courtregister.adapter.stub.StubDocumentRenderer;
import uk.gov.hmcts.cp.courtregister.adapter.stub.StubPayloadFileStore;
import uk.gov.hmcts.cp.courtregister.adapter.systemdocgenerator.SystemDocGeneratorClient;
import uk.gov.hmcts.cp.courtregister.application.DocumentOutcomeSink;
import uk.gov.hmcts.cp.courtregister.application.DocumentRenderer;
import uk.gov.hmcts.cp.courtregister.application.PayloadFileStore;
import uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService;
import uk.gov.hmcts.cp.courtregister.batch.BatchAssembler;
import uk.gov.hmcts.cp.courtregister.batch.GenerationReconciler;
import uk.gov.hmcts.cp.courtregister.batch.RegisterGenerationJob;
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
}
