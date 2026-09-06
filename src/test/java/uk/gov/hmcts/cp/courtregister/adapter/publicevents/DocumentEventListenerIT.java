package uk.gov.hmcts.cp.courtregister.adapter.publicevents;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.TestSocketUtils;
import uk.gov.hmcts.cp.courtregister.application.DocumentOutcomeSink;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;
import uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException;

/**
 * The subscription itself, against a broker: what {@code public.event} hands this service, and what
 * it keeps handing it across a restart.
 *
 * <p>{@code DocumentEventListenerTest} pins what the listener does with a message once it has one -
 * the envelope it parses, the source it ignores, the sink calls it makes. None of that is the risk
 * this leg actually carries. A batch's completion is announced once, by somebody else, at a moment
 * this service does not choose, and everything that decides whether that announcement is ever seen
 * lives outside the listener class: the topic is multicast, the subscription has to be durable, it
 * has to be registered under a client id the broker recognises as the same subscriber next time, and
 * the {@code CPPNAME} selector has to be narrow enough that a service subscribed to the estate's one
 * public topic is not handed the estate. Every one of those is configuration, and configuration is
 * exactly what a test with a doubled broker cannot get wrong on purpose.
 *
 * <p>So this suite owns a broker. An embedded Artemis on a port of its own (research §14), the real
 * {@code spring.jms} configuration this service deploys with, and the real listener container -
 * against which four things are asserted, and they are the four ways a completion is lost:
 *
 * <ul>
 *   <li><strong>a restart.</strong> A pod redeploys while systemdocgenerator renders. If the
 *       subscription is not durable, or its client id moved, the event is published to nobody and
 *       the batch waits for the reconciler to notice - which it will, but the reconciler is the
 *       safety net and not the mechanism (research §2). Here the listener is stopped, the event is
 *       published to a broker with nothing listening, and the listener is started again;</li>
 *   <li><strong>the selector.</strong> {@code public.event} carries every public event in the
 *       estate. The filter is the broker's, applied at routing time, so an event with another
 *       {@code CPPNAME} is never queued for this subscription at all - and the proof that it was
 *       excluded rather than merely slow is a second, admitted event published behind it that does
 *       arrive;</li>
 *   <li><strong>a duplicate.</strong> A durable subscription redelivers, and a batch that is
 *       announced twice must still be one outcome. What this level can say about that is that the
 *       two deliveries carry the <em>same</em> outcome - the same document, the same instant, read
 *       out of the event rather than off this pod's clock - so the duplicate is one the sink can
 *       absorb (it does, and {@code DocumentOutcomeSinkTest} is where that is pinned) rather than
 *       two different answers about one batch.</li>
 *   <li><strong>a sink that could not write.</strong> The register store is a database, and a
 *       database is down for a second or two now and then. The listener hands such a failure back to
 *       the container on purpose - it is the one thing it does not absorb - and that is worth
 *       nothing unless the container is running a session that can roll back, so the event has to
 *       reach the sink a second time.</li>
 * </ul>
 *
 * <p><strong>The sink is doubled and nothing else is.</strong> What an outcome does to a batch is
 * the sink's, and it is pinned where it belongs; what this suite needs is a place the outcome
 * arrives so that "arrived" can be told from "lost", which is all a mock is used for here.
 *
 * <p><strong>One thing this suite has already found out about the wiring it waits for.</strong> Boot
 * hands the listener container a caching connection factory, and a container that carries the client
 * id itself cannot set it - {@code setClientID call not supported on proxy for shared Connection} -
 * so the durable subscription's identity has to be established where the connection is. Recorded
 * because it is what the broker said, not as a design for T046, which is free to reach the same
 * subscription any way it likes.
 *
 * <p><strong>Why the generation half is switched on.</strong> The subscription is held only by a pod
 * that generates: an intake-only deployment has no use for outcomes and holding an unread durable
 * subscription would silently accumulate the estate's events on the broker. So the properties below
 * are a generating pod's, exactly as {@code GenerationMetricsContextTest} sets them, with the broker
 * url pointed at this suite's own.
 */
