package uk.gov.hmcts.cp.courtregister.support;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The components the golden comparator checks by derivation instead of by equality.
 *
 * <p><strong>Why this exists at all.</strong> Everything recorded under
 * {@code src/test/resources/differential/recorded/} is a recording of the real Node function app and
 * is the oracle's truth. It is never regenerated and never edited — a golden file somebody adjusted
 * to agree with the port has stopped being evidence. This port, though, is deliberately not a
 * bug-for-bug one: thirty-one catalogued defects are fixed, so for those components the recording
 * carries the legacy rendering and the port is required to write something else, and something has
 * to reconcile the two.
 *
 * <p><strong>The mechanism, and what it is not.</strong> It is not an exclusion. Excluding a field
 * would make every golden assertion stop looking at it, and the next mistake in that component — a
 * hard-coded offset, a re-rendered instant, a dropped seconds field — would sail through a green
 * suite. Instead each registered component carries a <em>derivation</em>: given the value the oracle
 * recorded, it computes the value the port is now required to produce, and the comparator demands
 * exactly that. The check is as strict as equality was; only the expected string moved.
 *
 * <p>Consequently the register can fail in a third way, and does so loudly: an oracle value the
 * derivation does not describe is reported as a difference rather than waved through, because a
 * field being "registered" is not a licence to stop comparing it.
 *
 * <p><strong>The register is empty, and stays empty until a fix earns a row.</strong> An entry here
 * is the executable half of a {@code doc/DEFECT-FIXES.md} row, so nothing belongs in this class that
 * is not a C-number in that register, and every entry quotes its C-number into the failure message
 * so a reader of a red build is one grep from the reasoning and the sign-off. Entries arrive with
 * the differential audit (T074), where the recorded legacy corpus first meets the ported pipeline
 * and a fix that changes a rendering first has an oracle value to be reconciled against. Seeding it
 * with guesses now would put fixed behaviour in a support class instead of in a pinning test.
 *
 * <p>The derivation machinery itself is nevertheless tested from the day it lands — see
 * {@code JsonParityTest}, which drives {@link JsonParity} with a lookup of its own so that the first
 * real entry arrives on machinery that is already known to work.
 */
public final class RegisteredDefectFixes {

    /**
     * The register, by the property name the component reaches the wire under.
     *
     * <p>Matched by property name rather than by JSON pointer because the comparator is handed
     * fragments as well as whole documents, and the same component sits at a different depth in
     * each. A component that occurs at more than one place in the contract, and is fixed at only one
     * of them, therefore cannot be registered this way — it needs a pointer-aware entry, and that
     * decision belongs with the fix that first needs it rather than here.
     */
    private static final Map<String, Fix> REGISTER = Map.of();

    private RegisteredDefectFixes() {
    }

    /**
     * The defect fix registered against a property name, if any.
     *
     * @param propertyName the property name as it reaches the wire
     * @return the fix, or {@code null} when the property is compared by equality
     */
    public static Fix forProperty(final String propertyName) {
        return REGISTER.get(propertyName);
    }

    /**
     * One registered component and the derivation that reconciles the port with the oracle.
     *
     * @param reference  the {@code doc/DEFECT-FIXES.md} C-number, quoted into every failure message
     * @param derivation the renderings the port may produce, given the value the oracle recorded;
     *                   empty when the oracle's value is not one this fix describes
     */
    public record Fix(String reference, Function<String, List<String>> derivation) {

        /**
         * The renderings the port may produce for a value the oracle recorded.
         *
         * @param oracleValue the value in the golden file
         * @return the permitted renderings, or empty when the oracle's value is undescribed
         */
        public List<String> permittedFor(final String oracleValue) {
            return derivation.apply(oracleValue);
        }
    }
}
