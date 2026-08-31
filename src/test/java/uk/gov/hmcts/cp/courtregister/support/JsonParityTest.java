package uk.gov.hmcts.cp.courtregister.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The parity comparator's own tests.
 *
 * <p>This comparator is what decides whether the golden-parity and differential suites pass, so a
 * fault in it is invisible in exactly the way that matters: a comparator that quietly matched
 * everything would leave a corpus of recorded legacy behaviour proving nothing. Both directions are
 * therefore pinned — what it must accept, and what it must reject.
 */
@DisplayName("JsonParity")
class JsonParityTest {

    private final ObjectMapper mapper = ContractJson.mapper();

    @Nested
    @DisplayName("accepts")
    class Accepts {

        @Test
        @DisplayName("objects whose fields are in a different order")
        void objects_whose_fields_are_in_a_different_order() {
            assertMatches("{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1}");
        }

        @Test
        @DisplayName("numbers that differ only in representation")
        void numbers_that_differ_only_in_representation() {
            // The service reads floating point as BigDecimal so money stays exact; the golden was
            // written by a runtime with one numeric type. These are the same number.
            assertMatches("{\"amount\":1}", "{\"amount\":1.0}");
            assertMatches("{\"amount\":1.50}", "{\"amount\":1.5}");
        }

        @Test
        @DisplayName("nested trees that are equal throughout")
        void nested_trees_that_are_equal_throughout() {
            assertMatches(
                    "[{\"x\":{\"y\":[1,2,{\"z\":null}]}}]",
                    "[{\"x\":{\"y\":[1,2,{\"z\":null}]}}]");
        }
    }

    @Nested
    @DisplayName("rejects")
    class Rejects {

        @Test
        @DisplayName("arrays whose elements are in a different order")
        void arrays_whose_elements_are_in_a_different_order() {
            // Order is meaning in this tree — which defendant is first, which result is first.
            assertDiffers("[1,2]", "[2,1]", "/0");
        }

        @Test
        @DisplayName("a field the port emits that the legacy never did")
        void a_field_the_port_emits_that_the_legacy_never_did() {
            assertDiffers("{\"a\":1}", "{\"a\":1,\"b\":2}", "unexpected field");
        }

        @Test
        @DisplayName("a field the legacy emits that the port dropped")
        void a_field_the_legacy_emits_that_the_port_dropped() {
            assertDiffers("{\"a\":1,\"b\":2}", "{\"a\":1}", "missing field");
        }

        @Test
        @DisplayName("an array of the wrong length")
        void an_array_of_the_wrong_length() {
            assertDiffers("[1,2,3]", "[1,2]", "element(s)");
        }

        @Test
        @DisplayName("a value that changed type")
        void a_value_that_changed_type() {
            assertDiffers("{\"a\":\"1\"}", "{\"a\":1}", "/a");
        }

        @Test
        @DisplayName("null where a value was expected")
        void null_where_a_value_was_expected() {
            assertDiffers("{\"a\":1}", "{\"a\":null}", "/a");
        }

        @Test
        @DisplayName("a difference buried deep in the tree")
        void a_difference_buried_deep_in_the_tree() {
            assertDiffers(
                    "[{\"x\":{\"y\":[1,2,{\"z\":\"kept\"}]}}]",
                    "[{\"x\":{\"y\":[1,2,{\"z\":\"lost\"}]}}]",
                    "/0/x/y/2/z");
        }
    }

    /**
     * The register as it stands: empty, and therefore invisible.
     *
     * <p>This port fixes thirty-one catalogued defects, so components whose rendering a fix changes
     * will eventually be reconciled against the oracle by derivation rather than by equality. None
     * is registered yet — entries arrive with the differential audit (T074), which is where a fix
     * first meets a recorded oracle value — and the consequence has to be pinned, because "the
     * comparator has a register" and "the register is doing something" are two different claims and
     * only the second one could be mistaken for the first.
     */
    @Nested
    @DisplayName("the defect-fix register, empty")
    class EmptyRegister {

        @Test
        @DisplayName("registers nothing, so every component is compared by equality")
        void registers_nothing_so_every_component_is_compared_by_equality() {
            assertThat(RegisteredDefectFixes.forProperty("registerDate")).isNull();
            assertThat(RegisteredDefectFixes.forProperty("fileName")).isNull();
            assertThat(RegisteredDefectFixes.forProperty("verdictCode")).isNull();
        }

        @Test
        @DisplayName("compares a component a fix will one day cover exactly as it compares any other")
        void compares_a_future_component_exactly_as_it_compares_any_other() {
            assertMatches(
                    "{\"registerDate\":\"2020-06-01T10:00:00Z\"}",
                    "{\"registerDate\":\"2020-06-01T10:00:00Z\"}");
            assertDiffers(
                    "{\"registerDate\":\"2020-06-01T11:00:00Z\"}",
                    "{\"registerDate\":\"2020-06-01T10:00:00Z\"}",
                    "/registerDate");
        }
    }

    /**
     * The derivation machinery, driven through a register of this test's own.
     *
     * <p><strong>The lookup below registers no C-number and asserts no fixed behaviour.</strong> It
     * exists because {@link RegisteredDefectFixes} is legitimately empty until a fix earns a row
     * there, and a mechanism nobody can reach is a mechanism nobody has tested — the first real
     * entry would otherwise be written against machinery whose three outcomes had never run. What is
     * pinned here is that the register is not an exemption: a registered component is still
     * compared, just against a derived value, and both of the ways that can fail are reported.
     */
    @Nested
    @DisplayName("the derivation mechanism a registered fix plugs into")
    class DerivationMechanism {