@SpringBootTest(properties = {
    "courtregister.generation.enabled=true",
    "courtregister.generation.sdg-mode=LIVE",
    "courtregister.generation.nn-mode=LIVE",
    "courtregister.generation.fileservice-mode=LIVE",
    "courtregister.generation.flag-mode=LIVE",
    "courtregister.fileservice.url=jdbc:postgresql://localhost:5432/fileservice",
    "courtregister.feature.endpoint=https://appconfig.internal",
    "courtregister.feature.label=ste86",
    "courtregister.endpoints.systemdocgenerator=http://systemdocgenerator.internal:8080",
    "courtregister.endpoints.notificationnotify=http://notificationnotify.internal:8080",
    "courtregister.email.templates.cr_standard=5c9a0e21-3d47-4f18-9b62-0a71c4e8d530"})
@ActiveProfiles("test") // no database and no Service Bus: this suite's only infrastructure is the broker
@DisplayName("the durable subscription to public.event")
class DocumentEventListenerIT {

    /** The estate's one multicast address, and the destination the subscription is on. */
    private static final String TOPIC = "public.event";

    /** The JMS string property the selector reads, and the framework's name for an event's name. */
    private static final String CPPNAME = "CPPNAME";

    private static final String DOCUMENT_AVAILABLE =
            "public.systemdocgenerator.events.document-available";

    private static final String GENERATION_FAILED =
            "public.systemdocgenerator.events.generation-failed";

    /**
     * An event the selector does not name, published onto the same topic.
     *
     * <p>A real one, from a context that really does publish onto {@code public.event}: the point is
     * that the subscription is on a topic the whole estate uses, so what keeps this service's
     * consumer from reading everybody's traffic has to be the broker's filter and not this service's
     * own patience.
     */
    private static final String ANOTHER_CONTEXTS_EVENT = "public.progression.events.hearing-resulted";

    /** The source this service stamps on its render requests and keeps events for. */
    private static final String OURS = "CourtRegisterService";

    /** When the render was asked for, which every one of these events echoes back. */
    private static final Instant REQUESTED_AT = Instant.parse("2026-09-04T18:00:04Z");

    /** When systemdocgenerator says it finished, which is the outcome's instant and not this pod's. */
    private static final Instant GENERATED_AT = Instant.parse("2026-09-04T18:02:41.412Z");

    /** When systemdocgenerator says it gave up. */
    private static final Instant FAILED_AT = Instant.parse("2026-09-04T18:03:07.918Z");

    /** Long enough for a broker round trip on a loaded build agent, short enough to fail a suite. */
    private static final Duration DELIVERED_WITHIN = Duration.ofSeconds(30);

    /** How long the subscription is given to appear on the broker before a case publishes. */
    private static final Duration SUBSCRIBED_WITHIN = Duration.ofSeconds(20);

    private static final Duration POLL = Duration.ofMillis(200);

    /**
     * The broker's address, chosen once when this class loads.
     *
     * <p>A free port asked for a second time is a different port, and the service would then be
     * pointed at a broker that is not the one these cases publish to - so it is asked for here and
     * read everywhere else.
     */
    private static final String BROKER_URL =
            "tcp://localhost:" + TestSocketUtils.findAvailableTcpPort();

    /**
     * The broker, started once and left running for the JVM.
     *
     * <p>The shared Testcontainers fixtures are treated the same way, and for the same reason: a
     * Spring context outlives the test class that caused it to be built, and a broker stopped in an
     * {@code @AfterAll} would be pulled out from under a listener the framework closes later.
     */
    private static EmbeddedActiveMQ broker;

    /** The publisher, which is systemdocgenerator's role here and so is nothing to do with the service. */
    private static ActiveMQConnectionFactory publisher;

    private final ApplicationContext context;

    /**
     * Where an outcome arrives, so that "arrived" can be told from "lost".
     *
     * <p>Created by the override when nothing declares one yet and replaced once T047 does, so this
     * suite says the same thing before and after the sink exists.
     */
    @MockitoBean
    private DocumentOutcomeSink sink;

    @Autowired
    DocumentEventListenerIT(final ApplicationContext context) {
        this.context = context;
    }

