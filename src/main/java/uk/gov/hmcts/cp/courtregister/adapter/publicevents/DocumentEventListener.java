package uk.gov.hmcts.cp.courtregister.adapter.publicevents;

import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.courtregister.application.DocumentOutcomeSink;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;

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
 * <p><strong>Seam only.</strong> The routing lands with T046. Until then the collaborators are held
 * and every delivery is acknowledged with nothing routed and nothing counted, which is the red run
 * {@code DocumentEventListenerTest} records: the sink is not called. A seam that threw instead would
 * be a nack, and a nack on a durable subscription is a message the broker offers again for ever.
 *
 * <p><strong>It is no longer a {@code @Component}.</strong> It was one while it had no
 * collaborators, when annotating it changed no context; a bean with a
 * {@link DocumentOutcomeSink} constructor argument would refuse every context to start until T047
 * makes the sink a bean. It becomes one again with T046, beside the subscription that gives it
 * something to listen to.
 */
public class DocumentEventListener {

    /**
     * The JMS string property carrying the event's name, which the broker's selector filters on.
     *
     * <p>The broker applies it as a selector so a subscriber to a topic the whole estate publishes
     * to is handed two event names rather than all of them; the envelope's own {@code _metadata.name}
     * is what the message says it is, and the two are read separately for that reason.
     */
    public static final String EVENT_NAME_PROPERTY = "CPPNAME";

    /** systemdocgenerator's announcement that a document was rendered. */
    public static final String DOCUMENT_AVAILABLE =
            "public.systemdocgenerator.events.document-available";

    /** systemdocgenerator's announcement that a generation was refused. */
    public static final String GENERATION_FAILED =
            "public.systemdocgenerator.events.generation-failed";

    /**
     * The {@code originatingSource} this service asks for its documents under, and therefore the
     * only one whose outcomes are this service's to act on.
     *
     * <p>The same name the render request carries as {@code originatingSource}; progression's leg is
     * still deployed and still subscribed, and it keeps its own.
     */
    public static final String ORIGINATING_SOURCE = "CourtRegisterService";

    private static final Logger LOG = LoggerFactory.getLogger(DocumentEventListener.class);

    private final DocumentOutcomeSink sink;

    private final GenerationMetrics metrics;

    /**
     * Holds the outcome port and the instruments the routing will use.
     *
     * @param sink    where a recognised outcome is applied, naming EVENT
     * @param metrics where an event this service did not ask for is counted
     */
    public DocumentEventListener(final DocumentOutcomeSink sink, final GenerationMetrics metrics) {
        this.sink = sink;
        this.metrics = metrics;
    }

    /**
     * Handles one message from the public-event topic.
     *
     * <p><strong>Seam only</strong>, as the class note says: it acknowledges and routes nothing. The
     * trace names the two collaborators it is holding, both because a delivery arriving before the
     * listener is wired should be visible somewhere and because a collaborator no method reads is a
     * PMD {@code UnusedPrivateField} violation.
     *
     * @param message the framework {@code JsonEnvelope} as text, with {@code CPPNAME} as a string
     *                property
     */
    public void onPublicEvent(final TextMessage message) {
        LOG.debug("A public event arrived before T046 wired the listener: nothing routed to {}, "
                + "nothing counted on {}.", sink, metrics);
    }
}
