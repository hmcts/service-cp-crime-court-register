package uk.gov.hmcts.cp.courtregister.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import uk.gov.hmcts.cp.courtregister.adapter.stub.StubDocumentRenderer;
import uk.gov.hmcts.cp.courtregister.adapter.stub.StubFeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.adapter.stub.StubPayloadFileStore;
import uk.gov.hmcts.cp.courtregister.adapter.stub.StubRegisterNotifier;
import uk.gov.hmcts.cp.courtregister.application.DocumentRenderer;
import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.application.PayloadFileStore;
import uk.gov.hmcts.cp.courtregister.application.RegisterNotifier;
import uk.gov.hmcts.cp.courtregister.domain.FlagDecision;

/**
 * The downstream half's stand-ins, kept for local runs and for the suites that address no downstream.
 *
 * <p>One configuration for four ports rather than four configurations of one, which is the opposite
 * of the choice {@link StubPayloadConfig} and {@link StubSubscriptionsConfig} make - and the reason
 * is that these four are not chosen together. Each downstream has its own mode key, so a suite can
 * stub the file service and keep systemdocgenerator live, and each bean below carries its own
 * condition; grouping them costs nothing because the alternative is four files whose only difference
 * is one property name. What they do share is a reason for existing: the container suites whose
 * subject is the batch state machine have no interest in a second Postgres, a broker or an App
 * Configuration store, and the compose stack answers with WireMock and a real file-service database
 * when the subject <em>is</em> the downstream.
 *
 * <p>Never the default, and not selectable where the service is deployed. A bean is contributed only
 * where the mode says {@code STUB}, so an environment that says nothing gets the real adapter, and
 * {@link PropertiesValidator} refuses {@code STUB} outright wherever the deployed credential source
 * is in use - a stub reachable in production is exactly the pod that skips every night, or tells a
 * Youth Offending Team nothing, while its metrics say the run succeeded (constitution Principle V).
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class StubGenerationConfig {

    /**
     * The prefix all four mode keys live under, named once because it is one prefix and not four.
     */
    private static final String GENERATION = "courtregister.generation";

    /** The value each mode key must hold for its stub to be contributed. */
    private static final String STUB = "STUB";

    /**
     * The unreadable answer, which is {@code NOT_CONFIGURED} and not one of the other five causes.
     *
     * <p>That cause is the only one of them true of a pod with no App Configuration endpoint: a stub
     * claiming a timeout or a refused identity would put into the skipped counter a cause no store
     * ever gave.
     */
    private static final FlagDecision UNREADABLE_ANSWER =
            new FlagDecision.Unreadable(FlagDecision.UnreadableReason.NOT_CONFIGURED);

    /**
     * The payload-store port, served by the logging no-op.
     *
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = GENERATION, name = "fileservice-mode", havingValue = STUB)
    public PayloadFileStore payloadFileStore() {
        return new StubPayloadFileStore();
    }

    /**
     * The renderer port, served by the stub that accepts a request and invents no document.
     *
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = GENERATION, name = "sdg-mode", havingValue = STUB)
    public DocumentRenderer documentRenderer() {
        return new StubDocumentRenderer();
    }

    /**
     * The notifier port, served by the refusing stub.
     *
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = GENERATION, name = "nn-mode", havingValue = STUB)
    public RegisterNotifier registerNotifier() {
        return new StubRegisterNotifier();
    }

    /**
     * The flag port, served by the reader that answers the configured decision and asks nobody.
     *
     * @param properties the typed settings, for the stub's configured answer
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = GENERATION, name = "flag-mode", havingValue = STUB)
    public FeatureFlagReader featureFlagReader(final CourtRegisterProperties properties) {
        return new StubFeatureFlagReader(decisionFor(properties.stub().flagAnswer()));
    }

    /**
     * Maps the configured answer onto the decision the port speaks in.
     *
     * @param answer the configured answer
     * @return the decision the reader returns on every read
     */
    private static FlagDecision decisionFor(final StubFlagAnswer answer) {
        return switch (answer) {
            case ON -> FlagDecision.ON;
            case OFF -> FlagDecision.OFF;
            case UNREADABLE -> UNREADABLE_ANSWER;
        };
    }
}