    /**
     * Points the service's subscription at this suite's broker, starting it if it is not up.
     *
     * <p>Everything else about the connection is the committed configuration: pub-sub domain,
     * durable subscription, client id, subscription name and selector all come from
     * {@code application.yaml}, because those five values are what this suite is about.
     *
     * @param registry the suite's property registry
     */
    @DynamicPropertySource
    static void pointTheSubscriptionAtTheEmbeddedBroker(final DynamicPropertyRegistry registry) {
        startTheBroker();
        registry.add("spring.artemis.broker-url", () -> BROKER_URL);
    }

    @BeforeEach
    void waitUntilTheSubscriptionIsOnTheBroker() {
        awaitTheSubscription();
    }

    @AfterEach
    void leaveTheListenerRunning() {
        startTheListener();
    }

    @Test
    @DisplayName("delivers an event published while the listener was stopped, once it restarts")
    void an_event_published_while_the_listener_is_stopped_should_be_delivered_on_restart() {
        final UUID batchId = UUID.randomUUID();
        final UUID payloadFileId = UUID.randomUUID();
        final UUID documentFileId = UUID.randomUUID();

        stopTheListener();
        publish(DOCUMENT_AVAILABLE,
                documentAvailable(batchId, payloadFileId, documentFileId, GENERATED_AT));
        startTheListener();

        verify(sink, timeout(DELIVERED_WITHIN.toMillis()))
                .documentAvailable(batchId, payloadFileId, documentFileId, GENERATED_AT,
                        CompletedBy.EVENT);
    }

    @Test
    @DisplayName("is never handed an event carrying another context's CPPNAME")
    void the_selector_should_keep_another_contexts_event_off_the_subscription() {
        final UUID excluded = UUID.randomUUID();
        final UUID ours = UUID.randomUUID();
        final UUID payloadFileId = UUID.randomUUID();

        // Published first, and on its own connection, so the broker has routed it before the
        // admitted event below is sent: what arrives afterwards therefore says the excluded event
        // was filtered rather than merely slower.
        publish(ANOTHER_CONTEXTS_EVENT,
                documentAvailable(excluded, UUID.randomUUID(), UUID.randomUUID(), GENERATED_AT));
        publish(GENERATION_FAILED, generationFailed(ours, payloadFileId, "template render timed out"));

        verify(sink, timeout(DELIVERED_WITHIN.toMillis()))
                .generationFailed(ours, payloadFileId, "template render timed out", FAILED_AT,
                        CompletedBy.EVENT);
        verify(sink, never()).documentAvailable(eq(excluded), any(), any(), any(), any());
        verify(sink, never()).generationFailed(eq(excluded), any(), any(), any(), any());
    }

    @Test
    @DisplayName("turns two announcements of one batch into one outcome, twice stated")
    void two_events_for_one_batch_should_be_one_outcome() {
        final UUID batchId = UUID.randomUUID();
        final UUID payloadFileId = UUID.randomUUID();
        final UUID documentFileId = UUID.randomUUID();

        publish(DOCUMENT_AVAILABLE,
                documentAvailable(batchId, payloadFileId, documentFileId, GENERATED_AT));
        publish(DOCUMENT_AVAILABLE,
                documentAvailable(batchId, payloadFileId, documentFileId, GENERATED_AT));

        // The same outcome both times, down to the instant: a listener stamping its own clock on
        // the outcome would make the redelivery a second, different answer about one batch, and no
        // amount of idempotency in the sink can absorb two answers that disagree.
        verify(sink, timeout(DELIVERED_WITHIN.toMillis()).times(2))
                .documentAvailable(batchId, payloadFileId, documentFileId, GENERATED_AT,
                        CompletedBy.EVENT);
        verifyNoMoreInteractions(sink);
    }

