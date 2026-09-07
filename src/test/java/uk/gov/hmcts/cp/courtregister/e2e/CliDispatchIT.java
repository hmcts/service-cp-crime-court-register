package uk.gov.hmcts.cp.courtregister.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/**
 * The two commands a runbook opens with, run through the entrypoint of the BUILT image (FR-016).
 *
 * <p><strong>[A] characterisation.</strong> Nothing here drives new behaviour: {@code CliMain},
 * the five commands and {@code docker/startup.sh}'s dispatch all exist, and this suite records what
 * the packaged artefact does with them today. It passes on introduction by construction, so its
 * commit narrative carries the observed result rather than a red assertion.
 *
 * <p><strong>What only the image can prove.</strong> This service exposes no REST API, so support
 * regenerates a date, resends a batch's failed recipients or reads the cutover flag through
 * {@code kubectl exec ... -- ./startup.sh <command>} and through nothing else (constitution
 * Principle III, research §13). That path is half shell and half fat jar: the script has to be
 * executable at the place the runbooks name, the five names have to reach {@code CliMain} out of an
 * archive whose manifest names {@code JarLauncher} instead, and the code the command answered with
 * has to be the code the exec ends on. Every {@code *IT} suite beside this one runs inside the
 * build's own JVM and would stay green if the image were unbuildable, and
 * {@code scripts/container-smoke.sh} - which proves the same path against compose - is not run by
 * {@code ./gradlew build}. So the image is built here, from the repo's own {@code Dockerfile} and
 * the context that Dockerfile declares, and the commands are asked of it by exec, exactly as an
 * operator would ask them of a pod.
 *
 * <p><strong>The two commands, and why these two.</strong> {@code check-flag} reads the one lever
 * and changes nothing, and {@code generate-register --help} prints what the command takes and does
 * not generate anything: between them they cover a command that reaches a downstream and a command
 * that reaches only the parser, and neither can leave a batch, a document or an e-mail behind it.
 *
 * <p><strong>The environment is the compose stack's, and generation is ON.</strong> That is forced
 * rather than chosen: {@code check-flag} asks the flag reader, which exists only where
 * {@code courtregister.generation.enabled} is true, so without it that command answers
 * {@code command-not-wired} and exits 2 and records nothing about the dispatch.
 * {@code generate-register --help} no longer needs it - {@code CliMain.wired} answers what a
 * command takes before it resolves a bean, so an intake-only pod prints the usage as this one does -
 * but the two commands share one container. And with generation on, {@code courtregister.generation.flag-mode=STUB} is refused at startup
 * ({@code PropertiesValidator}: a deployment that means to produce registers tonight cannot produce
 * them against a stand-in), so the flag is read by the REAL App Configuration reader under
 * {@code courtregister.feature.credential=local-test} against a WireMock container serving the
 * committed {@code kv} mapping - the one WireMock is the whole of the extra machinery, and it is
 * the same trade {@code docker-compose.yml} and the container smoke already make. The reading is
 * therefore the deployed reader, the deployed SDK client, the key in the path, the label in the
 * query and the fail-closed parsing, with only the identity swapped for a published pair the stub
 * does not check.
 *
 * <p>Nothing else the generation half names has to answer, and nothing here pretends otherwise:
 * the file-service datasource, the broker and the two downstream hosts are required to be SET at
 * startup and are never connected to by either command - Hikari opens no connection during refresh
 * and the public-event listener container is one of the three things {@code courtregister.cli=true}
 * keeps from starting ({@code CliModeConfig}).
 */
@DisplayName("the operations commands, dispatched by the built image's entrypoint")
class CliDispatchIT {

    /**
     * The tag the suite's image is built under, fixed rather than generated.
     *
     * <p>A stable tag lets Docker reuse the layers of the previous run, which matters because the
     * last of them copies a fat jar; {@code deleteOnExit} is off for the same reason, so a second
     * run of the suite pays for the {@code COPY} and nothing above it.
     */
    private static final String IMAGE_NAME = "courtregister-cli-dispatch-it:test";

    /** The tag {@code docker-compose.yml} pins, so the stub cannot answer differently there. */
    private static final String WIREMOCK_IMAGE = "wiremock/wiremock:3.13.2";

    /** What the App Configuration stand-in is called on the shared network. */
    private static final String WIREMOCK_ALIAS = "wiremock";

