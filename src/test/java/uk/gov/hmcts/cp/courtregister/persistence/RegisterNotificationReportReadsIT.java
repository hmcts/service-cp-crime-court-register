package uk.gov.hmcts.cp.courtregister.persistence;

import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.data.Offset.offset;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.FailedNotification;
import uk.gov.hmcts.cp.courtregister.domain.NotificationStatus;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterNotification;
import uk.gov.hmcts.cp.courtregister.support.AdjustableClock;
import uk.gov.hmcts.cp.courtregister.support.ReportReadsDatabase;

/**
 * The one read the exception report makes of the notification rows.
 *
 * <p>NOTIFICATION_FAILED is the kind that says a register exists and a Youth Offending Team was not
 * told about it, so what the entry has to carry is which team's day it was - the batch's court
 * centre and register date - and how long ago the attempt was settled. Both come from the same
 * statement: the join is what stops the read being one query per row on precisely the morning the
 * list is longest.
 *
 * <p><strong>Two of these cases are about the statement rather than about the rows</strong>, and
 * neither is answerable from what comes back. One statement and thirty produce the same list, and a
 * column that is never selected leaves no trace in a projection that has no component for it. So
 * the fixture records the SQL the driver was asked to prepare, and those two cases read it.
 *
 * <p>A database of this suite's own, and soft assertions, for the reasons
 * {@link ProcessedRequestReportReadsIT} gives.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the notification rows' report read")
class RegisterNotificationReportReadsIT {

    private static final String DATABASE = "courtregister_notification_report_reads";

    private static final String NOTIFICATION_TABLE = "register_notification";
    private static final String BATCH_TABLE = "register_batch";

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 9, 15);

    private static final String OU_CODE = "B01LY00";
    private static final String COURT_HOUSE = "Lavender Hill Youth Court";

    private static final UUID TEMPLATE_ID =
            UUID.fromString("5f0b2d71-8c43-4e19-a6d2-7b1e4c093a58");
    private static final String TEMPLATE_NAME = "courtregister-notification";

    /**
     * The one personal value the table holds, and the string no captured statement may mention.
     */
    private static final String ADDRESS_COLUMN = "email_address";

    /** How long a claim stays live here; irrelevant to a read, and stated rather than defaulted. */
    private static final Duration NOTIFIER_LEASE = Duration.ofMinutes(15);

    private static final String SEAM =
            "the report's failed-notification read implements this statement; this is its red run";

    private static final Duration A_LONG_WAY = Duration.ofHours(1);

    private static final long SECONDS_OF_SLACK = 5;

    private static ReportReadsDatabase database;
    private static RegisterBatchRepository batches;
    private static RegisterNotificationRepository repository;

    @InjectSoftAssertions
    private SoftAssertions softly;

    @BeforeAll
    static void migrate() {
        database = ReportReadsDatabase.migrated(DATABASE);
        batches = new RegisterBatchRepository(database.jdbcClient(), database.transactions(),
                NOTIFIER_LEASE);
        repository = new RegisterNotificationRepository(database.jdbcClient());
    }

    @BeforeEach
    void emptyTheTables() {
        database.empty(NOTIFICATION_TABLE, BATCH_TABLE);
    }

    @Test
    void failed_since_returns_failed_notifications_with_their_batchs_court_centre_and_register_date() {
        final RegisterBatch batch = batch(MONDAY);
        final UUID refused = settled(batch, "yot@example.gov.uk", NotificationStatus.FAILED, 400,
                minutesAgo(30));
        settled(batch, "accepted@example.gov.uk", NotificationStatus.ACCEPTED, 202, minutesAgo(30));
        settled(batch, "stale@example.gov.uk", NotificationStatus.FAILED, 500, hoursAgo(9));

        final List<FailedNotification> failed = failedSince(hoursAgo(2));

        softly.assertThat(failed)
                .as("a report is a statement about a period, and a team that was told is not an "
                        + "exception at all")
                .extracting(FailedNotification::notificationId)
                .containsExactly(refused);
        softly.assertThat(failed)
                .as("the batch's key, carried by the join, is what says whose day went untold")
                .extracting(FailedNotification::batchId, FailedNotification::courtCentreId,
                        FailedNotification::registerDate, FailedNotification::status,
                        FailedNotification::responseCode, FailedNotification::attempts)
                .containsExactly(tuple(batch.batchId(), batch.courtCentreId(), MONDAY,
                        NotificationStatus.FAILED, 400, 1));
    }

    @Test
    void failed_since_orders_oldest_first() {
        final RegisterBatch monday = batch(MONDAY);
        final RegisterBatch tuesday = batch(TUESDAY);
        final UUID oldest = settled(tuesday, "late@example.gov.uk", NotificationStatus.FAILED, 500,
                minutesAgo(90));
        final UUID next = settled(monday, "later@example.gov.uk", NotificationStatus.FAILED, 500,
                minutesAgo(45));
        final UUID newest = settled(monday, "newest@example.gov.uk", NotificationStatus.FAILED,
                null, minutesAgo(5));

        softly.assertThat(failedSince(hoursAgo(4)))
                .as("oldest first, because the team that has been waiting longest is the one a "
                        + "morning's resend starts with")
                .extracting(FailedNotification::notificationId)
                .containsExactly(oldest, next, newest);
    }

    @Test
    void failed_since_is_one_statement_not_one_per_row() {
        final RegisterBatch monday = batch(MONDAY);
        final RegisterBatch tuesday = batch(TUESDAY);
        settled(monday, "one@example.gov.uk", NotificationStatus.FAILED, 500, minutesAgo(30));
        settled(monday, "two@example.gov.uk", NotificationStatus.FAILED, 500, minutesAgo(20));
        settled(tuesday, "three@example.gov.uk", NotificationStatus.FAILED, 500, minutesAgo(10));
        database.forgetStatements();

        final List<FailedNotification> failed = failedSince(hoursAgo(4));

        softly.assertThat(database.statements())
                .as("N+1 reads land on precisely the morning the list is longest, which is the "
                        + "morning the report has to be quick")
                .hasSize(1);
        softly.assertThat(failed)
                .as("and it answered all three, across two batches")
                .hasSize(3);
    }

    @Test
    void age_seconds_is_computed_by_the_database_not_the_jvm() {
        final RegisterBatch batch = batch(MONDAY);
        settled(batch, "yot@example.gov.uk", NotificationStatus.FAILED, 500, minutesAgo(10));
        final AdjustableClock jvm = AdjustableClock.startingAt(Instant.now());

        final List<FailedNotification> before = failedSince(hoursAgo(4));
        final long jvmBefore = jvmAge(before, jvm);
        jvm.advance(A_LONG_WAY);
        final List<FailedNotification> after = failedSince(hoursAgo(4));

        softly.assertThat(jvmAge(after, jvm) - jvmBefore)
                .as("the counterfactual: an age this JVM derived would have moved by exactly as "
                        + "far as its clock was moved")
                .isEqualTo(A_LONG_WAY.toSeconds());
        softly.assertThat(ageOf(after))
                .as("the database's own reading did not move")
                .isCloseTo(ageOf(before), offset(SECONDS_OF_SLACK));
        softly.assertThat(ageOf(before))
                .as("and it is the real age, measured from when the attempt was settled")
                .isCloseTo(Duration.ofMinutes(10).toSeconds(), offset(SECONDS_OF_SLACK));
    }

    @Test
    void the_email_address_column_is_never_selected() {
        final RegisterBatch batch = batch(MONDAY);
        settled(batch, "yot@example.gov.uk", NotificationStatus.FAILED, 500, minutesAgo(30));
        database.forgetStatements();

        failedSince(hoursAgo(4));

        softly.assertThat(database.statements())
                .as("the read this case is about is the only statement it made")
                .hasSize(1);
        softly.assertThat(String.join("\n", database.statements()))
                .as("the one personal value in the table: a column that is never read cannot be "
                        + "logged by accident, so there is no value to mask and none to forget to")
                .doesNotContain(ADDRESS_COLUMN);
    }

    // --- the read, made so that a seam's refusal is recorded rather than thrown -----------------

    private List<FailedNotification> failedSince(final Instant since) {
        final AtomicReference<List<FailedNotification>> answered = new AtomicReference<>(List.of());
        softly.assertThatCode(() -> answered.set(repository.failedSince(since)))
                .as(SEAM)
                .doesNotThrowAnyException();
        return answered.get();
    }

    // --- seeding ------------------------------------------------------------------------------

    /**
     * A batch of its own court centre, inserted where a batch enters the table.
     *
     * <p>Its state is irrelevant to this read - the join is for the key, not for the status - so it
     * is left where {@link RegisterBatchRepository#insert} admits it.
     */
    private RegisterBatch batch(final LocalDate registerDate) {
        final RegisterBatch assembled = new RegisterBatch(UUID.randomUUID(), UUID.randomUUID(),
                OU_CODE, COURT_HOUSE, registerDate, fileName(registerDate), null, null,
                BatchStatus.PENDING, null, null, true, null, Instant.now(), null, null, null, null,
                0, null, 0);
        batches.insert(assembled);
        return assembled;
    }

    /**
     * One recipient's row, minted already settled.
     *
     * <p>Written through the repository's own insert rather than as SQL of this suite's invention,
     * so the row is exactly the shape the notifying leg writes; {@code sent_at} is a value that leg
     * supplies, which is why the age this read computes is the database's {@code now()} less a
     * timestamp a pod wrote.
     */
    private UUID settled(final RegisterBatch batch, final String address,
            final NotificationStatus status, final Integer responseCode, final Instant sentAt) {
        final UUID notificationId = UUID.randomUUID();
        repository.insert(new RegisterNotification(notificationId, batch.batchId(), address,
                "Wandsworth Youth Offending Team", TEMPLATE_NAME, TEMPLATE_ID, status, responseCode,
                sentAt, 1));
        return notificationId;
    }

    // --- reading the answers ------------------------------------------------------------------

    private long ageOf(final List<FailedNotification> answered) {
        return answered.isEmpty() ? -1 : answered.get(0).ageSeconds();
    }

    private long jvmAge(final List<FailedNotification> answered, final AdjustableClock jvm) {
        return answered.isEmpty()
                ? 0
                : Duration.between(answered.get(0).sentAt(), jvm.instant()).toSeconds();
    }

    private static String fileName(final LocalDate registerDate) {
        return "court-register_" + registerDate + '_' + OU_CODE + ".pdf";
    }

    private static Instant hoursAgo(final long hours) {
        return Instant.now().minus(Duration.ofHours(hours));
    }

    private static Instant minutesAgo(final long minutes) {
        return Instant.now().minus(Duration.ofMinutes(minutes));
    }
}
