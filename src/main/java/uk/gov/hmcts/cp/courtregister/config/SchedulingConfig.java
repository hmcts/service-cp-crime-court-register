package uk.gov.hmcts.cp.courtregister.config;

import net.javacrumbs.shedlock.core.LockProvider;

/**
 * What makes the nightly job one run rather than one run per replica.
 *
 * <p>ShedLock over the service's own Postgres, against the {@code shedlock} table V2 creates. The
 * service deploys with a single replica today, so this is insurance rather than a fix: relying on
 * {@code replicas: 1} is a deployment fact and not a code guarantee, and the cost of being wrong is
 * two documents and two e-mails for every court centre in the country.
 *
 * <p>The zone the schedule is read in is validated here as well, by
 * {@link GenerationProperties#validate()}, because 18:00 is a wall-clock requirement that has to
 * hold in BST and in GMT alike. The legacy fires in the scheduling JVM's default zone, its Quartz
 * trigger having been built without one, and that ambiguity is not inherited: an override needs
 * {@code courtregister.generation.zone-override-acknowledged}, and startup refuses without it.
 *
 * <p><strong>Seam only.</strong> The configuration lands with T050; until then this is annotated
 * with nothing and creates no beans, so no context gains a scheduler it has no job for.
 */
public class SchedulingConfig {

    /**
     * The lock the job holds while it runs.
     *
     * @return the JDBC lock provider, over the processed log's own datasource
     */
    public LockProvider lockProvider() {
        throw new UnsupportedOperationException("T050");
    }
}