    private static final int WIREMOCK_PORT = 8080;

    private static final String WIREMOCK_URL = "http://" + WIREMOCK_ALIAS + ':' + WIREMOCK_PORT;

    /** The label the committed mappings answer under (docker/wiremock/README.md). */
    private static final String STACK_LABEL = "LOCAL";

    /** Long enough for a cold image build and a JVM start, short enough to fail not hang. */
    private static final Duration STARTS_WITHIN = Duration.ofMinutes(5);

    /** The line the application prints once its context is up, whatever readiness then says. */
    private static final String APPLICATION_IS_UP = ".*Started Application in .*";

    /** One network, so the container under test reaches the stub by the name it is given. */
    private static final Network NETWORK = Network.newNetwork();

    /** Azure App Configuration, systemdocgenerator and notificationnotify, on disjoint paths. */
    private static final GenericContainer<?> WIREMOCK = new GenericContainer<>(WIREMOCK_IMAGE)
            .withNetwork(NETWORK)
            .withNetworkAliases(WIREMOCK_ALIAS)
            .withExposedPorts(WIREMOCK_PORT)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(
                            Paths.get("docker", "wiremock", "mappings").toAbsolutePath()),
                    "/home/wiremock/mappings")
            // Verbose deliberately, as compose has it: a mapping that does not match prints the
            // near-miss diff, which is the difference between "the dispatch is broken" and "the
            // Content-Type is wrong".
            .withCommand("--verbose", "--local-response-templating")
            .waitingFor(Wait.forHttp("/__admin/health").forPort(WIREMOCK_PORT)
                    .forStatusCode(200));

    /**
     * The service, as a deployed pod runs it: the image, no arguments, the fall-through.
     *
     * <p>Started as the application rather than as one command per container, because the runbook
     * step this suite is about is an exec into a pod that is already running one. Waiting on the
     * application's own start line and not on readiness: the store is not part of this suite's
     * subject, a pod without one answers 503 and stays up, and what the readiness group does with
     * its dependencies is {@code ReadinessPolicyIT}'s question.
     */
    private static final GenericContainer<?> APP = new GenericContainer<>(builtImage())
            .withNetwork(NETWORK)
            .withEnv(theCommandsEnvironment())
            .waitingFor(Wait.forLogMessage(APPLICATION_IS_UP, 1))
            .withStartupTimeout(STARTS_WITHIN);

    @BeforeAll
    static void buildTheImageAndStartTheStack() {
        WIREMOCK.start();
        APP.start();
    }

    @AfterAll
    static void stopTheStack() {
        APP.stop();
        WIREMOCK.stop();
    }

    @Test
    @DisplayName("check-flag reads the one lever through the deployed reader and exits 0")
    void check_flag_should_print_the_reading_and_exit_zero() throws Exception {
        final Container.ExecResult read = APP.execInContainer("./startup.sh", "check-flag");

        // The exit code is the whole interface between the command and the runbook step that ran
        // it, and 0 is the only one a flag that answered carries: 1 would be a refusal and 2 a
        // flag nobody could read.
        assertThat(read.getExitCode()).isZero();
        // The line a runbook step greps for, on stdout and on its own.
        assertThat(read.getStdout().lines()).contains("flag=ON");
        // And it was reached by dispatch out of the fat jar rather than by a second application
        // starting: the notice is the script's, and the script writes it to stderr alone so that
        // what the command printed is not a stream an operator has to filter.
        assertThat(read.getStderr()).contains("Running the check-flag command from /app/");
    }

    @Test
    @DisplayName("generate-register --help prints what the command takes and exits 0")
    void generate_register_help_should_print_the_usage_line_and_exit_zero() throws Exception {
        final Container.ExecResult asked =
                APP.execInContainer("./startup.sh", "generate-register", "--help");

        // 0 and not 1: asking what a command takes is a thing the command did, not a refusal, and a
        // runbook that read the two the same way would retry a help text.
        assertThat(asked.getExitCode()).isZero();
        assertThat(asked.getStdout().lines()).contains(
                "usage: generate-register --date D [--court-house H] [--batch B] [--ignore-flag]"
                        + " [--recorded-before T]");
        assertThat(asked.getStderr())
                .contains("Running the generate-register command from /app/");
    }

    /**
     * The image, built from the repo's own Dockerfile over exactly the context it copies from.
     *
     * <p>The Dockerfile is transferred rather than pointed at, so the build context is the four
     * things it names - itself, {@code docker/}, the packaged jar and the agent config - and not
     * the working tree, which carries {@code .git} and every build output beside them.
     *
     * @return the lazily built image, resolved when the container starts
     */
    private static ImageFromDockerfile builtImage() {
        final Path jar = packagedJar();
        return new ImageFromDockerfile(IMAGE_NAME, false)
                .withFileFromPath("Dockerfile", Paths.get("Dockerfile").toAbsolutePath())
                .withFileFromPath("docker", Paths.get("docker").toAbsolutePath())
                .withFileFromPath("lib/applicationinsights.json",
                        Paths.get("lib", "applicationinsights.json").toAbsolutePath())
                .withFileFromPath("build/libs/" + jar.getFileName(), jar);
    }

    /**
     * The fat jar the image copies, which {@code test} is made to depend on {@code bootJar} for.
     *
     * <p>An exception and not an assumption. A skipped acceptance test records nothing, and the
     * thing it would have recorded - that the packaged artefact dispatches its own commands - is
     * exactly what is unobservable everywhere else in this build.
     *
     * @return the packaged application, {@code -plain} excluded as {@code startup.sh} excludes it
     */
    private static Path packagedJar() {
        final Path libs = Paths.get("build", "libs").toAbsolutePath();
        try (Stream<Path> built = Files.list(libs)) {
            return built.filter(candidate -> candidate.getFileName().toString().endsWith(".jar"))
                    .filter(candidate -> !candidate.getFileName().toString().contains("plain"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no packaged jar in " + libs + " - run ./gradlew bootJar"));
        } catch (IOException notThere) {
            throw new UncheckedIOException(
                    "no " + libs + " to build the image from - run ./gradlew bootJar", notThere);
        }
    }

    /**
     * What the compose stack gives the packaged image, which is what a command needs to start.
     *
     * <p>Generation on, the two stubbed sources off, and every setting turning generation on makes
     * required. The values that are hosts point at the one WireMock; the values that are only
     * required to be present are local-only dummies and are named as such, because neither command
     * connects to any of them.
     *
     * @return the container's environment, by the variables the deployment sets
     */
    private static Map<String, String> theCommandsEnvironment() {
        return Map.ofEntries(
                // The intake half's two stand-ins: local compose has neither results nor reference
                // data to call, and neither command asks either of them for anything.
                Map.entry("COURTREGISTER_PAYLOAD_MODE", "STUB"),
                Map.entry("COURTREGISTER_REFERENCEDATA_MODE", "STUB"),
                // The downstream half, on, which is what makes generate-register a wired command.
                Map.entry("COURTREGISTER_GENERATION_ENABLED", "true"),
                // The REAL reader on the identity the stub does not check. Startup refuses this
                // value against a .azconfig.io store or on a pod with a Service Bus namespace.
                Map.entry("COURTREGISTER_FEATURE_CREDENTIAL", "local-test"),
                Map.entry("APPCONFIG_ENDPOINT", WIREMOCK_URL),
                Map.entry("STACK_LABEL", STACK_LABEL),
                // Required to be set, never connected to: the payload is written into the file
                // service by a run, and neither of these commands starts one.
                Map.entry("FILESERVICE_DATASOURCE_URL",
                        "jdbc:postgresql://fileservice-postgres:5432/fileservice"),
                Map.entry("FILESERVICE_DATASOURCE_USERNAME", "fileservice"),
                Map.entry("FILESERVICE_DATASOURCE_PASSWORD", "fileservice"),
                // Hosts only: each client appends its own contract path.
                Map.entry("SYSTEMDOCGENERATOR_BASE_URL", WIREMOCK_URL),
                Map.entry("NOTIFICATIONNOTIFY_BASE_URL", WIREMOCK_URL),
                // Local-only dummy, and a UUID because startup checks the shape (fix P9). Never a
                // real template id.
                Map.entry("CR_EMAIL_TEMPLATE_ID", "11111111-1111-1111-1111-111111111111"),
                // Required whenever completion is event-driven, and it is. Nothing subscribes here:
                // the listener container is one of the three things courtregister.cli=true stops.
                Map.entry("ARTEMIS_BROKER_URL", "tcp://artemis:61616"));
    }
}
