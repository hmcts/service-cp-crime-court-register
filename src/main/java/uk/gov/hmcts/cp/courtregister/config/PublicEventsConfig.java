package uk.gov.hmcts.cp.courtregister.config;

import jakarta.jms.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.jms.autoconfigure.JmsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import uk.gov.hmcts.cp.courtregister.adapter.publicevents.DeliveryObserver;
import uk.gov.hmcts.cp.courtregister.adapter.publicevents.DocumentEventListener;
import uk.gov.hmcts.cp.courtregister.application.DocumentOutcomeSink;

/**
 * The listener container the public-event subscription runs in.
 *
 * <p>Five settings make it the subscription this service needs rather than a queue consumer that
 * happens to work: {@code pub-sub-domain}, because {@code public.event} is a topic and a consumer
 * that read it as a queue would compete with every other subscriber on the estate;
 * {@code subscription-durable} with a {@code client-id}, because a document rendered while this pod
 * was restarting must still be delivered, which is one of the things
 * {@code DocumentEventListenerIT} proves; the {@code CPPNAME} selector, so the broker filters
 * the topic rather than this service filtering it after delivery; and a <strong>transacted
 * session</strong>, which is the subject of the next paragraph. The selector, the destination and
 * the subscription's name are on the listener itself, read from {@code courtregister.publicevents.*};
 * the three broker-shape settings are Spring's own {@code spring.jms.*} keys and are read from there.
 *
 * <p><strong>The session is transacted, and the listener's contract depends on it.</strong>
 * {@link DocumentEventListener} absorbs every message it cannot make sense of and hands exactly one
 * failure back to the container - the sink's, which is the register store having been unavailable
 * for the second the outcome arrived in. That hand-back is only worth anything if the message is
 * still the broker's to offer again: a {@code DefaultMessageListenerContainer} left at the default
 * {@code AUTO_ACKNOWLEDGE} acknowledges before it invokes the listener, so the exception would reach
 * a container with nothing left to roll back and the outcome would be lost until the grace-period
 * reconciler noticed it ten minutes later. Boot's own
 * {@code DefaultJmsListenerContainerFactoryConfigurer} sets this for precisely that reason and is
 * not used here, so it is set here instead
 * ({@code DocumentEventListenerIT.an_outcome_the_sink_could_not_apply_should_be_offered_again}).
 *
 * <p><strong>And the failure is reported at ERROR.</strong> A container with no error handler logs a
 * listener failure at WARN, which is not the level a lost-then-recovered outcome is worth reading at
 * (constitution Principle VI). The handler below is the container-level half of that line; the
 * batch it was about is named by the listener's own line, which is where the identity is known.
 *
 * <p><strong>The container is given the native connection factory.</strong> Boot's shared
 * {@code jmsConnectionFactory} is a caching one, and a container that carries the client id cannot
 * set it on a shared connection - {@code setClientID call not supported on proxy for shared
 * Connection}. The durable subscription's identity therefore has to be established where the
 * connection is, so the container is handed the unwrapped factory and opens a connection of its own,
 * which is in any case the arrangement {@code DefaultMessageListenerContainer} is built for: it
 * caches the connection, the session and the consumer itself.
 *
 * <p><strong>Its auto-startup is tied to {@code courtregister.generation.enabled} and to
 * {@code completion=event}.</strong> A deployment running the intake half alone has no use for
 * outcomes and should hold no durable subscription: an unread durable subscription accumulates every
 * matching event on the broker until somebody notices. The first half of the rule is this class's
 * own condition, so an intake-only pod builds none of this at all; the second is the
 * {@code poll-only} escape hatch, which asks for no broker and must therefore subscribe to nothing.
 *
 * <p><strong>One consumer, deliberately.</strong> A non-shared durable subscription admits exactly
 * one, and a second would be refused by the broker rather than double the throughput. Outcomes
 * arrive at the rate court centres are rendered at, which is tens a night.
 *
 * <p><strong>And none of it on a JVM started to run one operations command.</strong> That one
 * consumer is the whole reason: a command that subscribed would take the topic away from the pod
 * waiting for the outcomes and give it to a process about to exit. The whole configuration goes
 * rather than the listener alone, because the container factory is here too and a factory with no
 * {@code @JmsListener} to create a container from is a half-absence to reason about
 * ({@link CliModeConfig}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "courtregister.generation", name = "enabled", havingValue = "true")
@Conditional(CliModeConfig.NotCliMode.class)
public class PublicEventsConfig {

    /**
     * The container factory the listener names, held as a constant because the listener names it in
     * an annotation and a bean name that only agreed by convention would be a subscription nothing
     * created.
     */
    public static final String LISTENER_CONTAINER_FACTORY = "publicEventListenerContainerFactory";

    private static final Logger LOG = LoggerFactory.getLogger(PublicEventsConfig.class);

    /** The concurrency a non-shared durable subscription permits, which is one. */
    private static final String ONE_CONSUMER = "1";

    /**
     * The container factory the listener's subscription is created from.
     *
     * @param connectionFactory the broker connection, unwrapped to the native factory so the
     *                          container may establish the client id on its own connection
     * @param jms               Spring's own JMS settings: the topic domain, the durable flag and the
     *                          client id that is half the subscription's identity
     * @param generation        the downstream half's settings, for the completion mechanism
     * @return the factory, durable and topic-scoped
     */
    @Bean(LISTENER_CONTAINER_FACTORY)
    public DefaultJmsListenerContainerFactory publicEventListenerContainerFactory(
            final ConnectionFactory connectionFactory,
            final JmsProperties jms,
            final GenerationProperties generation) {

        final DefaultJmsListenerContainerFactory factory =
                new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(ConnectionFactoryUnwrapper.unwrap(connectionFactory));
        factory.setPubSubDomain(jms.isPubSubDomain());
        factory.setSubscriptionDurable(jms.isSubscriptionDurable());
        factory.setClientId(jms.getClientId());
        factory.setConcurrency(ONE_CONSUMER);
        // The listener hands the sink's failure back on purpose; this is what leaves the broker
        // something to hand back to.
        factory.setSessionTransacted(true);
        factory.setErrorHandler(PublicEventsConfig::notApplied);
        factory.setAutoStartup(
                GenerationProperties.COMPLETION_EVENT.equals(generation.completion()));
        return factory;
    }

    /**
     * Says at ERROR that a delivery was rolled back, and is the reason the container does not say it
     * at WARN.
     *
     * <p>The cause's type is named in the line and the throwable carries the stack: a store outage
     * and a bug in the sink are the same sentence from here and different investigations, and the
     * type is the only bounded thing that tells them apart. Nothing else is added - which batch this
     * was about is on {@link DocumentEventListener}'s own line, where the identity is known, and no
     * value here comes from a register (constitution Principle VII).
     *
     * @param notApplied whatever the listener handed back, which is the sink's failure or the
     *                   container's own
     */
    private static void notApplied(final Throwable notApplied) {
        LOG.error("A public event was not applied, so the transacted session is rolled back and the "
                + "broker offers the message again. cause={}", notApplied.getClass().getName(),
                notApplied);
    }

    /**
     * The listener the subscription delivers to.
     *
     * <p>Declared here rather than annotated as a component for the same reason
     * {@link ProcessedLogConfig}'s beans are: the sink it writes through is the register store's
     * neighbour and exists only where a database does, and a subscription with nowhere to apply an
     * outcome is a durable subscription filling up on the broker. Where there is no sink there is
     * therefore no listener, no {@code @JmsListener} to register and no subscription - which is the
     * shape a context-load test with no database has, and the shape
     * {@code DocumentEventListenerIT} gives itself a sink to escape.
     *
     * <p>The delivery observer is asked for rather than required, because the component that serves
     * it is {@link GenerationHealth}'s and that configuration is outside the {@code test} profile:
     * a context-load suite with no health component still gets a listener, and gets it with the
     * observer that tells nobody rather than with none at all.
     *
     * @param outcomes  where a recognised outcome is applied, if this context has anywhere
     * @param metrics   where an event this service did not ask for is counted
     * @param observers told that the broker served this subscription, if anything on this context
     *                  is listening for that
     * @return the listener, or {@code null} where no outcome could be applied
     */
    @Bean
    public DocumentEventListener documentEventListener(
            final ObjectProvider<DocumentOutcomeSink> outcomes, final GenerationMetrics metrics,
            final ObjectProvider<DeliveryObserver> observers) {

        final DocumentOutcomeSink sink = outcomes.getIfAvailable();
        final DocumentEventListener listener = sink == null
                ? null
                : new DocumentEventListener(sink, metrics,
                        observers.getIfAvailable(() -> DeliveryObserver.NONE));
        if (listener == null) {
            LOG.warn("No outcome sink is on this context, so no durable subscription to the "
                    + "public-event topic is held: an outcome has nowhere to be applied without "
                    + "the register store.");
        }
        return listener;
    }
}
