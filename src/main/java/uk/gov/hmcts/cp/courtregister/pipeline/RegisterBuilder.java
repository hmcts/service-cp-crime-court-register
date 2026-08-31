package uk.gov.hmcts.cp.courtregister.pipeline;

import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.RegisterFragment;

/**
 * Turns a hearing payload into the register fragment for that hearing.
 *
 * <p>Ports {@code SetCourtRegister/index.js} — the gather, the latest ordered date, the
 * court-extract filter, the per-defendant vocabulary, and the six fields of the fragment — in the
 * legacy's own order, because the order is load-bearing: the ordered dates are collected from the
 * results <em>before</em> the court-extract filter removes any of them
 * ({@code index.js:40-43}), so a result that never reaches the register still decides which day the
 * register covers.
 *
 * <p>Pure: JSON in, a typed fragment out, no clock and no I/O (constitution Principle V). Every date
 * it needs comes from {@link Dates}, which is where the three date fixes live.
 *
 * <p>Two catalogued defects surface here:
 *
 * <ul>
 *   <li><strong>C6</strong> — {@code index.js:35-38} guards the gather's result with
 *       {@code if (!defendantContextBaseList) return;}, which can never fire because the builder
 *       always returns an array. The guard disappears: a hearing that gathers nobody produces a
 *       fragment with an empty defendant list, and the pipeline records that as the named outcome
 *       {@code no-defendants} rather than as an absent return nothing downstream can tell from an
 *       exception.</li>
 *   <li><strong>C10</strong> — {@code registerDate} is the instant the results were shared, not a
 *       London wall clock relabelled with a {@code Z}. See {@link Dates#dateTime(String)}.</li>
 * </ul>
 */
public final class RegisterBuilder {

    /** The marker the red run records while the fragment build is unwritten. */
    private static final String UNIMPLEMENTED = "the court register fragment is not built yet";

    private final Dates dates;

    /**
     * Creates the builder over the register's date handling.
     *
     * @param dates the register's date handling
     */
    public RegisterBuilder(final Dates dates) {
        this.dates = dates;
    }

    /**
     * Builds one hearing's register fragment.
     *
     * @param hearing    the hearing payload, exactly as the producer sent it
     * @param sharedTime the instant the results were shared
     * @return the fragment, which carries an empty defendant list where the hearing gathered nobody
     * @throws uk.gov.hmcts.cp.courtregister.domain.TransformationFailedException if the payload
     *     cannot be read
     */
    public RegisterFragment build(final JsonNode hearing, final String sharedTime) {
        throw new UnsupportedOperationException(UNIMPLEMENTED);
    }
}
