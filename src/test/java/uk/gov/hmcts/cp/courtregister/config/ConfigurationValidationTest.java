package uk.gov.hmcts.cp.courtregister.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Holds the configuration surface to the plan's table, and holds startup to the rules that make the
 * service safe to run.
 *
 * <p>The two timing relationships and the credential rule are checked at startup precisely because
 * they fail quietly otherwise: a run that outlives its claim shows up as a duplicate submission
 * weeks later, and a silently preferred credential source is how a deployed pod ends up talking to
 * the wrong broker. The court register adds a third family of them — a submission that cannot
 * authorise its POST, a retry policy that cannot make one, and the C29 pre-send validator switched
 * off where the service is deployed.
 *
 * <p>The plan's Spring-level rows — datasource, Flyway, server and management — are not asserted
 * here. They arrive with {@code application.yaml} and are proven by the context boot and the
 * HTTP-surface and readiness suites.
 */
class ConfigurationValidationTest {

    /** The emulator connection string, the local and CI credential. */
    private static final String CONNECTION_STRING =
            "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;"
                    + "SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;";

    private static final String NAMESPACE = "courtregister.servicebus.windows.net";

    private static final String CONNECTION_STRING_PROPERTY =
            "courtregister.servicebus.connection-string=" + CONNECTION_STRING;

    private static final String NAMESPACE_PROPERTY =
            "courtregister.servicebus.namespace=" + NAMESPACE;

    /**
     * The identity the query-side payload fallback authorises with. Carried by every case here that
     * is not about it, because the live payload source cannot work without one and startup says so.
     */
    private static final String PAYLOAD_IDENTITY_PROPERTY =
            "courtregister.results.system-user-id=9f61bdbb-6f1a-4c0f-9a3d-6b8f0f1c2a44";

    /**
     * The endpoint and identity the submission needs. Carried by every case here that is not about
     * them: the register is POSTed to progression on every hearing that produces one, and a POST
     * with nowhere to go or nobody to be from is refused every time.
     */
    private static final String PROGRESSION_ENDPOINT_PROPERTY =
            "courtregister.progression.base-url=http://localhost:8080";

    private static final String PROGRESSION_IDENTITY_PROPERTY =
            "courtregister.progression.system-user-id=4d3c2b1a-9e8f-4a7b-8c6d-5e4f3a2b1c09";

    /**
     * The endpoint and identity the live now-subscriptions source needs, carried for the same reason
     * the two above are: the live source is the default, and startup refuses one that cannot ask
     * reference data anything.
     */
    private static final String REFDATA_ENDPOINT_PROPERTY =
            "courtregister.referencedata.base-url=http://localhost:8080";

