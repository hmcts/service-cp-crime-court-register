package uk.gov.hmcts.cp.courtregister.pipeline;

import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.application.NowSubscriptionsSource;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.ReferenceDataUnavailableException;
import uk.gov.hmcts.cp.courtregister.domain.RegisterFragment;

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

    /** The marker the red run records while the matching stage is unwritten. */
    private static final String UNIMPLEMENTED = "the subscription matching is not ported yet";

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
        throw new UnsupportedOperationException(UNIMPLEMENTED);
    }
}
