package uk.gov.hmcts.cp.courtregister.config;

import org.springframework.jms.config.DefaultJmsListenerContainerFactory;

/**
 * The listener container the public-event subscription runs in.
 *
 * <p>Four settings make it the subscription this service needs rather than a queue consumer that
 * happens to work: {@code pub-sub-domain}, because {@code public.event} is a topic and a consumer
 * that read it as a queue would compete with every other subscriber on the estate;
 * {@code subscription-durable} with a {@code client-id}, because a document rendered while this pod
 * was restarting must still be delivered, which is the whole of what
 * {@code DocumentEventListenerIT} proves; and the {@code CPPNAME} selector, so the broker filters
 * the topic rather than this service filtering it after delivery.
 *
 * <p><strong>Its auto-startup is tied to {@code courtregister.generation.enabled}.</strong> A
 * deployment running the intake half alone has no use for outcomes and should hold no durable
 * subscription: an unread durable subscription accumulates every matching event on the broker until
 * somebody notices.
 *
 * <p><strong>Seam only.</strong> The configuration lands with T046; until then this is annotated
 * with nothing and creates no beans, so a context that loads today is the context 001 left.
 */
public class PublicEventsConfig {

    /**
     * The container factory the listener's subscription is created from.
     *
     * @return the factory, durable and topic-scoped
     */
    public DefaultJmsListenerContainerFactory publicEventListenerContainerFactory() {
        throw new UnsupportedOperationException("T046");
    }
}
