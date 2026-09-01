package uk.gov.hmcts.cp.courtregister.adapter.payload;

import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.application.HearingPayloadSource;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.PayloadUnavailableException;

/**
 * The payload source as the function app arranges it: the cache first, the query side after it.
 *
 * <p>Ordering, and the fact that a cache failure is not a request failure, are the whole of this
 * class. Both come from {@code HearingResultedCacheQuery.getHearing}
 * ({@code index.js:166-187}): read the cache, and go to the query API when the cache produced
 * nothing. Deciding that a cache which cannot answer has nothing to say belongs to the cache
 * adapter, which knows what its own failures look like; this class catches nothing, so a fault that
 * is not the cache's own reaches the pipeline and is recorded there rather than being spent on a
 * fallback.
 *
 * <p>Two keys are read, not one. The legacy builds exactly one key from the hearing date it was
 * handed ({@code getCacheKey}, {@code index.js:83-88}), and its own suite pins both forms — K6 the
 * dated one, K1 and K4 the undated twin — because the producer publishes under both. Reading only
 * the dated form would send every legacy-cached hearing to the query API, which answers, so the miss
 * would cost a round trip and show up nowhere. Both reads are time the run spends before the query
 * side is asked, and {@code PropertiesValidator} budgets two of them against the processing
 * deadline; a third lookup here is a change to that budget as well as to this method.
 *
 * <p><strong>Defect fix C32 ends here.</strong> The legacy returns {@code null} when neither source
 * answered, the orchestrator's {@code if (hearingResultedObj)} guard skips every remaining step
 * ({@code CourtRegisterOrchestrator/index.js:20}), and the run reports success having produced
 * nothing. Here it raises {@link PayloadUnavailableException}, which is transient by construction:
 * the payload may simply not be written yet, so the request is redelivered, and an exhausted one is
 * dead-lettered where somebody can see it.
 */
// PMD.UnusedPrivateField: the three collaborators are the seam T057's suite constructs the adapter
// with, and they are read by the body T065 writes. The suppression comes off with that body.
@SuppressWarnings("PMD.UnusedPrivateField")
public class CachedHearingPayloadAdapter implements HearingPayloadSource {

    private final HearingPayloadCache cache;
    private final HearingPayloadQuery query;
    private final String keyPrefix;

    /**
     * Composes the two sources behind the single port.
     *
     * @param cache     the payload cache, read first
     * @param query     the query-side fallback, read when the cache produced nothing
     * @param keyPrefix the payload prefix the producer writes under, {@code INT_} for this flow
     */
    public CachedHearingPayloadAdapter(final HearingPayloadCache cache,
            final HearingPayloadQuery query, final String keyPrefix) {
        this.cache = cache;
        this.query = query;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public JsonNode fetch(final DistributionCommand command) {
        throw new UnsupportedOperationException("T065 composes the cache and the query side");
    }
}
