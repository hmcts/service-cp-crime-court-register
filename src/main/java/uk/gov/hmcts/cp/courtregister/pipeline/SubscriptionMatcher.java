package uk.gov.hmcts.cp.courtregister.pipeline;

import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.application.NowSubscriptionsSource;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.ReferenceDataUnavailableException;
import uk.gov.hmcts.cp.courtregister.domain.RegisterFragment;
import uk.gov.hmcts.cp.courtregister.domain.RegisterResult;

/**
 * Who a built register is addressed to.
 *
 * <p>Ports {@code CourtRegisterSubscriptions/index.js}: read the subscriptions in force on the
 * register's day, keep the court-register ones, and ask {@link SubscriptionRules} about each. It is
 * the one class in this package that reaches a port, because the activity it ports is the one that
 * reads reference data; the rules it asks stay pure.
 *
 * <p><strong>Defect C31 is fixed here.</strong> {@code index.js:49} builds the matcher's input with
 * {@code subscriptionObj.vocabulary = registerDefendants[0].vocabulary} — one vocabulary, taken from
 * whichever defendant the hearing happened to gather first. A register carries every defendant the
 * hearing gathered, adults included, because the youth filter runs a stage later; so an adult-first
 * hearing has its youth register matched against adult vocabulary, and a subscription keyed on
 * {@code youthDefendant} — which is what a court-register subscription is keyed on — matches nothing.
 * The register is built, completes successfully, and reaches nobody. Matching is evaluated per
 * defendant instead: a subscription matches if <em>any</em> register defendant's vocabulary
 * satisfies it.
 *
 * <p>The judicial results handed to the rules are unchanged: they are collected across all
 * defendants ({@code index.js:53-63}), which is the asymmetry that makes the single-vocabulary read
 * look deliberate and is not.
 */
public final class SubscriptionMatcher {

    private final NowSubscriptionsSource subscriptions;
    private final SubscriptionRules rules;
    private final Dates dates;

    /**
     * Creates the matcher over the reference-data port and the rules it asks.
     *
     * @param subscriptions where the now-subscriptions come from
     * @param rules         whether one subscription wants one defendant's register
     * @param dates         the register's date handling, for the day the subscriptions are read on
     */
    public SubscriptionMatcher(
            final NowSubscriptionsSource subscriptions,
            final SubscriptionRules rules,
            final Dates dates) {
        this.subscriptions = subscriptions;
        this.rules = rules;
        this.dates = dates;
    }

    /**
     * The subscriptions this register is addressed to.
     *
     * @param fragment the built register
     * @param caller   the identity the reference-data read is made as
     * @return the matched subscriptions, in the order reference data returned them; empty where
     *     nothing matched, which is an answer and not a failure
     * @throws ReferenceDataUnavailableException if reference data could not be read
     */
    public List<JsonNode> match(final RegisterFragment fragment, final CallerIdentity caller) {
        final JsonNode answer =
                subscriptions.subscriptionsOn(dates.subscriptionDay(fragment.registerDate()), caller);

        // `index.js:22` — reference data answering with no `nowSubscriptions` is still an answer,
        // and the register completes addressed to nobody. Not answering at all throws out of the
        // port above and never reaches here, which is the half of the legacy's single case that
        // stops being a completion.
        final List<JsonNode> inForce = Json.array(answer, "nowSubscriptions");
        final String ouCode = fragment.courtCentreOUCode();
        final List<JsonNode> judicialResults = judicialResultsOf(fragment);

        // The legacy's two remaining guards — no subscriptions in force, and a register that
        // gathered no defendants (`index.js:26-29`) — need no branch of their own: an empty set in
        // force filters to nothing, and a register with no defendants satisfies nothing. Both end
        // where the legacy's early returns end, at a register addressed to nobody.
        return inForce.stream()
                .filter(subscription -> Json.truthy(subscription, "isCourtRegisterSubscription"))
                .filter(subscription -> wantedByAnyDefendant(
                        subscription, ouCode, fragment, judicialResults))
                .toList();
    }

    /**
     * Whether any register defendant's vocabulary satisfies this subscription — defect fix C31.
     *
     * <p>The subscription is the outer loop and the defendants the inner one, which is what keeps
     * the answer in reference data's own order: recipient order is the order the register's emails
     * are queued in, and walking defendants outermost would reorder it. A subscription that several
     * defendants satisfy is still one recipient.
     *
     * @param subscription    the subscription being asked about
     * @param ouCode          the court centre's OU code
     * @param fragment        the built register
     * @param judicialResults every judicial result the register gathered
     * @return whether the subscription wants this register
     */
    private boolean wantedByAnyDefendant(
            final JsonNode subscription,
            final String ouCode,
            final RegisterFragment fragment,
            final List<JsonNode> judicialResults) {

        return fragment.registerDefendants().stream()
                .anyMatch(defendant -> rules.matches(
                        subscription, ouCode, defendant.vocabulary(), judicialResults));
    }

    /**
     * {@code collectJudicialResults}: every defendant's results, flattened, in gather order.
     *
     * <p>Unchanged from the legacy, and deliberately so — it is the asymmetry that makes the
     * single-vocabulary read at {@code index.js:49} look deliberate. The prompt and result rules
     * have always been asked about the whole hearing; only the vocabulary was narrowed to one
     * defendant.
     *
     * @param fragment the built register
     * @return the judicial results, in the order the defendants were gathered
     */
    private static List<JsonNode> judicialResultsOf(final RegisterFragment fragment) {
        return fragment.registerDefendants().stream()
                .flatMap(defendant -> defendant.results().stream())
                .map(RegisterResult::judicialResult)
                .filter(Objects::nonNull)
                .toList();
    }
}