    /**
     * The one failure the listener deliberately does not absorb, and the only thing that makes not
     * absorbing it worth anything.
     *
     * <p>{@code DocumentEventListener}'s own account of itself is that a message it cannot make sense
     * of is said out loud and acknowledged, and that "the one failure worth a redelivery is the
     * sink's". A sink failure is a register store that was not there for the second or two the
     * outcome arrived in - and unless the session the listener runs in is transacted, the exception
     * it raises reaches a container that acknowledged the message before it ever called the listener,
     * so the outcome is dropped and the batch waits for the reconciler's grace period instead. The
     * redelivery is the claim; this is where it is either true or a comment.
     */
    @Test
    @DisplayName("offers the event again when the sink could not apply it")
    void an_outcome_the_sink_could_not_apply_should_be_offered_again() {
        final UUID batchId = UUID.randomUUID();
        final UUID payloadFileId = UUID.randomUUID();
        final UUID documentFileId = UUID.randomUUID();

        doThrow(new StoreUnavailableException("the register store is unavailable", null))
                .doNothing()
                .when(sink).documentAvailable(batchId, payloadFileId, documentFileId, GENERATED_AT,
                        CompletedBy.EVENT);

        publish(DOCUMENT_AVAILABLE,
                documentAvailable(batchId, payloadFileId, documentFileId, GENERATED_AT));

        verify(sink, timeout(DELIVERED_WITHIN.toMillis()).times(2))
                .documentAvailable(batchId, payloadFileId, documentFileId, GENERATED_AT,
                        CompletedBy.EVENT);
    }

    // --- the broker -------------------------------------------------------------------------------

    /**
     * Starts the broker, once per JVM.
     *
     * <p>Artemis declares {@code throws Exception} on both the acceptor configuration and the start,
     * so there is no narrower type to catch; a broker that will not start is fatal to every case
     * here and is rethrown as such rather than left to surface as a connection refusal thirty
     * seconds later.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void startTheBroker() {
        if (broker != null) {
            return;
        }
        final Path data = temporaryDataDirectory();
        try {
            final ConfigurationImpl configuration = new ConfigurationImpl();
            configuration.setName("public-event-test-broker")
                    // Nothing here outlives the JVM, and the durable subscription this suite is
                    // about survives a listener restart rather than a broker one: an in-memory
                    // journal is the whole of what these cases need.
                    .setPersistenceEnabled(false)
                    .setSecurityEnabled(false)
                    .setJMXManagementEnabled(false)
                    .setJournalDirectory(data.resolve("journal").toString())
                    .setBindingsDirectory(data.resolve("bindings").toString())
                    .setPagingDirectory(data.resolve("paging").toString())
                    .setLargeMessagesDirectory(data.resolve("large-messages").toString());
            configuration.addAcceptorConfiguration("tcp", BROKER_URL);
            broker = new EmbeddedActiveMQ().setConfiguration(configuration);
            broker.start();
        } catch (final Exception problem) {
            throw new IllegalStateException("the embedded broker would not start on " + BROKER_URL,
                    problem);
        }
        publisher = new ActiveMQConnectionFactory(BROKER_URL);
    }

    private static Path temporaryDataDirectory() {
        try {
            return Files.createTempDirectory("court-register-public-event-broker");
        } catch (final IOException problem) {
            throw new UncheckedIOException("no temporary directory for the broker's data", problem);
        }
    }

    /**
     * Publishes one framework envelope onto the topic, as systemdocgenerator does.
     *
     * <p>A connection of its own each time, and closed before the next: the broker has routed a
     * message by the time its send returns, so two publishes in sequence are two routings in
     * sequence - which is what lets a case say "this one arrived and that one never did" rather than
     * "this one arrived first".
     */
    private static void publish(final String eventName, final String body) {
        try (JMSContext session = publisher.createContext()) {
            final Topic topic = session.createTopic(TOPIC);
            final TextMessage message = session.createTextMessage(body);
            // The selector's property, set as a JMS string property rather than carried in the body:
            // a broker filters on properties and cannot read the envelope.
            message.setStringProperty(CPPNAME, eventName);
            session.createProducer().send(topic, message);
        } catch (final JMSException problem) {
            throw new IllegalStateException("could not publish " + eventName + " to " + TOPIC,
                    problem);
        }
    }

    /**
     * Waits for the subscription to appear on the broker, and asserts nothing.
     *
     * <p>A container starts asynchronously, so a case that published the moment the context refreshed
     * could be publishing to a topic nobody had subscribed to yet - a race that would read as a lost
     * event and be one only in the test. This waits that race out. It does not fail when the
     * subscription never appears, because a subscription that never appears is one of the ways an
     * event is lost, and the case below is where this suite says so.
     */
    private static void awaitTheSubscription() {
        final Instant deadline = Instant.now().plus(SUBSCRIBED_WITHIN);
        while (subscriptionsOnTheTopic().isEmpty() && Instant.now().isBefore(deadline)) {
            pause();
        }
    }

