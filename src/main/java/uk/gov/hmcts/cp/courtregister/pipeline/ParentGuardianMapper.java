package uk.gov.hmcts.cp.courtregister.pipeline;

import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterParentGuardian;
import uk.gov.hmcts.cp.courtregister.domain.RegisterDefendant;

/**
 * Maps a youth defendant's parent or guardian.
 *
 * <p>Ports {@code .../Mappers/ParentGuardian/ParentGuardianMapper.js}. Finds the defendant's
 * associated persons, takes the first whose role is {@code parent} and falls back to the first whose
 * role is {@code guardian} ({@code :24-31}), then composes the name from its parts and maps the
 * address. Where there is no such person there is no parent guardian — the field is simply absent.
 *
 * <p>The guardian fallback has never run: the one parent-guardian fixture has a parent. Neither has
 * the case that matters most — a parent or guardian with no address. The schema requires
 * {@code address} on this component, so that document is rejected with a 400, and the legacy
 * swallows the rejection and loses the whole hearing's register (defect C29). This mapper's job is
 * to let that absence arrive at the pre-send validator as an absence; refusing the document is the
 * validator's.
 *
 * <p><strong>A role that is not a string is not the role being looked for.</strong>
 * {@code associatedPerson.role.toLowerCase()} ({@code :28}) reads the role with no check that it is
 * one, so a payload carrying a coded role — a number, an object — throws a {@code TypeError} the
 * whole hearing's register is lost to. Here the search simply moves on. It is the same class as C19
 * and C20 and is not separately catalogued, because the register the legacy loses to it is a
 * register it also loses to them.
 */
// PMD.OnlyOneReturn: the parent search, the guardian fallback and the no-such-person answer are
// three legacy expressions, and each answers where the legacy's own `find` answers.
@SuppressWarnings("PMD.OnlyOneReturn")
final class ParentGuardianMapper {

    /** The role looked for first. */
    private static final String PARENT = "parent";

    /** The role looked for where there is no parent. */
    private static final String GUARDIAN = "guardian";

    /** The entry's own person block, which the legacy tests alongside the role. */
    private static final String PERSON = "person";

    private ParentGuardianMapper() {
    }

    /**
     * Maps the parent or guardian of one gathered defendant.
     *
     * @param registerDefendant the gathered defendant whose payload record carries the associated
     *                          persons
     * @param hearing           the hearing payload
     * @return the mapped parent or guardian, or {@code null} where the defendant has neither
     */
    /* default */ static CourtRegisterParentGuardian map(
            final RegisterDefendant registerDefendant, final JsonNode hearing) {

        final List<JsonNode> defendants =
                DefendantMapper.defendantsOf(hearing, registerDefendant.masterDefendantId());
        // `defendantMapper.getDefendants()[0]` with no length check (`:15`) — C19's construct, in
        // the mapper C19's fix guards. A defendant nothing resolves never reaches this one, so the
        // dereference is kept rather than given a second, different answer here.
        final JsonNode defendant = Json.dereferencedElement(
                defendants.isEmpty() ? null : defendants.get(0), "defendants");

        final JsonNode person = parentOrGuardian(Json.array(defendant, "associatedPersons"));
        if (person == null) {
            return null;
        }
        return new CourtRegisterParentGuardian(
                JsStrings.composedName(person), AddressMapper.map(Json.at(person, "address")));
    }

    /**
     * The person the register addresses: a parent where there is one, a guardian otherwise.
     *
     * @param associatedPersons the defendant's associated persons, in payload order
     * @return the person block, or {@code null} where the defendant has neither
     */
    private static JsonNode parentOrGuardian(final List<JsonNode> associatedPersons) {
        final JsonNode parent = inRole(associatedPersons, PARENT);
        return parent == null ? inRole(associatedPersons, GUARDIAN) : parent;
    }

    /**
     * The first associated person in the given role who carries a person block.
     *
     * @param associatedPersons the defendant's associated persons, in payload order
     * @param role              the role being looked for, lower case
     * @return the person block, or {@code null}
     */
    private static JsonNode inRole(final List<JsonNode> associatedPersons, final String role) {
        for (final JsonNode associatedPerson : associatedPersons) {
            final JsonNode recorded = Json.at(associatedPerson, "role");
            // `role.toLowerCase()` on a value that is not a string is a TypeError. A coded role is
            // not the role being looked for, and the search carries on.
            if (recorded != null && recorded.isString()
                    && role.equals(recorded.stringValue().toLowerCase(Locale.ROOT))
                    && Json.truthy(associatedPerson, PERSON)) {
                return Json.at(associatedPerson, PERSON);
            }
        }
        return null;
    }
}
