package uk.gov.hmcts.cp.courtregister.adapter.publicevents;

import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.application.DocumentOutcomeSink;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
import uk.gov.hmcts.cp.courtregister.config.PublicEventsConfig;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;

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
 * <p><strong>Nothing here ever throws at the container.</strong> A listener that threw would nack,
 * and a nack on a durable subscription is a message the broker offers again for ever - so a message
 * that cannot be read, is not an envelope, contradicts its own header or names no batch is said out
 * loud and acknowledged. The one failure worth a redelivery is the sink's, and the sink is the thing
 * that decides that: an exception it raises travels back through here untouched.
 *
 * <p><strong>The subscription is configured, not hard-coded.</strong> The destination, the durable
 * subscription's name and the selector all come from {@code courtregister.publicevents.*}, and the
 * container they run in is {@link PublicEventsConfig}'s - pub-sub, durable, and started only where
 * this deployment generates and learns its outcomes from events.
 *
 * <p><strong>It is a bean of {@link PublicEventsConfig}'s making rather than a component.</strong>
 * The sink it writes through lives with the register store, which a deployment without a database
 * does not have; declaring the listener where the container factory is declared is what lets one
 * condition - "this pod generates" - govern the subscription and everything it needs.
 */
// PMD.OnlyOneReturn: every filter below is a reason to acknowledge and stop, and each says so where
// it is decided. Funnelling them through one exit would turn four distinct refusals into a flag.
@SuppressWarnings("PMD.OnlyOneReturn")
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

    /** The event's own account of who asked for the document. */
    private static final String ORIGINATING_SOURCE_FIELD = "originatingSource";

    /** The batch the render was requested for, which is what an outcome is applied to. */
    private static final String SOURCE_CORRELATION_ID = "sourceCorrelationId";

    /** The payload the document was rendered from, and the correlation's cross-check. */
    private static final String PAYLOAD_FILE_SERVICE_ID = "payloadFileServiceId";

    /** The rendered document, which is what the e-mail will attach. */
    private static final String DOCUMENT_FILE_SERVICE_ID = "documentFileServiceId";

    /** When systemdocgenerator says it finished. */
    private static final String GENERATED_TIME = "generatedTime";

    /** When systemdocgenerator says it gave up. */
    private static final String FAILED_TIME = "failedTime";

    /** systemdocgenerator's own words for a refusal, kept by the sink and never logged. */
    private static final String REASON = "reason";

    private final DocumentOutcomeSink sink;

    private final GenerationMetrics metrics;

    /**
     * Holds the outcome port and the instruments the routing uses.
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
     * <p>The three configured values are the subscription's identity as much as its address: the
     * destination is the estate's one multicast topic, the subscription name is half of what the
     * broker recognises this consumer by across a restart (the client id is the other half, and it
     * is set where the connection is), and the selector is the broker-side filter that keeps a
     * subscriber to everybody's topic from being handed everybody's traffic.
     *
     * @param message the framework {@code JsonEnvelope} as text, with {@code CPPNAME} as a string
     *                property
     */
    @JmsListener(
            destination = "${courtregister.publicevents.topic}",
            subscription = "${courtregister.publicevents.subscription}",
            selector = "${courtregister.publicevents.selector}",
            containerFactory = PublicEventsConfig.LISTENER_CONTAINER_FACTORY)
    public void onPublicEvent(final TextMessage message) {
        final String eventName;
        final String body;
        try {
            eventName = message.getStringProperty(EVENT_NAME_PROPERTY);
            body = message.getText();
        } catch (final JMSException unreadable) {
            LOG.warn("A public event could not be read off the subscription, so it is acknowledged "
                    + "and dropped: a message the broker cannot hand over will not read any better "
                    + "on the redelivery.", unreadable);
            return;
        }
        if (!DOCUMENT_AVAILABLE.equals(eventName) && !GENERATION_FAILED.equals(eventName)) {
            LOG.debug("A public event the selector does not name reached the subscription: {}.",
                    eventName);
            return;
        }
        final PublicEventEnvelope envelope;
        try {
            envelope = PublicEventEnvelope.parse(body);
        } catch (final JacksonException | IllegalArgumentException notAnEnvelope) {
            LOG.warn("A {} was not a readable JsonEnvelope, so it is acknowledged and dropped.",
                    eventName, notAnEnvelope);
            return;
        }
        if (!eventName.equals(envelope.metadataName())) {
            LOG.warn("A public event's CPPNAME and its envelope disagree, so it is acknowledged and "
                    + "dropped: the header said {} and the envelope says {}.", eventName,
                    envelope.metadataName());
            return;
        }
        route(eventName, envelope.payload());
    }

    /**
     * Applies one of systemdocgenerator's two announcements, if it is this service's to apply.
     *
     * @param eventName the event both the header and the envelope name
     * @param payload   the event's own fields
     */
    private void route(final String eventName, final JsonNode payload) {
        if (!ORIGINATING_SOURCE.equals(text(payload, ORIGINATING_SOURCE_FIELD))) {
            // Somebody else's document, or one from a request that carried no source at all - which
            // this service always sends, so an outcome without one answers a request that was not
            // ours. Counted, because a subscription hearing nothing of its own has to be legible
            // from a subscription hearing nothing at all.
            metrics.foreignEventIgnored();
            LOG.debug("A {} for another service's document was acknowledged and dropped.",
                    eventName);
            return;
        }
        final UUID correlationId = uuid(payload, SOURCE_CORRELATION_ID);
        final UUID payloadFileId = uuid(payload, PAYLOAD_FILE_SERVICE_ID);
        if (correlationId == null || payloadFileId == null) {
            LOG.warn("A {} named no batch to apply it to, so it is acknowledged and dropped: "
                    + "inventing one from the payload would be this service guessing at somebody "
                    + "else's document.", eventName);
            return;
        }
        if (DOCUMENT_AVAILABLE.equals(eventName)) {
            documentAvailable(payload, correlationId, payloadFileId);
        } else {
            generationFailed(payload, correlationId, payloadFileId);
        }
    }

    /**
     * Applies a document this service asked for and systemdocgenerator rendered.
     *
     * @param payload       the event's own fields
     * @param correlationId the batch the render was requested for
     * @param payloadFileId the payload the document was rendered from
     */
    private void documentAvailable(final JsonNode payload, final UUID correlationId,
            final UUID payloadFileId) {

        final UUID documentFileId = uuid(payload, DOCUMENT_FILE_SERVICE_ID);
        final Instant generatedAt = instant(payload, GENERATED_TIME);
        if (documentFileId == null || generatedAt == null) {
            LOG.warn("A document-available for batch {} carried no document or no instant, so it "
                    + "is acknowledged and dropped; the reconciler is what asks again.",
                    correlationId);
            return;
        }
        sink.documentAvailable(correlationId, payloadFileId, documentFileId, generatedAt,
                CompletedBy.EVENT);
    }

    /**
     * Applies a generation this service asked for and systemdocgenerator refused.
     *
     * <p>The generator's own words travel with it and are handed to the sink, never logged: they are
     * free text from another context and the batch's own reason is the bounded GENERATION_FAILED.
     *
     * @param payload       the event's own fields
     * @param correlationId the batch the render was requested for
     * @param payloadFileId the payload the render was requested for
     */
    private void generationFailed(final JsonNode payload, final UUID correlationId,
            final UUID payloadFileId) {

        final Instant failedAt = instant(payload, FAILED_TIME);
        if (failedAt == null) {
            LOG.warn("A generation-failed for batch {} carried no instant, so it is acknowledged "
                    + "and dropped; the reconciler is what asks again.", correlationId);
            return;
        }
        sink.generationFailed(correlationId, payloadFileId, text(payload, REASON), failedAt,
                CompletedBy.EVENT);
    }

    /**
     * The text of a field, or {@code null} where the event carries none.
     *
     * @param payload the event's own fields
     * @param field   the field name
     * @return the field's text, or {@code null}
     */
    private static String text(final JsonNode payload, final String field) {
        final JsonNode value = payload.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    /**
     * The identity a field names, or {@code null} where the event carries none or names something
     * that is not one.
     *
     * @param payload the event's own fields
     * @param field   the field name
     * @return the identity, or {@code null}
     */
    private static UUID uuid(final JsonNode payload, final String field) {
        final String value = text(payload, field);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (final IllegalArgumentException notAnIdentity) {
            LOG.warn("A public event's {} was not an identity, so the event names no batch.", field,
                    notAnIdentity);
            return null;
        }
    }

    /**
     * The instant a field names, read with the offset the estate publishes it under.
     *
     * <p>Both schemas' times are {@code date-time} strings and the platform sends them with an
     * offset rather than in UTC - a register generated at 18:04 on a British summer evening is
     * announced as {@code 18:04:11.412+01:00} - so the offset is part of the value and reading it
     * as local time would put every summer batch an hour out.
     *
     * @param payload the event's own fields
     * @param field   the field name
     * @return the instant, or {@code null} where the event carries none or none that parses
     */
    private static Instant instant(final JsonNode payload, final String field) {
        final String value = text(payload, field);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (final DateTimeParseException notATime) {
            LOG.warn("A public event's {} was not a date-time, so the outcome carries no instant.",
                    field, notATime);
            return null;
        }
    }
}
