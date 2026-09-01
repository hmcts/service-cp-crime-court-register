package uk.gov.hmcts.cp.courtregister.support;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The values a privacy suite looks for, and the fixtures that carry them.
 *
 * <p>Every defendant on a court register is a child, and this service's log lines are shipped to an
 * index the whole estate can read. Two suites hold the pipeline to that rule from different heights
 * — {@code config/TelemetryPrivacyTest} over the assembled bean graph with the four outward ports
 * doubled, and {@code config/TelemetryPrivacyIT} over the live adapters, a real cache and real HTTP
 * contexts — and they have to be looking for the same values. Two lists of markers is how one suite
 * comes to sweep for a field the other one has stopped setting, with both of them green.
 *
 * <p>The markers are deliberately implausible strings. A suite looking for the word "name" would
 * fail on a field called {@code loggerName}; a suite looking for a value nothing else in this
 * repository can produce fails only when that value really was written down.
 */
public final class PersonalDataMarkers {

    /** The child's name, on both name fields, and their guardian's. */
    public static final String CHILD_NAME = "CHILDNAMEMARKERZQX7";

    /** The first line of the child's address. */
    public static final String CHILD_ADDRESS = "CHILDADDRESSMARKERZQX7";

    /** The child's national insurance number. */
    public static final String CHILD_NINO = "CHILDNINOMARKERZQX7";

    /** The child's own email address, in the form the frozen contract's format assertion accepts. */
    public static final String CHILD_EMAIL = "child.contact.marker.zqx7@example.invalid";

    /** The child's self-defined ethnicity description. */
    public static final String ETHNICITY = "ETHNICITYMARKERZQX7";

    /** The parent or guardian's name. */
    public static final String GUARDIAN = "GUARDIANMARKERZQX7";

    /** The free text most likely to describe a child: the prosecution's statement of facts. */
    public static final String FACTS = "STATEMENTOFFACTSMARKERZQX7";

    /**
     * The child's date of birth — a real date rather than a marker word, because the mappers parse
     * it and a hearing whose child has no readable birthday would never reach the lines under test.
     * It is nonetheless a value nothing else in this repository produces.
     */
    public static final String DATE_OF_BIRTH = "2009-11-23";

    /** The subscribing organisation's name, which reference data supplies and the register carries. */
    public static final String RECIPIENT_ORGANISATION = "RECIPIENTORGMARKERZQX7";

    /**
     * The address the register would be emailed to.
     *
     * <p>A deliverable-looking domain, unlike the child's: this one is on the outbound document and
     * the frozen contract asserts {@code format: email} over it, so a marker the validator refuses
     * would fail the register before it could be posted — which is a contract case, not a privacy
     * one.
     */
    public static final String RECIPIENT_EMAIL = "recipient.marker.zqx7@example.gov.uk";

    /** Every marker that names or describes a person, and must never appear at any level. */
    public static final List<String> PERSONAL = List.of(
            CHILD_NAME, CHILD_ADDRESS, CHILD_NINO, CHILD_EMAIL, ETHNICITY, GUARDIAN, FACTS,
            DATE_OF_BIRTH);

    /**
     * Everything reference data supplies about who a register goes to.
     *
     * <p>Not a defendant's data, and still not a log's business: an organisation's contact address
     * is somebody else's text, it arrives over the wire, and the rule about text the far end chose
     * is the same rule whoever it describes.
     */
    public static final List<String> RECIPIENT = List.of(RECIPIENT_ORGANISATION, RECIPIENT_EMAIL);

    private PersonalDataMarkers() {
        // Static fixture holder.
    }

    /**
     * Replaces every field of a hearing that identifies its child with a marker.
     *
     * <p>The envelope is edited in place and handed back, so the caller decides where the copy came
     * from: the unit suite marks a base fixture, and the container suite marks one that has already
     * been re-identified as the hearing its own request names.
     *
     * <p>A field the fixture does not carry is left alone rather than invented. The
     * address-less hearing is a fixture on purpose — it is the C29 shape — and marking it must not
     * quietly give it the address whose absence is the point of it.
     *
     * @param envelope a claim-check envelope built on one of the base hearings
     * @return the same envelope, with a child made of markers wherever it has fields to hold them
     */
    public static JsonNode marked(final JsonNode envelope) {
        final ObjectNode prosecutionCase =
                (ObjectNode) envelope.get("hearing").get("prosecutionCases").get(0);
        prosecutionCase.put("statementOfFacts", FACTS);

        final JsonNode defendant = prosecutionCase.get("defendants").get(0);
        final ObjectNode personDetails =
                (ObjectNode) defendant.get("personDefendant").get("personDetails");
        personDetails.put("firstName", CHILD_NAME);
        personDetails.put("lastName", CHILD_NAME);
        personDetails.put("dateOfBirth", DATE_OF_BIRTH);
        personDetails.put("nationalInsuranceNumber", CHILD_NINO);
        marking(personDetails, "address", "address1", CHILD_ADDRESS);
        marking(personDetails, "contact", "primaryEmail", CHILD_EMAIL);
        marking(personDetails, "ethnicity", "selfDefinedEthnicityDescription", ETHNICITY);

        final JsonNode associated = defendant.get("associatedPersons");
        if (associated != null && !associated.isEmpty()) {
            final ObjectNode guardian = (ObjectNode) associated.get(0).get("person");
            guardian.put("firstName", GUARDIAN);
            guardian.put("lastName", GUARDIAN);
        }
        return envelope;
    }

    /** Marks one field of a nested object, where the fixture carries that object at all. */
    private static void marking(final ObjectNode owner, final String child, final String field,
            final String marker) {
        final JsonNode nested = owner.get(child);
        if (nested != null && nested.isObject()) {
            ((ObjectNode) nested).put(field, marker);
        }
    }

    /**
     * A subscription that matches the base hearings, with its subscriber made of markers.
     *
     * <p>The recipient is what reference data contributes to a register, so it is the part of the
     * document that arrives over HTTP rather than out of the cache — and therefore the part a
     * reference-data adapter could quote back into a log line while explaining itself.
     *
     * @param ouCode the court house's OU code
     * @return the subscription
     */
    public static ObjectNode markedSubscription(final String ouCode) {
        final ObjectNode subscription =
                NowSubscriptionFixtures.youthCourtRegisterSubscription(ouCode);
        final ObjectNode recipient = (ObjectNode) subscription.get("recipient");
        recipient.put("organisationName", RECIPIENT_ORGANISATION);
        recipient.put("emailAddress1", RECIPIENT_EMAIL);
        return subscription;
    }
}