    private static final String REFDATA_IDENTITY_PROPERTY =
            "courtregister.referencedata.system-user-id=2c7b1e64-0f4a-4f0e-9b2c-8d1a6f3e5c07";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PropertiesTestConfiguration.class)
                    .withPropertyValues(PAYLOAD_IDENTITY_PROPERTY, PROGRESSION_ENDPOINT_PROPERTY,
                            PROGRESSION_IDENTITY_PROPERTY, REFDATA_ENDPOINT_PROPERTY,
                            REFDATA_IDENTITY_PROPERTY);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CourtRegisterProperties.class)
    @Import(PropertiesValidator.class)
    static class PropertiesTestConfiguration {
    }

    @Nested
    @DisplayName("binding")
    class Binding {

        @Test
        void every_setting_should_fall_back_to_the_documented_default() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY).run(context -> {
                assertThat(context).hasNotFailed();
                final CourtRegisterProperties properties =
                        context.getBean(CourtRegisterProperties.class);

                assertThat(properties.consumer().enabled()).isTrue();

                assertThat(properties.servicebus().connectionString()).isEqualTo(CONNECTION_STRING);
                assertThat(properties.servicebus().namespace()).isNull();
                assertThat(properties.servicebus().queueName()).isEqualTo("courtregister.requests");
                assertThat(properties.servicebus().maxConcurrentCalls()).isEqualTo(2);
                assertThat(properties.servicebus().maxDeliveryCount()).isEqualTo(5);
                assertThat(properties.servicebus().maxAutoLockRenewDuration())
                        .isEqualTo(Duration.ofMinutes(5));
                assertThat(properties.servicebus().healthStaleness())
                        .isEqualTo(Duration.ofSeconds(60));

                assertThat(properties.claim().lease()).isEqualTo(Duration.ofMinutes(5));
                assertThat(properties.claim().processingDeadline()).isEqualTo(Duration.ofMinutes(4));

                assertThat(properties.store().probeInterval()).isEqualTo(Duration.ofSeconds(10));

                assertThat(properties.stub().payloadFailureMode()).isEqualTo(PayloadFailureMode.NONE);

                assertThat(properties.payload().mode()).isEqualTo(PayloadSourceMode.LIVE);
                assertThat(properties.payload().redis().host()).isEqualTo("localhost");
                assertThat(properties.payload().redis().port()).isEqualTo(6379);
                assertThat(properties.payload().redis().password()).isNull();
                assertThat(properties.payload().redis().ssl()).isFalse();
                assertThat(properties.payload().redis().keyPrefix()).isEqualTo("INT_");
                assertThat(properties.payload().redis().connectTimeout())
                        .isEqualTo(Duration.ofSeconds(5));
                assertThat(properties.payload().redis().commandTimeout())
                        .isEqualTo(Duration.ofSeconds(5));
                assertThat(properties.payload().fallback().maxAttempts()).isEqualTo(3);
                assertThat(properties.payload().fallback().retryInterval())
                        .isEqualTo(Duration.ofSeconds(1));
                assertThat(properties.payload().fallback().connectTimeout())
                        .isEqualTo(Duration.ofSeconds(5));
                assertThat(properties.payload().fallback().readTimeout())
                        .isEqualTo(Duration.ofSeconds(10));

                assertThat(properties.referencedata().mode())
                        .isEqualTo(SubscriptionsSourceMode.LIVE);
                assertThat(properties.referencedata().headers()).isEmpty();
                assertThat(properties.referencedata().maxAttempts()).isEqualTo(3);
                assertThat(properties.referencedata().retryInterval())
                        .isEqualTo(Duration.ofSeconds(1));
                assertThat(properties.referencedata().connectTimeout())
                        .isEqualTo(Duration.ofSeconds(5));
                assertThat(properties.referencedata().readTimeout())
                        .isEqualTo(Duration.ofSeconds(10));

                assertThat(properties.progression().headers()).isEmpty();
                assertThat(properties.progression().maxAttempts()).isEqualTo(4);
                assertThat(properties.progression().initialBackoff())
                        .isEqualTo(Duration.ofMillis(500));
                assertThat(properties.progression().maxBackoff()).isEqualTo(Duration.ofSeconds(20));
                assertThat(properties.progression().connectTimeout())
                        .isEqualTo(Duration.ofSeconds(5));
                assertThat(properties.progression().readTimeout())
                        .isEqualTo(Duration.ofSeconds(10));

                assertThat(properties.submission().validateOutbound())
                        .as("the C29 pre-send validator is on unless something turns it off")
                        .isTrue();

                assertThat(properties.results().baseUrl())
                        .as("an endpoint a service invents is an endpoint it can talk to by mistake")
                        .isNull();
            });
        }

        @Test
        void every_setting_should_be_overridable() {
            runner.withPropertyValues(
                    NAMESPACE_PROPERTY,
                    "courtregister.consumer.enabled=false",
                    "courtregister.servicebus.queue-name=other.requests",
                    "courtregister.servicebus.max-concurrent-calls=8",
                    "courtregister.servicebus.max-delivery-count=3",
                    "courtregister.servicebus.max-auto-lock-renew-duration=9m",
                    "courtregister.servicebus.health-staleness=90s",
                    "courtregister.claim.lease=8m",
                    "courtregister.claim.processing-deadline=7m",
                    "courtregister.store.probe-interval=45s",
                    "courtregister.stub.payload-failure-mode=TRANSIENT",
                    "courtregister.payload.redis.host=cache.internal",
                    "courtregister.payload.redis.port=6380",
                    "courtregister.payload.redis.password=a-secret",
                    "courtregister.payload.redis.ssl=true",
                    "courtregister.payload.redis.key-prefix=OTHER_",
                    "courtregister.payload.fallback.max-attempts=2",
                    "courtregister.payload.fallback.retry-interval=3s",
                    "courtregister.results.base-url=http://results.internal:8080",
                    "courtregister.referencedata.max-attempts=4",
                    "courtregister.referencedata.retry-interval=2s",
                    "courtregister.referencedata.headers.X-Mesh-Group=court-register",
                    "courtregister.progression.max-attempts=2",
                    "courtregister.progression.initial-backoff=250ms",
                    "courtregister.progression.max-backoff=10s",
                    "courtregister.progression.connect-timeout=3s",
                    "courtregister.progression.read-timeout=15s",
                    "courtregister.progression.headers.X-Mesh-Group=progression").run(context -> {
                        assertThat(context).hasNotFailed();
                        final CourtRegisterProperties properties =
                                context.getBean(CourtRegisterProperties.class);

                        assertThat(properties.consumer().enabled()).isFalse();

                        assertThat(properties.servicebus().connectionString()).isNull();
                        assertThat(properties.servicebus().namespace()).isEqualTo(NAMESPACE);
                        assertThat(properties.servicebus().queueName()).isEqualTo("other.requests");
                        assertThat(properties.servicebus().maxConcurrentCalls()).isEqualTo(8);
                        assertThat(properties.servicebus().maxDeliveryCount()).isEqualTo(3);
                        assertThat(properties.servicebus().maxAutoLockRenewDuration())
                                .isEqualTo(Duration.ofMinutes(9));
                        assertThat(properties.servicebus().healthStaleness())
                                .isEqualTo(Duration.ofSeconds(90));

                        assertThat(properties.claim().lease()).isEqualTo(Duration.ofMinutes(8));
                        assertThat(properties.claim().processingDeadline())
                                .isEqualTo(Duration.ofMinutes(7));

                        assertThat(properties.store().probeInterval())
                                .isEqualTo(Duration.ofSeconds(45));

                        assertThat(properties.stub().payloadFailureMode())
                                .isEqualTo(PayloadFailureMode.TRANSIENT);

                        assertThat(properties.payload().redis().host()).isEqualTo("cache.internal");
                        assertThat(properties.payload().redis().port()).isEqualTo(6380);
                        assertThat(properties.payload().redis().password()).isEqualTo("a-secret");
                        assertThat(properties.payload().redis().ssl()).isTrue();
                        assertThat(properties.payload().redis().keyPrefix()).isEqualTo("OTHER_");
                        assertThat(properties.payload().fallback().maxAttempts()).isEqualTo(2);
                        assertThat(properties.payload().fallback().retryInterval())
                                .isEqualTo(Duration.ofSeconds(3));

                        assertThat(properties.results().baseUrl())
                                .isEqualTo("http://results.internal:8080");

                        assertThat(properties.referencedata().maxAttempts()).isEqualTo(4);
                        assertThat(properties.referencedata().retryInterval())
                                .isEqualTo(Duration.ofSeconds(2));
                        assertThat(properties.referencedata().headers())
                                .isEqualTo(Map.of("X-Mesh-Group", "court-register"));

                        assertThat(properties.progression().baseUrl())
                                .isEqualTo("http://localhost:8080");
                        assertThat(properties.progression().maxAttempts()).isEqualTo(2);
                        assertThat(properties.progression().initialBackoff())
                                .isEqualTo(Duration.ofMillis(250));
                        assertThat(properties.progression().maxBackoff())
                                .isEqualTo(Duration.ofSeconds(10));
                        assertThat(properties.progression().connectTimeout())
                                .isEqualTo(Duration.ofSeconds(3));
                        assertThat(properties.progression().readTimeout())
                                .isEqualTo(Duration.ofSeconds(15));
                        assertThat(properties.progression().headers())
                                .isEqualTo(Map.of("X-Mesh-Group", "progression"));
                    });
        }
    }

    @Nested
    @DisplayName("the run must finish before the claim can be reclaimed")
    class ProcessingDeadlineAgainstLease {

        @Test
        void a_deadline_equal_to_the_lease_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.claim.lease=5m",
                    "courtregister.claim.processing-deadline=5m").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.claim.processing-deadline")
                                .hasMessageContaining("courtregister.claim.lease");
                    });
        }

        @Test
        void a_deadline_longer_than_the_lease_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.claim.lease=5m",
                    "courtregister.claim.processing-deadline=6m").run(context -> {
                        assertThat(context).hasFailed();
                        // "It failed" is not the assertion. Whoever set the deadline sees only the
                        // startup failure, and one that does not name both settings and the relation
                        // between them leaves them guessing which of a dozen durations to move.
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.claim.processing-deadline")
                                .hasMessageContaining("courtregister.claim.lease")
                                .hasMessageContaining("strictly shorter");
                    });
        }

        @Test
        void a_deadline_shorter_than_the_lease_should_start() {
            // The renewal is raised alongside the deadline so this case tests one rule only: at
            // 4m59s the default 5m renewal would break the lock rule, which has its own cases below.
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.claim.lease=5m",
                    "courtregister.claim.processing-deadline=PT4M59S",
                    "courtregister.servicebus.max-auto-lock-renew-duration=PT5M29S").run(context ->
                            assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("the broker lock must outlive any legitimate run")
    class LockRenewalAgainstDeadline {

        @Test
        void a_renewal_shorter_than_the_deadline_plus_the_margin_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.claim.lease=5m",
                    "courtregister.claim.processing-deadline=4m",
                    "courtregister.servicebus.max-auto-lock-renew-duration=PT4M29S")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(
                                        "courtregister.servicebus.max-auto-lock-renew-duration")
                                .hasMessageContaining("courtregister.claim.processing-deadline");
                    });
        }

        @Test
        void a_renewal_exactly_the_deadline_plus_the_margin_should_start() {
            // The margin is a fixed 30 seconds, so this is the boundary the rule allows.
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.claim.lease=5m",
                    "courtregister.claim.processing-deadline=4m",
                    "courtregister.servicebus.max-auto-lock-renew-duration=PT4M30S").run(context ->
                            assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("exactly one credential source")
    class CredentialSelection {

        @Test
        void a_connection_string_alone_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void a_namespace_alone_should_start() {
            runner.withPropertyValues(NAMESPACE_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void both_credential_sources_should_fail_startup_with_a_clear_message() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, NAMESPACE_PROPERTY)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.servicebus.connection-string")
                                .hasMessageContaining("courtregister.servicebus.namespace")
                                .hasMessageContaining("exactly one");
                    });
        }

        @Test
        void neither_credential_source_should_fail_startup_with_a_clear_message() {
            runner.run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasMessageContaining("courtregister.servicebus.connection-string")
                        .hasMessageContaining("courtregister.servicebus.namespace")
                        .hasMessageContaining("exactly one");
            });
        }

        @Test
        void a_blank_connection_string_should_count_as_unset() {
            // A deployed environment overrides the local default with an empty value rather than
            // deleting the key, so blank must mean absent or every deployment would fail as
            // "both set".
            runner.withPropertyValues("courtregister.servicebus.connection-string=",
                    NAMESPACE_PROPERTY).run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("the live payload source needs an identity to fall back with")
    class PayloadIdentity {

        /**
         * Without it the fallback cannot be used at all: every cold-cache request is abandoned,
         * redelivered and finally dead-lettered by a pod that reports itself perfectly healthy
         * throughout. A mount that did not arrive is a deployment fault, and a deployment fault
         * belongs at startup.
         */
        @Test
        void live_mode_without_an_identity_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.results.system-user-id=",
                    "courtregister.payload.mode=LIVE").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.results.system-user-id")
                                .hasMessageContaining("courtregister.payload.mode");
                    });
        }

        @Test
        void live_mode_with_an_identity_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.payload.mode=LIVE")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * The identity is the live source's requirement and nobody else's. A local run on the stub
         * fetches nothing and so authorises with nobody.
         */
        @Test
        void stub_mode_without_an_identity_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.results.system-user-id=",
                    "courtregister.payload.mode=STUB")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("the stub payload source is not reachable where the service is deployed")
    class StubReachability {

        /**
         * Constitution Principle V: a stub must not be reachable in a production profile. The
         * discriminator is the credential source already used for exactly this distinction — a
         * namespace means workload identity, which means a deployed pod. Such a pod running the stub
         * would settle every message and produce no register at all, which is the failure this
         * service exists to end.
         */
        @Test
        void stub_mode_on_the_deployed_credential_source_should_fail_startup() {
            runner.withPropertyValues(NAMESPACE_PROPERTY,
                    "courtregister.payload.mode=STUB").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.payload.mode")
                                .hasMessageContaining("courtregister.servicebus.namespace");
                    });
        }

        @Test
        void stub_mode_on_the_local_credential_source_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.payload.mode=STUB")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void live_mode_on_the_deployed_credential_source_should_start() {
            runner.withPropertyValues(NAMESPACE_PROPERTY,
                    "courtregister.payload.mode=LIVE")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("the live now-subscriptions source must be able to ask reference data")
    class SubscriptionsReachability {

        /**
         * Without an endpoint the client has nowhere to send the query, so every hearing that
         * produced a register is abandoned, redelivered and finally parked — by a pod whose
         * readiness, liveness and queue metrics all say the deployment succeeded.
         */
        @Test
        void live_mode_without_an_endpoint_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.referencedata.base-url=").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.referencedata.base-url")
                                .hasMessageContaining("courtregister.referencedata.mode");
                    });
        }

        /**
         * {@code CJSCPPUID} is part of the reference-data query's own contract and its
         * access-control rules authorise on it, so an anonymous query is a refused query — every
         * time, for ever.
         */
        @Test
        void live_mode_without_an_identity_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.referencedata.system-user-id=").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.referencedata.system-user-id")
                                .hasMessageContaining("courtregister.referencedata.mode");
                    });
        }

        @Test
        void live_mode_with_an_endpoint_and_an_identity_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.referencedata.mode=LIVE")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /** Both are the live source's requirement and nobody else's; the stub asks nobody. */
        @Test
        void stub_mode_without_an_endpoint_or_an_identity_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.payload.mode=STUB",
                    "courtregister.referencedata.mode=STUB",
                    "courtregister.referencedata.base-url=",
                    "courtregister.referencedata.system-user-id=")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * The stub answers "nobody is subscribed", which is a legitimate business outcome and this
         * flow's commonest one. Given about a real hearing it is indistinguishable from working:
         * every run completes {@code no-subscriptions}, and the log, the metrics and the queue all
         * agree the service is doing its job. The stub cannot refuse its way out of that — the read
         * happens before the transformation, so refusing would only trade a silent completion for a
         * queue that never drains — so the configuration is what is refused, at startup.
         */
        @Test
        void stub_mode_beside_a_live_payload_source_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.payload.mode=LIVE",
                    "courtregister.referencedata.mode=STUB").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.referencedata.mode")
                                .hasMessageContaining("courtregister.payload.mode");
                    });
        }

        /**
         * Constitution Principle V, the same rule the payload stub is held to: a deployed pod
         * running this one asks reference data nothing, so every hearing it reads completes
         * addressed to nobody and no register is ever sent.
         */
        @Test
        void stub_mode_on_the_deployed_credential_source_should_fail_startup() {
            runner.withPropertyValues(NAMESPACE_PROPERTY,
                    "courtregister.referencedata.mode=STUB").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.referencedata.mode")
                                .hasMessageContaining("courtregister.servicebus.namespace");
                    });
        }

        @Test
        void a_source_with_no_attempts_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.referencedata.max-attempts=0").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.referencedata.max-attempts");
                    });
        }

        @Test
        void a_negative_wait_between_attempts_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.referencedata.retry-interval=-1s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.referencedata.retry-interval");
                    });
        }

        @Test
        void a_timeout_that_never_expires_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.referencedata.read-timeout=0s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.referencedata.read-timeout");
                    });
        }

        /**
         * Every timeout here is positive and every attempt count is at least one, and the read can
         * still outlast the run: ten attempts against a minute-long read is over ten minutes of
         * waiting that startup would otherwise accept. The claim becomes reclaimable long before
         * that, so another delivery starts processing the request while this runner is still blocked
         * on the socket — the outcome the processing deadline exists to prevent, reached by a
         * configuration each individual rule calls valid.
         */
        @Test
        void a_read_that_can_outlast_the_processing_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.referencedata.max-attempts=10",
                    "courtregister.referencedata.read-timeout=1m").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.referencedata")
                                .hasMessageContaining("courtregister.claim.processing-deadline");
                    });
        }

        /**
         * The waits between attempts count too: they are spent inside the same run as the reads.
         * The shipped three attempts of 5s + 10s is 45s, comfortably inside the 4m deadline — until
         * the two waits between them are lengthened, which no other rule looks at.
         */
        @Test
        void the_waits_between_attempts_should_count_towards_the_deadline() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    // 45s of reads, and two 70s waits between the three attempts, is 185s — inside
                    // the deadline on its own, and not inside the run it shares.
                    "courtregister.referencedata.retry-interval=70s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.referencedata")
                                .hasMessageContaining("courtregister.claim.processing-deadline");
                    });
        }

        /**
         * The same reading of the bound the payload rule takes: a read that fills the deadline
         * exactly leaves the rest of the run nothing, because the run only tests the deadline once
         * the read has returned.
         */
        @Test
        void a_read_that_exactly_fills_the_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.referencedata.max-attempts=2",
                    "courtregister.referencedata.retry-interval=10s",
                    // Two attempts of 5s + 110s, with a 10s wait between them, is the 4m deadline
                    // to the second.
                    "courtregister.referencedata.read-timeout=110s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.referencedata")
                                .hasMessageContaining("courtregister.claim.processing-deadline");
                    });
        }

        /**
         * A read that finishes half a second inside the deadline satisfies the rule above and is
         * refused anyway, by the budget the whole run shares: a step that leaves nothing for the
         * payload fetch, the submission and the guard's writes has not finished inside the run, it
         * has finished inside the deadline and taken the rest of the run's time with it. Which is
         * why there are two rules and not one.
         */
        @Test
        void a_read_that_leaves_the_rest_of_the_run_nothing_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.referencedata.max-attempts=2",
                    "courtregister.referencedata.retry-interval=10s",
                    "courtregister.referencedata.read-timeout=PT109.5S").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.referencedata")
                                .hasMessageContaining("courtregister.claim.processing-deadline");
                    });
        }

        /**
         * The timing rule belongs to the live adapter, which STUB does not build — the same reason
         * the endpoint and the identity are not asked of a stub run.
         */
        @Test
        void stub_mode_should_not_be_held_to_the_live_read_timings() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.payload.mode=STUB",
                    "courtregister.referencedata.mode=STUB",
                    "courtregister.referencedata.max-attempts=10",
                    "courtregister.referencedata.read-timeout=1m")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("the payload settings must describe a source that can answer")
    class PayloadReachability {

        @Test
        void a_fallback_with_no_attempts_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.payload.fallback.max-attempts=0").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(
                                        "courtregister.payload.fallback.max-attempts");
                    });
        }

        @Test
        void a_single_attempt_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.payload.fallback.max-attempts=1")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void a_negative_retry_interval_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.payload.fallback.retry-interval=-1s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(
                                        "courtregister.payload.fallback.retry-interval");
                    });
        }

        @Test
        void a_timeout_that_never_expires_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.payload.redis.command-timeout=0s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(
                                        "courtregister.payload.redis.command-timeout");
                    });
        }

        @Test
        void a_cache_with_no_address_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.payload.redis.host=").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.payload.redis.host");
                    });
        }

        /**
         * {@code INT_} is the prefix the producer writes this flow's payload under, and an empty one
         * reads a key nobody writes.
         */
        @Test
        void a_cache_with_no_key_prefix_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.payload.redis.key-prefix=").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.payload.redis.key-prefix");
                    });
        }

        /**
         * The fetch happens inside the run, and the run must stop before its claim can be reclaimed.
         * A fallback whose own worst case outlasts the processing deadline therefore guarantees the
         * thing the deadline exists to prevent: a runner still waiting on a socket while another
         * runner takes its request.
         */
        @Test
        void a_fallback_that_can_outlast_the_processing_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.claim.processing-deadline=1m",
                    "courtregister.payload.fallback.read-timeout=30s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.payload.fallback")
                                .hasMessageContaining("courtregister.claim.processing-deadline");
                    });
        }

        @Test
        void a_fallback_that_finishes_inside_the_processing_deadline_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * The fallback is not the whole fetch. Two cache reads precede it — the dated key and the
         * legacy undated twin — and each of them can spend its connect and command timeouts before
         * the query side is asked at all. A budget that counts only the HTTP half licences a fetch
         * that overruns the deadline by everything the cache cost.
         */
        @Test
        void a_fetch_whose_cache_reads_push_it_past_the_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.claim.processing-deadline=2m",
                    "courtregister.payload.redis.connect-timeout=5s",
                    "courtregister.payload.redis.command-timeout=5s",
                    "courtregister.payload.fallback.max-attempts=1",
                    "courtregister.payload.fallback.connect-timeout=5s",
                    // 105s of query side alone fits inside 120s; the 20s of cache reads in front of
                    // it does not.
                    "courtregister.payload.fallback.read-timeout=100s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.payload")
                                .hasMessageContaining("courtregister.claim.processing-deadline");
                    });
        }

        /**
         * A fetch that fills the deadline exactly leaves the rest of the run nothing, and the run
         * only checks the deadline once the fetch has returned.
         */
        @Test
        void a_fetch_that_exactly_fills_the_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.claim.processing-deadline=2m",
                    "courtregister.payload.redis.connect-timeout=5s",
                    "courtregister.payload.redis.command-timeout=5s",
                    "courtregister.payload.fallback.max-attempts=1",
                    "courtregister.payload.fallback.connect-timeout=5s",
                    // 20s of cache reads plus 100s of query side is the deadline to the second.
                    "courtregister.payload.fallback.read-timeout=95s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.payload")
                                .hasMessageContaining("courtregister.claim.processing-deadline");
                    });
        }

        /**
         * The same relationship the reference-data cases show, on the fetch: 119s of payload fetch
         * is one second inside a two-minute deadline by the per-step rule, and leaves the
         * now-subscriptions read, the submission and the guard's writes one second between them.
         * The run's shared budget is what refuses it.
         */
        @Test
        void a_fetch_that_leaves_the_rest_of_the_run_nothing_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.claim.processing-deadline=2m",
                    "courtregister.payload.redis.connect-timeout=5s",
                    "courtregister.payload.redis.command-timeout=5s",
                    "courtregister.payload.fallback.max-attempts=1",
                    "courtregister.payload.fallback.connect-timeout=5s",
                    "courtregister.payload.fallback.read-timeout=94s",
                    "courtregister.progression.max-attempts=1").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.payload")
                                .hasMessageContaining("courtregister.claim.processing-deadline");
                    });
        }

        /**
         * The cache and the query side belong to the live source, and STUB selects neither bean.
         * Holding a local stub run to settings nothing will read fails a run that is configured
         * exactly as it means to be.
         */
        @Test
        void stub_mode_should_not_be_held_to_the_live_payload_settings() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.payload.mode=STUB",
                    "courtregister.payload.redis.host=",
                    "courtregister.payload.fallback.max-attempts=0")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    /**
     * The submission must be able to make the call the whole service exists to make.
     *
     * <p>These settings fail in the same quiet way the timing rules do. A {@code max-attempts} of
     * zero attempts no POST at all: the loop that would send the register never runs, every hearing
     * comes back transient, and the queue fills with deliveries that were never even tried — the
     * silent non-delivery this service exists to remove, wearing a retry policy's clothes. A
     * negative wait reaches {@code Thread.sleep} and throws from inside the retry, and a ceiling
     * below the first wait is a bound that shortens the very back-off it is meant to bound.
     *
     * <p>The endpoint and the identity are asked of a configuration that can actually reach the
     * POST — a live payload source. A deployed pod is always one of those, because the stub payload
     * source is itself refused on the deployed credential source, so no deployment escapes the rule
     * through the exemption a local stub run relies on.
     */
    @Nested
    @DisplayName("the submission must be able to post the register")
    class SubmissionPolicy {

        @Test
        void a_live_pipeline_without_a_submission_identity_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.payload.mode=LIVE",
                    "courtregister.progression.system-user-id=").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.progression.system-user-id")
                                .hasMessageContaining("courtregister.payload.mode");
                    });
        }

        @Test
        void a_live_pipeline_without_a_submission_endpoint_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.payload.mode=LIVE",
                    "courtregister.progression.base-url=").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.progression.base-url")
                                .hasMessageContaining("courtregister.payload.mode");
                    });
        }

        /** A local stub run fetches no hearing, so it reaches no POST and needs no identity. */
        @Test
        void a_stubbed_pipeline_without_an_endpoint_or_an_identity_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.payload.mode=STUB",
                    "courtregister.progression.base-url=",
                    "courtregister.progression.system-user-id=")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void no_attempts_at_all_should_fail_startup_rather_than_post_nothing() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.progression.max-attempts=0").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.progression.max-attempts");
                    });
        }

        @Test
        void a_negative_attempt_count_should_fail_startup_the_same_way() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.progression.max-attempts=-1").run(context -> {
                        assertThat(context).hasFailed();
                        // "The same way" is the point of the case, so it is asserted rather than
                        // asserted-about: the refusal names the setting and the floor it is under,
                        // exactly as the zero case's does.
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.progression.max-attempts")
                                .hasMessageContaining("-1")
                                .hasMessageContaining("must be at least 1");
                    });
        }

        @Test
        void a_single_attempt_should_start_because_no_retry_is_a_policy_too() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.progression.max-attempts=1").run(context ->
                            assertThat(context).hasNotFailed());
        }

        @Test
        void a_negative_initial_backoff_should_fail_startup_rather_than_throw_mid_retry() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.progression.initial-backoff=-1s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.progression.initial-backoff");
                    });
        }

        @Test
        void a_ceiling_below_the_first_wait_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.progression.initial-backoff=10s",
                    "courtregister.progression.max-backoff=5s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.progression.max-backoff")
                                .hasMessageContaining("courtregister.progression.initial-backoff");
                    });
        }

        @Test
        void a_timeout_that_never_expires_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.progression.read-timeout=0s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.progression.read-timeout");
                    });
        }

        /**
         * The POST happens inside the same run the payload fetch and the reference-data read happen
         * in, so its own worst case has to fit inside the processing deadline for the same reason
         * theirs do: a runner still waiting on a socket when its claim becomes reclaimable is a
         * second runner processing the same hearing.
         */
        @Test
        void a_submission_that_can_outlast_the_processing_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.progression.max-attempts=10",
                    "courtregister.progression.read-timeout=1m").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.progression")
                                .hasMessageContaining("courtregister.claim.processing-deadline");
                    });
        }

        /**
         * The back-off waits are spent inside the run as surely as the reads are, and nothing else
         * bounds them: four attempts of 5s + 10s is 60s and comfortably inside the 4m deadline until
         * the three waits between them are lengthened.
         */
        @Test
        void the_back_off_waits_should_count_towards_the_deadline() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    // 60s of attempts, and three 90s waits between them, is 330s.
                    "courtregister.progression.initial-backoff=90s",
                    "courtregister.progression.max-backoff=90s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.progression")
                                .hasMessageContaining("courtregister.claim.processing-deadline");
                    });
        }

        /** The same reading of the bound every other timing rule takes: reached is already too far. */
        @Test
        void a_submission_that_exactly_fills_the_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.progression.max-attempts=2",
                    "courtregister.progression.initial-backoff=10s",
                    "courtregister.progression.max-backoff=20s",
                    "courtregister.progression.connect-timeout=5s",
                    // Two attempts of 5s + 110s, with the single 10s wait between them, is the 4m
                    // deadline to the second.
                    "courtregister.progression.read-timeout=110s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.progression")
                                .hasMessageContaining("courtregister.claim.processing-deadline");
                    });
        }

        /**
         * And the same again on the POST. Half a second inside the deadline by the rule above, and
         * refused by the budget it shares with the two reads that precede it.
         */
        @Test
        void a_submission_that_leaves_the_rest_of_the_run_nothing_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.progression.max-attempts=2",
                    "courtregister.progression.initial-backoff=10s",
                    "courtregister.progression.max-backoff=20s",
                    "courtregister.progression.connect-timeout=5s",
                    "courtregister.progression.read-timeout=PT109.5S").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.progression")
                                .hasMessageContaining("courtregister.claim.processing-deadline");
                    });
        }

        @Test
        void the_shipped_defaults_should_satisfy_their_own_rules() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    /**
     * The pre-send contract validation that fix C29 exists to add.
     *
     * <p>It is the difference between a schema-invalid document becoming an explicit, attributable
     * FAILED and becoming a 400 from progression that the legacy pipeline swallowed — the single
     * behaviour that lost whole registers most often. A deployed pod that has it switched off is
     * back in the legacy failure mode with none of the legacy's excuses, so startup refuses it.
     */
    @Nested
    @DisplayName("the outbound contract validator is never off where the service is deployed")
    class OutboundValidation {

        @Test
        void it_should_be_on_unless_something_turns_it_off() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(CourtRegisterProperties.class)
                        .submission().validateOutbound()).isTrue();
            });
        }

        @Test
        void disabling_it_on_the_deployed_credential_source_should_fail_startup() {
            runner.withPropertyValues(NAMESPACE_PROPERTY,
                    "courtregister.submission.validate-outbound=false").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.submission.validate-outbound")
                                .hasMessageContaining("courtregister.servicebus.namespace");
                    });
        }

        /**
         * Local and CI runs may turn it off — a fixture that deliberately sends a shape the vendored
         * schemas reject has to be able to reach the wire to prove what happens next.
         */
        @Test
        void disabling_it_on_the_local_credential_source_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.submission.validate-outbound=false").run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(CourtRegisterProperties.class)
                                .submission().validateOutbound()).isFalse();
                    });
        }

        @Test
        void leaving_it_on_where_the_service_is_deployed_should_start() {
            runner.withPropertyValues(NAMESPACE_PROPERTY,
                    "courtregister.submission.validate-outbound=true")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    /**
     * The whole run's budget, not three budgets that each look reasonable on their own.
     *
     * <p>Every per-step rule above asks the same question of one step: can this one outlast the
     * processing deadline? Answering "no" three times does not answer the question that matters,
     * because the three steps are spent inside <strong>one</strong> run and one claim. A payload
     * fetch, a now-subscriptions read and a submission that each fit comfortably can add up to more
     * than twice the deadline, and the run that spends them is a runner still holding a socket while
     * its claim is reclaimed and a second delivery starts the same request — which, for a flow whose
     * POST progression <em>appends</em> rather than replaces, is a second register for the hearing.
     *
     * <p>The margin is the rest of the run: the guard's admission and outcome writes, and the
     * transformation between the reads. It is fixed rather than configured because nothing about it
     * is an environment's choice.
     */
    @Nested
    @DisplayName("every step together must fit inside the run")
    class CumulativeRunBudget {

        /**
         * The case the per-step rules cannot see. 127s of payload fetch, 107s of now-subscriptions
         * read and 143.5s of submission are each inside a four-minute deadline; together they are
         * 377.5s, and the run that spends them outlives its claim by more than a minute.
         */
        @Test
        void three_steps_that_each_fit_but_do_not_fit_together_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.claim.lease=5m",
                    "courtregister.claim.processing-deadline=4m",
                    "courtregister.payload.fallback.read-timeout=30s",
                    "courtregister.referencedata.read-timeout=30s",
                    "courtregister.progression.read-timeout=30s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.payload")
                                .hasMessageContaining("courtregister.referencedata")
                                .hasMessageContaining("courtregister.progression")
                                .hasMessageContaining("courtregister.claim.processing-deadline");
                    });
        }

        /**
         * The margin counts. These three steps sum to 177.5s, comfortably inside a 200s deadline —
         * and leave the guard's two writes and the whole transformation twenty-two seconds, which is
         * the shape of budget that overruns in production and looks correct in review.
         */
        @Test
        void a_budget_with_no_room_for_the_rest_of_the_run_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.claim.processing-deadline=200s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("courtregister.claim.processing-deadline");
                    });
        }

        /**
         * The shipped numbers have to satisfy the rule they are shipped under, or the service ships
         * unable to start.
         */
        @Test
        void the_shipped_settings_should_leave_room_for_every_step_and_the_margin() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * A step no adapter makes costs the run nothing, which is the same reason the per-step rules
         * are asked only of the source actually selected.
         */
        @Test
        void a_step_the_deployment_stubs_out_should_not_be_counted() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "courtregister.claim.processing-deadline=2m",
                    "courtregister.payload.mode=STUB",
                    "courtregister.referencedata.mode=STUB",
                    "courtregister.payload.fallback.read-timeout=5m",
                    "courtregister.referencedata.read-timeout=5m")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    /**
     * What the shipped {@code application.yaml} actually binds.
     *
     * <p>Asserted against the real file rather than against property values a test invents, because
     * the failure this covers is a documented environment variable that reaches nothing. A comment
     * naming {@code COURT_REGISTER_SYSTEM_USER_ID} is not a binding, and a deployment that sets it
     * and still fails to start with "system-user-id is required" is a deployment nobody can debug
     * from the configuration in front of them.
     */
    @Nested
    @DisplayName("the shipped application.yaml")
    class ShippedConfiguration {

        /**
         * Deliberately not built from {@code runner}: that one carries the identities and endpoints
         * so the cases about something else are not refused startup by the live sources' own rules,
         * and a property value set on the runner outranks the file. A test about what the file binds
         * has to let the file be the only thing that binds it.
         */
        private final ApplicationContextRunner shipped = new ApplicationContextRunner()
                .withUserConfiguration(PropertiesTestConfiguration.class)
                .withInitializer(new ConfigDataApplicationContextInitializer());

        /**
         * The subject here is what the file binds, not which adapters are selected. The shipped file
         * ships both sources LIVE, and a live source without an identity is refused startup by
         * design; selecting the stubs takes that rule out of the way of a test about the binding of
         * a value.
         */
        private final ApplicationContextRunner shippedOnTheStub = shipped
                .withPropertyValues("courtregister.payload.mode=STUB",
                        "courtregister.referencedata.mode=STUB");

        @Test
        void the_queue_it_names_should_be_the_court_register_queue() {
            shippedOnTheStub.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(CourtRegisterProperties.class).servicebus().queueName())
                        .isEqualTo("courtregister.requests");
            });
        }

        @Test
        void the_identity_should_arrive_from_the_environment_variable_the_file_documents() {
            shipped.withSystemProperties(
                    "COURT_REGISTER_SYSTEM_USER_ID=b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        final CourtRegisterProperties properties =
                                context.getBean(CourtRegisterProperties.class);
                        assertThat(properties.progression().systemUserId())
                                .isEqualTo("b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234");
                        assertThat(properties.results().systemUserId())
                                .isEqualTo("b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234");
                    });
        }

        @Test
        void the_submission_endpoint_should_arrive_from_the_variable_the_file_documents() {
            shippedOnTheStub
                    .withSystemProperties("PROGRESSION_BASE_URL=http://progression.internal:8080")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(CourtRegisterProperties.class)
                                .progression().baseUrl())
                                .isEqualTo("http://progression.internal:8080");
                    });
        }

        @Test
        void the_fallback_endpoint_should_arrive_from_the_variable_the_file_documents() {
            shippedOnTheStub.withSystemProperties("RESULTS_BASE_URL=http://results.internal:8080")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(CourtRegisterProperties.class)
                                .results().baseUrl())
                                .isEqualTo("http://results.internal:8080");
                    });
        }

        @Test
        void the_reference_data_endpoint_should_arrive_from_the_variable_the_file_documents() {
            shippedOnTheStub
                    .withSystemProperties("REFERENCEDATA_BASE_URL=http://referencedata.internal:8080")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(CourtRegisterProperties.class)
                                .referencedata().baseUrl())
                                .isEqualTo("http://referencedata.internal:8080");
                    });
        }

        /**
         * One identity, because the function app has one: {@code input.cjscppuid} authorises the
         * payload read and the now-subscriptions read alike. An environment that mounts this
         * service's identity is therefore not asked for a second one.
         */
        @Test
        void the_reference_data_identity_should_fall_back_to_this_services_own() {
            shippedOnTheStub.withSystemProperties(
                    "COURT_REGISTER_SYSTEM_USER_ID=b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(CourtRegisterProperties.class)
                                .referencedata().systemUserId())
                                .isEqualTo("b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234");
                    });
        }

        @Test
        void the_reference_data_identity_should_be_settable_on_its_own() {
            shippedOnTheStub.withSystemProperties(
                    "COURT_REGISTER_SYSTEM_USER_ID=b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234",
                    "REFERENCEDATA_SYSTEM_USER_ID=2c7b1e64-0f4a-4f0e-9b2c-8d1a6f3e5c07")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(CourtRegisterProperties.class)
                                .referencedata().systemUserId())
                                .isEqualTo("2c7b1e64-0f4a-4f0e-9b2c-8d1a6f3e5c07");
                    });
        }

        @Test
        void the_file_should_ship_the_outbound_validator_switched_on() {
            shippedOnTheStub.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(CourtRegisterProperties.class)
                        .submission().validateOutbound()).isTrue();
            });
        }

        @Test
        void an_unset_identity_should_stay_unset_so_a_local_run_borrows_nobodys() {
            shippedOnTheStub.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(CourtRegisterProperties.class)
                        .progression().systemUserId())
                        .as("absent is absent; startup refuses a live pipeline on it, which is the"
                                + " point")
                        .isNullOrEmpty();
            });
        }
    }
}
