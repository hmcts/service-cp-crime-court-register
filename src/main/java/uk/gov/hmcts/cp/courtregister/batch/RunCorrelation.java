package uk.gov.hmcts.cp.courtregister.batch;

import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * The correlation a scheduled unit of work has, in place of the two a delivery has.
 *
 * <p>Constitution Principle VII asks that every line about processing carry the correlation of the
 * unit of work it belongs to. For a delivery that is {@code requestId} and {@code hearingId}. A run
 * has neither and cannot: it is one unit of work across many hearings and many batches, and those
 * identifiers belong to the deliveries that recorded the registers, not to the night that renders
 * them. Before this the eleven lines a night writes - the job's and the reconciler's - carried no
 * correlation at all, so a night could not be pulled out of the estate's index as one thing. On an
 * evening where the grace-period sweep is also settling batches from earlier nights, that is the
 * difference between reading a run and reading a haystack.
 *
 * <p><strong>Nesting is the whole reason this is a type rather than two calls.</strong> There are
 * two independently scheduled units here - {@code RegisterGenerationJob.run()} and
 * {@code GenerationReconciler.reconcileScheduled()} - and the first calls into the second. A sweep
 * reached through a run is part of that run and must carry its id; a sweep that fired on its own
 * schedule is a unit of work in its own right and needs one of its own. So the work adopts an
 * ambient correlation where there is one, and only the call that put one there takes it away again.
 *
 * <p>The removal matters as much as the minting, which is why the work is handed in rather than the
 * scope handed out: the scheduler's threads are pooled and reused, so an id left behind would be
 * inherited by the next run on that thread and by anything else that thread writes - and that is
 * worse than no correlation at all, because it reads as a true one. There is no way to call this
 * and forget the {@code finally}.
 */
final class RunCorrelation {

    /**
     * The MDC key, camel-cased like the delivery path's four rather than snake-cased like the run
     * line's fields: this is a correlation slot beside {@code requestId} and {@code hearingId}, and
     * the same index filters read it.
     */
    /* default */ static final String KEY = "runId";

    private RunCorrelation() {
        // The key, the scope that mints it and the reader that names it on one line.
    }

    /**
     * Runs work under a correlation, adopting an ambient one where one is already set.
     *
     * @param work the unit of work
     * @param <T>  what it answers
     * @return whatever the work answered
     */
    /* default */ static <T> T under(final Supplier<T> work) {
        final boolean owned = MDC.get(KEY) == null;
        if (owned) {
            MDC.put(KEY, UUID.randomUUID().toString());
        }
        try {
            return work.get();
        } finally {
            if (owned) {
                MDC.remove(KEY);
            }
        }
    }

    /**
     * Runs work that answers nothing under a correlation.
     *
     * @param work the unit of work
     */
    /* default */ static void under(final Runnable work) {
        under(() -> {
            work.run();
            return null;
        });
    }

    /**
     * The correlation this unit of work is running under, for the one line that names it as a field.
     *
     * @return the id, or {@code null} outside any correlation
     */
    /* default */ static String current() {
        return MDC.get(KEY);
    }
}
