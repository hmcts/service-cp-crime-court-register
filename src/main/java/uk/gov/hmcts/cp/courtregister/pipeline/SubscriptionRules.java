package uk.gov.hmcts.cp.courtregister.pipeline;

import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.RegisterVocabulary;

/**
 * Whether one now-subscription wants this register.
 *
 * <p>Ports the predicate half of {@code NowsHelper/service/SubscriptionsService.js} — the court-house
 * rule and the whole of {@code matchVocabularyRules}: attendance, major creditors, court house,
 * defendant, custody, custodial results, then the included and excluded prompt and result lists. The
 * kernel's own court-register case is the shape this answers for, and every branch below it is
 * shared with the NOWs flow and ports unchanged.
 *
 * <p>Pure: a subscription, a court-centre code, one defendant's vocabulary and the hearing's
 * judicial results in; a yes or a no out. No I/O, no clock (constitution Principle V).
 *
 * <p><strong>Three catalogued defects are fixed here.</strong>
 *
 * <ul>
 *   <li><strong>C4</strong> — the legacy feeds the same {@code ouCode} to {@code matchCourtHouse}
 *       ({@code selectedCourtHouses.includes}) <em>and</em> to {@code matchProsecutor}
 *       ({@code informantCode ===}), so a subscription whose informant code happens to equal a
 *       court-centre code is matched by a register nobody subscribed it to. A court centre's OU code
 *       has no meaning as an informant code; here it reaches the court-house rule and nothing
 *       else.</li>
 *   <li><strong>C5</strong> — the legacy has no branch keyed on
 *       {@code isCourtRegisterSubscription} at all ({@code SubscriptionsService.js:14-45}). A
 *       court-register subscription matches only by accident: through
 *       {@code selectedCourtHouses}, through a coincidental informant code, or through the
 *       {@code isNowSubscription} / {@code isEDTSubscription} /
 *       {@code isPrisonCourtRegisterSubscription} routes, none of which belongs to this flow.
 *       ({@code matchCpsProsecuted} at {@code :56} is dead code and is not reproduced.) Here the
 *       branch is explicit: a court-register subscription matches through its selected court houses
 *       and the vocabulary rules, and a subscription that is not one cannot match at all.</li>
 *   <li><strong>C30</strong> — {@code checkIfMajorCreditorTypeMatch} is asymmetric on empty data.
 *       {@code prosecutorMajorCreditor} and {@code nonProsecutorMajorCreditor} require a non-empty
 *       list and so can never match a court register, whose lists are always empty; but
 *       {@code anyMajorCreditor} tests {@code != null}, and an empty array is not null, so it always
 *       passes. All three now require a non-empty applicable list, consistently.</li>
 * </ul>
 */
public final class SubscriptionRules {

    /** The marker the red run records while the matching rules are unwritten. */
    private static final String UNIMPLEMENTED = "the subscription rules are not ported yet";

    /**
     * Whether the given subscription wants a register carrying this defendant's vocabulary.
     *
     * @param subscription    one now-subscription, exactly as reference data sent it
     * @param ouCode          the court centre's OU code; the court-house rule's only input
     * @param vocabulary      one register defendant's vocabulary, or {@code null} where the register
     *                        has none
     * @param judicialResults every judicial result the register gathered, across all its defendants
     * @return whether this subscription is matched
     */
    public boolean matches(
            final JsonNode subscription,
            final String ouCode,
            final RegisterVocabulary vocabulary,
            final List<JsonNode> judicialResults) {
        throw new UnsupportedOperationException(UNIMPLEMENTED);
    }
}
