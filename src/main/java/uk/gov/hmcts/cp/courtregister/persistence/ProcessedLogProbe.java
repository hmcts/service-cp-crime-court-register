package uk.gov.hmcts.cp.courtregister.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Can the processed log be reached right now?
 *
 * <p>One question, answered by asking the database the cheapest thing it can answer. It exists
 * because processed-log availability is a <strong>precondition</strong> rather than a step: a
 * delivery that arrives without a store must be handed back before it is examined, not part-way
 * through being processed, and the consumer lifecycle controller must be able to ask the same
 * question on a schedule to know when the outage is over.
 *
 * <p>The failure is caught and turned into an answer rather than propagated — which is what this
 * class is <em>for</em>, and is not a swallowed exception: every caller acts on the answer, and the
 * two that matter both do something loud with it. The exception itself is reported at DEBUG, because
 * during an outage this runs every few seconds and a stack trace per probe would bury the one ERROR
 * that says intake has stopped.
 */
public class ProcessedLogProbe {

    private final JdbcClient jdbcClient;

    /**
     * Creates the probe over the processed log's connection.
     *
     * @param jdbcClient the processed log's connection
     */
    public ProcessedLogProbe(final JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Asks the processed log to answer a trivial query.
     *
     * @return whether the processed log answered
     */
    public boolean available() {
        throw new UnsupportedOperationException("T018 implements the store probe");
    }
}
