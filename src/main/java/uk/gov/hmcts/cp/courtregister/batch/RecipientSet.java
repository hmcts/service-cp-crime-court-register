package uk.gov.hmcts.cp.courtregister.batch;

import java.util.List;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.courtregister.domain.RegisterRecord;

/**
 * Who one batch's document is e-mailed to.
 *
 * <p>The de-duplicated union of every record's recipients across the batch, keyed on
 * {@code emailAddress1}, with the name taken from the address's first occurrence. One address, one
 * e-mail, one {@code register_notification} row: the same Youth Offending Team on ten hearings is
 * told once, and the row that says so is the evidence it was told.
 *
 * <p><strong>Defect fix P4.</strong> The progression leg keeps only the first non-empty recipient
 * list it sees for a court centre ({@code CourtCentreAggregate.java:71-83}) and every later record's
 * recipients are discarded, so a subscription that exists on the day's second hearing and not on its
 * first is never e-mailed at all. Subscriptions are keyed on the court centre, so the union usually
 * equals that first list and the deviation only bites where subscriptions differ within a court
 * centre - which is exactly the case the legacy loses silently. The union is content-affecting, so
 * the register row carries the sign-off-before-cutover marker (research §9).
 *
 * <p>The order is the order the addresses were first seen, so a batch read twice produces the same
 * recipients in the same order, and a run report a person compares by eye reads the same way twice.
 *
 * <p>Nothing here reaches a log. An address and a recipient name are the two components that never
 * appear at INFO or above (constitution Principle VII); this class hands them to the notification
 * rows, which are the only place that may hold them.
 *
 * <p><strong>Seam only.</strong> The union lands with T057; until then this throws, so that
 * {@code RecipientSetTest} records a failing assertion rather than a compile error.
 */
public final class RecipientSet {

    private RecipientSet() {
        // Function holder.
    }

    /**
     * The batch's recipients, de-duplicated by address.
     *
     * <p>{@link RegisterRecord#recipients()} is what is read from each record rather than the
     * document's own list, because empty and absent are the same statement to a union and the
     * document distinguishes them only because progression's schemas do.
     *
     * @param records the batch's registers, in the order the batch holds them
     * @return one recipient per distinct {@code emailAddress1}, named from its first occurrence, in
     *     the order the addresses were first seen; empty where no record matched anybody, which is
     *     the batch that ends NOTIFIED_NOBODY (defect fix P1)
     */
    public static List<CourtRegisterRecipient> unionOf(final List<RegisterRecord> records) {
        throw new UnsupportedOperationException("T057");
    }
}
