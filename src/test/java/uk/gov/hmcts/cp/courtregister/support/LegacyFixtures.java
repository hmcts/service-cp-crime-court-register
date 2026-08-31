package uk.gov.hmcts.cp.courtregister.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.config.JacksonConfig;

/**
 * Reads the legacy {@code NowsHelper} Jest fixtures the shared-kernel twins run against.
 *
 * <p>The files under {@code src/test/resources/fixtures/nowshelper/} are byte-identical copies of
 * {@code $DF/NowsHelper/service/test/}, verified with {@code diff} at copy time, as constitution
 * Principle I requires — the legacy is the oracle for everything the fix register does not
 * catalogue, and a fixture edited on the way in is an oracle that agrees with the port by
 * construction.
 *
 * <p><strong>Repaired fixtures are named, not edited.</strong> Where the design proves a fixture
 * stale or vacuous the repair lands as a <em>new</em> file beside the original, whose name says what
 * was changed and whose provenance note says why. The original stays exactly as the legacy suite
 * has it, so a twin and its repaired counterpart can be read side by side:
 *
 * <ul>
 *   <li>{@code application-case-level-prosecuting-applicant.json} —
 *       {@code application-case-level.json} with the applicant's {@code masterDefendant} replaced by
 *       the {@code prosecutingAuthority} block of {@code application-court-order-level.json}, byte
 *       for byte, and nothing else altered. The original's applicant is another master defendant, so
 *       under the C22 fix the application is not eligible and the fixture gathers no defendant at
 *       all — which is the fix, and which leaves the register-configuration result-level split it
 *       was written to pin with nothing to assert against. This copy pins that split.</li>
 * </ul>
 *
 * <p>They are read through the service's own contract mapper, so a fixture reaches the port exactly
 * as a fetched hearing payload would — {@code USE_BIG_DECIMAL_FOR_FLOATS} included. Reading them
 * with a default mapper would compare the port against a tree the port never sees.
 */
public final class LegacyFixtures {

    private static final String ROOT = "/fixtures/nowshelper/";

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private LegacyFixtures() {
    }

    /**
     * Reads one legacy fixture.
     *
     * @param name the file name, as it is named in the legacy suite
     * @return the parsed tree
     */
    public static JsonNode read(final String name) {
        final String resource = ROOT + name;
        try (InputStream stream = LegacyFixtures.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            return MAPPER.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException cannotRead) {
            throw new IllegalStateException("could not read " + resource, cannotRead);
        }
    }
}
