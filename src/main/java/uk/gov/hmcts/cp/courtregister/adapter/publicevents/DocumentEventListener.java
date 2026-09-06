package uk.gov.hmcts.cp.courtregister.adapter.publicevents;

import jakarta.jms.TextMessage;
import org.springframework.stereotype.Component;

/**
 * How a rendering outcome reaches this service: the durable subscription to {@code public.event}.
 *
 * <p>systemdocgenerator announces every document it renders and every generation it refuses on the
 * estate's public topic, so the two events this service cares about arrive among everybody else's.
 * Three filters narrow them, and each is there for a different reason:
 *
 * <ul>
 *   <li>the broker's own {@code CPPNAME} selector, so the subscription is handed the two event names
 *       rather than the whole topic;</li>
 *   <li>{@code originatingSource == CourtRegisterService}, because progression's leg is still
 *       deployed and still subscribed, and its documents are not this service's to act on. Such a
 *       message is acknowledged and counted, never left unsettled: a message this service has no
 *       business with is not a message the broker should redeliver;</li>
 *   <li>{@code sourceCorrelationId}, which is the batch id and the only thing that maps an outcome
 *       back to the rows the document was built from.</li>
 * </ul>
 *
 * <p>What it does with a message it recognises is call
 * {@link uk.gov.hmcts.cp.courtregister.application.DocumentOutcomeSink}, naming EVENT. Nothing about
 * batch state is decided here: this class knows JMS and envelopes, and the sink knows what an
 * outcome means.
 *
 * <p><strong>The subscription itself arrives with T046</strong> - the {@code @JmsListener} with its
 * destination, durable subscription name and selector read from
 * {@code courtregister.publicevents.*}, and the container factory in
 * {@link uk.gov.hmcts.cp.courtregister.config.PublicEventsConfig} - because
 * {@code DocumentEventListenerIT} exists to prove that an event published while the listener is
 * stopped is delivered on restart, and a seam that already carried the annotation would be that pin
 * passing before anything wired it.
 *
 * <p><strong>Seam only.</strong> The listener lands with T046; until then this throws, so that
 * {@code DocumentEventListenerTest} records a failing assertion rather than a compile error.
 */
@Component
public class DocumentEventListener {

    /**
     * Handles one message from the public-event topic.
     *
     * @param message the framework {@code JsonEnvelope} as text, with {@code CPPNAME} as a string
     *                property
     */
    public void onPublicEvent(final TextMessage message) {
        throw new UnsupportedOperationException("T046");
    }
}