        /** Uppercase, chosen because it is obviously not a fix and obviously not the identity. */
        private final Function<String, RegisteredDefectFixes.Fix> register = lookup(Map.of(
                "component",
                new RegisteredDefectFixes.Fix(
                        "C0 (illustrative — no such row exists)",
                        oracleValue -> oracleValue.startsWith("legacy-")
                                ? List.of(oracleValue.toUpperCase(Locale.ROOT))
                                : List.of())));

        @Test
        @DisplayName("accepts the rendering derived from the golden's own value")
        void accepts_the_rendering_derived_from_the_goldens_own_value() {
            assertMatchesUnder(register,
                    "{\"component\":\"legacy-value\"}", "{\"component\":\"LEGACY-VALUE\"}");
        }

        @Test
        @DisplayName("rejects the legacy rendering itself, so a silent revert cannot pass")
        void rejects_the_legacy_rendering_itself() {
            assertDiffersUnder(register,
                    "{\"component\":\"legacy-value\"}", "{\"component\":\"legacy-value\"}",
                    "/component");
        }

        @Test
        @DisplayName("rejects any other rendering, so the register is not an exemption")
        void rejects_any_other_rendering() {
            // THE CASE THAT MATTERS. A comparator that simply skipped a registered component would
            // pass here, and the next mistake in that component would ship inside a green suite.
            assertDiffersUnder(register,
                    "{\"component\":\"legacy-value\"}", "{\"component\":\"something-else\"}",
                    "/component");
        }

        @Test
        @DisplayName("reports a golden it cannot derive from rather than passing it")
        void reports_a_golden_it_cannot_derive_from() {
            // The recordings are never edited, so a value in a shape the fix does not describe means
            // the fix no longer covers the component — which must be reported, not waved through on
            // the grounds that the component is "registered".
            assertDiffersUnder(register,
                    "{\"component\":\"unrecognised\"}", "{\"component\":\"unrecognised\"}",
                    "cannot derive");
        }

        @Test
        @DisplayName("rejects the component going missing, exactly as equality would")
        void rejects_the_component_going_missing() {
            assertDiffersUnder(register,
                    "{\"component\":\"legacy-value\"}", "{}", "missing field");
        }

        @Test
        @DisplayName("names the fix in the failure, so nobody has to guess why it differs")
        void names_the_fix_in_the_failure() {
            assertDiffersUnder(register,
                    "{\"component\":\"legacy-value\"}", "{\"component\":\"something-else\"}",
                    "C0 (illustrative — no such row exists)");
        }
    }

    /**
     * A register built from a map, in the shape {@link JsonParity} asks for.
     *
     * @param fixes the fixes by property name
     * @return the lookup
     */
    private static Function<String, RegisteredDefectFixes.Fix> lookup(
            final Map<String, RegisteredDefectFixes.Fix> fixes) {
        return fixes::get;
    }

    /**
     * Asserts the comparator accepts two trees as equal, against the real register.
     *
     * @param expected the golden tree as JSON text
     * @param actual   the ported tree as JSON text
     */
    private void assertMatches(final String expected, final String actual) {
        assertThatCode(() -> JsonParity.assertMatches(tree(expected), tree(actual), "case"))
                .doesNotThrowAnyException();
    }

    /**
     * Asserts the comparator rejects two trees, naming where, against the real register.
     *
     * @param expected the golden tree as JSON text
     * @param actual   the ported tree as JSON text
     * @param mention  text the failure message must contain, so the report is usable
     */
    private void assertDiffers(
            final String expected, final String actual, final String mention) {
        assertThatThrownBy(() -> JsonParity.assertMatches(tree(expected), tree(actual), "case"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("case")
                .hasMessageContaining(mention);
    }

    /**
     * Asserts the comparator accepts two trees as equal, against a supplied register.
     *
     * @param register the register to compare under
     * @param expected the golden tree as JSON text
     * @param actual   the ported tree as JSON text
     */
    private void assertMatchesUnder(
            final Function<String, RegisteredDefectFixes.Fix> register,
            final String expected,
            final String actual) {
        assertThatCode(() ->
                JsonParity.assertMatches(tree(expected), tree(actual), "case", register))
                .doesNotThrowAnyException();
    }

    /**
     * Asserts the comparator rejects two trees, naming where, against a supplied register.
     *
     * @param register the register to compare under
     * @param expected the golden tree as JSON text
     * @param actual   the ported tree as JSON text
     * @param mention  text the failure message must contain, so the report is usable
     */
    private void assertDiffersUnder(
            final Function<String, RegisteredDefectFixes.Fix> register,
            final String expected,
            final String actual,
            final String mention) {
        assertThatThrownBy(() ->
                JsonParity.assertMatches(tree(expected), tree(actual), "case", register))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("case")
                .hasMessageContaining(mention);
    }

    /**
     * Parses JSON text.
     *
     * @param json the text
     * @return the tree
     */
    private JsonNode tree(final String json) {
        return mapper.readTree(json);
    }
}
