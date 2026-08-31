package uk.gov.hmcts.cp.courtregister.inbound;

import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import uk.gov.hmcts.cp.courtregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.config.ServiceBusHealthIndicator;
import uk.gov.hmcts.cp.courtregister.domain.GuardDecision;

/**
 * One delivery in, exactly one settlement out.
 *
 * <p><strong>A compile-safe seam, not the adapter.</strong> T022 implements the settlement contract
 * this class exists to hold; what is fixed here is the shape the suites drive it through — the
 * collaborators it settles with, and a single entry point per delivery.
 *
 * <p>The contract it will hold. Every path produces a {@link GuardDecision}, and settlement happens
 * once, afterwards, in one place: there is no route through this class that reaches the end without
 * a settlement attempt and none that settles twice, which is what stops a delivery being left to
 * time out (spec FR-001, constitution Principle VI). Ordering is the other half — a delivery is
 * acknowledged only after the outcome write has returned durably, because a message acknowledged
 * before the write is a request the processed log has never heard of and the broker will never
 * deliver again.
 *
 * <p>One broker fact is read here and nowhere else: whether the queue will deliver this message
 * again. The processed log cannot answer it — the delivery budget belongs to the message, not to the
 * request — so the transport adapter reads it from the delivery and carries it into the core.
 */
public class CourtRegisterMessageListener {

    private final DistributionCommandParser parser;
    private final DistributionPipeline pipeline;
    private final ProcessingMetrics metrics;
    private final ServiceBusHealthIndicator health;
    private final StoreGate storeGate;
    private final int maxDeliveryCount;

    /**
     * Creates the listener; the settlement decision stays here and nowhere else.
     *
     * @param parser           reads the body into the validated command
     * @param pipeline         the use case every valid request is run through
     * @param metrics          the instrument surface settlements are counted on
     * @param health           where a refused or accepted settlement is reported as transport news
     * @param storeGate        the processed-log precondition every delivery passes through
     * @param maxDeliveryCount the queue's own delivery budget, mirrored in configuration
     */
    public CourtRegisterMessageListener(
            final DistributionCommandParser parser,
            final DistributionPipeline pipeline,
            final ProcessingMetrics metrics,
            final ServiceBusHealthIndicator health,
            final StoreGate storeGate,
            final int maxDeliveryCount) {
        this.parser = parser;
        this.pipeline = pipeline;
        this.metrics = metrics;
        this.health = health;
        this.storeGate = storeGate;
        this.maxDeliveryCount = maxDeliveryCount;
    }

    /**
     * Handles one delivery, from receipt to its single settlement.
     *
     * @param context the delivery, and the settlement calls it permits
     */
    public void onMessage(final ServiceBusReceivedMessageContext context) {
        throw new UnsupportedOperationException("every delivery is settled exactly once");
    }
}
