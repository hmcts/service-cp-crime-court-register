package uk.gov.hmcts.cp.courtregister.adapter.publicevents;

import tools.jackson.databind.JsonNode;

/**
 * A framework {@code JsonEnvelope} as this service reads it: what the event is called, and what it
 * says.
 *
 * <p>Public events on the {@code public.event} topic are an envelope with two halves, the
 * {@code _metadata} object that names and traces the event and the payload beside it. Only two facts
 * of the first half matter here, and the second is passed on as a tree rather than as a typed model
 * because it belongs to systemdocgenerator: this service adapts to the two event schemas vendored
 * under {@code contracts/systemdocgenerator/} and never redefines them.
 *
 * <p>The name is read from the envelope itself rather than trusted from the JMS {@code CPPNAME}
 * property. The property is the broker's selector, which is how the subscription avoids being handed
 * every public event in the estate; the envelope is the message's own account of what it is, and
 * where the two disagree the message is not what the header claimed.
 *
 * @param metadataName the event name from {@code _metadata.name}
 * @param payload      everything the event says, as a tree
 */
public record PublicEventEnvelope(String metadataName, JsonNode payload) {

    /**
     * Reads one message body as an envelope.
     *
     * <p><strong>Seam only.</strong> The parse lands with T046; until then this throws, so that
     * {@code DocumentEventListenerTest} records a failing assertion rather than a compile error.
     *
     * @param body the JMS text message's body
     * @return the envelope it carries
     */
    public static PublicEventEnvelope parse(final String body) {
        throw new UnsupportedOperationException("T046");
    }
}
