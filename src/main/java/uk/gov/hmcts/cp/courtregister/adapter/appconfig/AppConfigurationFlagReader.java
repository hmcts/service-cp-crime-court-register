package uk.gov.hmcts.cp.courtregister.adapter.appconfig;

import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.config.FeatureFlagProperties;
import uk.gov.hmcts.cp.courtregister.domain.FlagDecision;

/**
 * The one lever, read from Azure App Configuration.
 *
 * <p>This service is the flag's third reader, after the producer and the legacy, and it reads the
 * same key and label those two read - which is what makes it one lever rather than three. The
 * setting is a feature flag, so the value is App Configuration's own feature-flag JSON and the
 * answer is its {@code enabled} member; the key and the stack's label are configuration, never
 * defaults invented here (research §3).
 *
 * <p><strong>It never throws</strong>, which is the port's contract and the reason this adapter
 * exists rather than an SDK call at the call site: a store that is absent, one that refuses this
 * pod's identity, one that is merely slow and one that answers with something this service cannot
 * read are four different {@link FlagDecision.Unreadable} causes, each bounded, and every one of
 * them means the run is skipped. Nothing about the SDK's exceptions reaches the caller, and nothing
 * of the store's text reaches a counter label or the log index.
 *
 * <p>The read is bounded by {@code courtregister.feature.timeout} and is made once per run with no
 * cache: a flag that was on an hour ago says nothing about a cutover that was rolled back ten
 * minutes ago.
 *
 * <p><strong>Seam.</strong> T029 replaces the refusal below with the SDK read - a
 * {@code ConfigurationClient} over {@code WorkloadIdentityCredential} - and the mapping from its
 * failures onto the bounded causes; its green run is {@code AppConfigurationFlagReaderTest} (T026),
 * which drives it over WireMock on the App Configuration {@code kv} endpoint.
 */
public class AppConfigurationFlagReader implements FeatureFlagReader {

    /** The task that replaces the refusal in this class with the read. */
    private static final String PENDING_TASK =
            "T029 implements AppConfigurationFlagReader; AppConfigurationFlagReaderTest (T026) "
                    + "guards it";

    // Read by T029: the endpoint, the key, the stack label and the read's budget all come from here.
    @SuppressWarnings("PMD.UnusedPrivateField")
    private final FeatureFlagProperties properties;

    /**
     * Creates the reader over the store, the key and the label the deployment named.
     *
     * @param properties where the flag is read from, and under which key, label and budget
     */
    public AppConfigurationFlagReader(final FeatureFlagProperties properties) {
        this.properties = properties;
    }

    @Override
    public FlagDecision read() {
        throw new UnsupportedOperationException(PENDING_TASK);
    }
}