    /**
     * The queues bound to the topic, which for a durable subscription is the subscription itself.
     *
     * <p>{@code throws Exception} again, from the post office, and again there is nothing narrower to
     * catch: a broker that cannot be asked what is bound to its own address is not a broker any case
     * here can go on to use.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static List<Queue> subscriptionsOnTheTopic() {
        try {
            return broker.getActiveMQServer().getPostOffice()
                    .listQueuesForAddress(SimpleString.of(TOPIC));
        } catch (final Exception problem) {
            throw new IllegalStateException("could not read the broker's queues for " + TOPIC,
                    problem);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(POLL.toMillis());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the subscription", interrupted);
        }
    }

    // --- the listener's own lifecycle -------------------------------------------------------------

    /**
     * Stops the listener container, which is what a redeploying pod does to its subscription.
     *
     * <p>Stopping the container closes the consumer and leaves the durable subscription standing on
     * the broker, which is the distinction these cases exist to make: a subscription that is not
     * durable is dropped here, and the event published next is published to nobody.
     */
    private void stopTheListener() {
        listenerRegistry().ifPresent(JmsListenerEndpointRegistry::stop);
    }

    private void startTheListener() {
        listenerRegistry().ifPresent(JmsListenerEndpointRegistry::start);
    }

    /**
     * The registry the listener container's lifecycle is driven through, when there is one.
     *
     * <p>Empty until T046 declares the {@code @JmsListener} that puts a container in it: there is no
     * subscription to stop, no event is delivered, and the cases above report that as the lost event
     * it is.
     */
    private Optional<JmsListenerEndpointRegistry> listenerRegistry() {
        return Optional.ofNullable(
                context.getBeanProvider(JmsListenerEndpointRegistry.class).getIfAvailable());
    }

    // --- the envelopes systemdocgenerator publishes -----------------------------------------------

    /**
     * A {@code document-available} envelope, shaped as the vendored schema and the framework's
     * {@code JsonEnvelope} require: {@code _metadata} naming the event beside the payload's own
     * fields, with {@code _metadata.stream.id} the batch the event belongs to.
     */
    private static String documentAvailable(final UUID batchId, final UUID payloadFileId,
            final UUID documentFileId, final Instant generatedAt) {
        return """
                {
                  "_metadata": {
                    "id": "%s",
                    "name": "%s",
                    "createdAt": "%s",
                    "source": "systemdocgenerator",
                    "stream": {"id": "%s"}
                  },
                  "payloadFileServiceId": "%s",
                  "templateIdentifier": "OEE_Layout5",
                  "conversionFormat": "pdf",
                  "requestedTime": "%s",
                  "documentFileServiceId": "%s",
                  "generatedTime": "%s",
                  "generateVersion": 1,
                  "sourceCorrelationId": "%s",
                  "originatingSource": "%s"
                }
                """.formatted(UUID.randomUUID(), DOCUMENT_AVAILABLE, REQUESTED_AT, batchId,
                payloadFileId, REQUESTED_AT, documentFileId, generatedAt, batchId, OURS);
    }

    /** A {@code generation-failed} envelope, carrying systemdocgenerator's own words for why. */
    private static String generationFailed(final UUID batchId, final UUID payloadFileId,
            final String reason) {
        return """
                {
                  "_metadata": {
                    "id": "%s",
                    "name": "%s",
                    "createdAt": "%s",
                    "source": "systemdocgenerator",
                    "stream": {"id": "%s"}
                  },
                  "payloadFileServiceId": "%s",
                  "templateIdentifier": "OEE_Layout5",
                  "conversionFormat": "pdf",
                  "requestedTime": "%s",
                  "failedTime": "%s",
                  "reason": "%s",
                  "sourceCorrelationId": "%s",
                  "originatingSource": "%s"
                }
                """.formatted(UUID.randomUUID(), GENERATION_FAILED, REQUESTED_AT, batchId,
                payloadFileId, REQUESTED_AT, FAILED_AT, reason, batchId, OURS);
    }
}
