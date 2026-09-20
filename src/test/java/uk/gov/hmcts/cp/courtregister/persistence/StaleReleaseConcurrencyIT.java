package uk.gov.hmcts.cp.courtregister.persistence;

import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.gov.hmcts.cp.courtregister.application.NotificationSummary;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.GuardDecision;
import uk.gov.hmcts.cp.courtregister.domain.NotificationStatus;
import uk.gov.hmcts.cp.courtregister.domain.ReasonCode;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterNotification;
import uk.gov.hmcts.cp.courtregister.domain.RegisterRecord;
import uk.gov.hmcts.cp.courtregister.domain.RequestFingerprint;
import uk.gov.hmcts.cp.courtregister.domain.RunClaim;
import uk.gov.hmcts.cp.courtregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.courtregister.support.ProcessedLogTestSupport;

/**
 * The fenced release raced against the two things that can overtake it - SC-009.
 *
 * <p>This is the reason {@code failAndReleaseStale} is one statement rather than a read followed by
 * a mark, and it is the suite the rest of the increment stands on: everything after Phase 2 assumes
 * the operation is atomic and fenced on the staleness rule itself.
 *
 * <p>Two contenders, because they are the two ways a batch stops being stale while a run is deciding
 * about it: a render request accepted for a PENDING batch ({@code markRequested}), and a
 * {@code document-available} applied to a GENERATING one ({@code markGenerated}). Each is run in
 * <strong>both winner orders</strong> - staged, so the two deterministic outcomes can be asserted by
 * name - and then <strong>repeatedly, genuinely concurrently</strong>, where either may win and only
 * the invariants may be asserted.
 *
 * <p>The invariants, after every round:
 *
 * <ul>
 *   <li><strong>No stranded register.</strong> A register still awaiting its document is either
 *       stamped to a live batch or active and unbatched, and never stamped to a terminal one -
 *       {@code ACTIVE_UNBATCHED}'s predicate is {@code batch_id IS NULL}, so a RECORDED row stamped
 *       to a FAILED batch is a hearing's youth defendants that no later run and no command will ever
 *       reach again. That is the precise failure this increment exists to end, and the failure a
 *       read-then-mark shape reintroduces the moment a crash lands between its two statements.</li>
 *   <li><strong>One live batch per key.</strong> Two would render and e-mail one court centre day
 *       twice.</li>
 *   <li><strong>One notification aggregate per key and address.</strong> SC-003 read off the rows: a
 *       Youth Offending Team is never told twice about one register date, whichever contender won
 *       the race that night.</li>
 *   <li><strong>Every refusal is the state machine's.</strong> The loser of a race is refused with
 *       {@link IllegalStateException} - which the listener rethrows and the broker redelivers, the
 *       self-healing path - and never with anything else.</li>
 * </ul>
 *
 * <p>Each round mints its own court centre, so the rounds and the other suites sharing the container
 * hold no rows in common, and every cutoff stated here is in the past for the same reason.
 *
 * <p>Soft assertions, so a red run reports every round rather than stopping at the first.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the stale release under concurrency")
class StaleReleaseConcurrencyIT {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);

    private static final Instant MONDAY_SHARED = Instant.parse("2026-08-24T09:00:00Z");

    private static final Instant HEARING_DATE = Instant.parse("2026-08-19T00:00:00Z");

    private static final Duration LEASE = Duration.ofMinutes(5);

    /** Older than the cutoff every round states, which is what the night between two runs leaves. */
    private static final Duration LAST_NIGHT = Duration.ofHours(2);

    /** What a run gives up after, and the age every cutoff here is stated at. */
    private static final Duration STALE_AFTER = Duration.ofMinutes(30);

    private static final String OU_CODE = "B01LY00";

    private static final String APPLICANT = "Applicant";

    private static final String YOT_ADDRESS = "yot@example.gov.uk";

    private static final String NOT_COMPLETED = "NOT_COMPLETED_BY_NEXT_RUN";

    /** The states in which a batch is still owed something, which is what makes a key busy. */
    private static final List<String> LIVE = List.of("PENDING", "GENERATING", "GENERATED");

    /** How long a round waits for its two contenders before calling the race hung. */
    private static final int RACE_SECONDS = 30;

    /** The single row a fixture ageing a batch is expected to touch. */
    private static final int ONE_BATCH = 1;

    /**
     * The orders a round is run in: the two staged ones, then four genuine races.
     *
     * <p>Staged first because the two deterministic outcomes are the properties worth naming - the
     * pass wins and the outcome is refused, or the outcome wins and the pass matches nothing - and
     * concurrently afterwards because a property that only holds when the scheduler cooperates is
     * not a property.
     */
    private static final List<Order> ROUNDS = List.of(
            Order.RELEASE_FIRST, Order.OUTCOME_FIRST,
            Order.TOGETHER, Order.TOGETHER, Order.TOGETHER, Order.TOGETHER);

    private final RegisterStore store =
            new JdbcRegisterStore(ProcessedLogTestSupport.jdbcClient(),
                    ProcessedLogTestSupport.transactions());

    private final RegisterNotificationRepository notifications =
            new RegisterNotificationRepository(ProcessedLogTestSupport.jdbcClient());

    @InjectSoftAssertions
    private SoftAssertions softly;

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    /** Which of the two contenders is let go first, or whether both are let go at once. */
    private enum Order {
        RELEASE_FIRST, OUTCOME_FIRST, TOGETHER
    }

    @Test
    void a_render_acceptance_racing_the_release_leaves_no_stranded_register() {
        final List<Ending> endings = new ArrayList<>();

        for (final Order order : ROUNDS) {
            final UUID courtCentre = UUID.randomUUID();
            final Instant cutoff = cutoff();
            final RegisterBatch batch = staleBatch(courtCentre, false);
            final Escapes escaped = raced(order,
                    () -> store.failAndReleaseStale(cutoff, cutoff),
                    () -> store.markRequested(batch.batchId(), UUID.randomUUID()));

            final Ending ending = endingOf(batch.batchId());
            settleTheNight(courtCentre);
            endings.add(ending);
            assertInvariants(courtCentre, order, cutoff, escaped);
        }

        softly.assertThat(endings.get(0))
                .as("the pass let go first fails the batch it found stale, under the one bounded "
                        + "reason that means the passage of time, and the render request that "
                        + "follows is refused by the state machine rather than accepted against a "
                        + "batch this service has already given up on")
                .isEqualTo(new Ending("FAILED", NOT_COMPLETED));
        softly.assertThat(endings.get(1))
                .as("and a render accepted first moves the batch out of the pass's reach without "
                        + "any read of a state that has since moved: the request is stamped now, "
                        + "so the predicate no longer matches and nothing is written for it")
                .isEqualTo(new Ending("GENERATING", null));
    }

    @Test
    void a_document_arrival_racing_the_release_leaves_no_stranded_register() {
        final List<Ending> endings = new ArrayList<>();

        for (final Order order : ROUNDS) {
            final UUID courtCentre = UUID.randomUUID();
            final Instant cutoff = cutoff();
            final RegisterBatch batch = staleBatch(courtCentre, true);
            final Escapes escaped = raced(order,
                    () -> store.failAndReleaseStale(cutoff, cutoff),
                    () -> store.markGenerated(batch.batchId(), UUID.randomUUID(), Instant.now(),
                            CompletedBy.EVENT));

            final Ending ending = endingOf(batch.batchId());
            settleTheNight(courtCentre);
            endings.add(ending);
            assertInvariants(courtCentre, order, cutoff, escaped);
        }

        softly.assertThat(endings.get(0))
                .as("the pass let go first fails the batch, and the document that arrives after it "
                        + "is refused by the state machine - the listener rethrows, the broker "
                        + "redelivers, and the sink then reads a FAILED batch and drops the "
                        + "outcome. Self-healing, and the registers are already back")
                .isEqualTo(new Ending("FAILED", NOT_COMPLETED));
        softly.assertThat(endings.get(1))
                .as("and a document that arrives first takes the batch to GENERATED, where the "
                        + "pass may not touch it at any age: it holds a document somebody is owed "
                        + "e-mails about")
                .isEqualTo(new Ending("GENERATED", null));
    }

    /**
     * Everything SC-009 asks of a round, whichever contender won it.
     *
     * @param courtCentre this round's court centre
     * @param order       the order its two contenders were let go in, named in every failure
     * @param cutoff      the cutoff this round's pass was given, which is the fence itself
     * @param escaped     whatever the two contenders threw
     */
    private void assertInvariants(final UUID courtCentre, final Order order, final Instant cutoff,
            final Escapes escaped) {
        softly.assertThat(escaped.release())
                .as("%s: **no single batch's outcome may end the run** (FR-003a). The pass is run "
                        + "inline in the night's generation, so a refusal escaping it costs every "
                        + "court centre its document - which is exactly what a read-then-mark "
                        + "shape does the moment the outcome sink commits between its two steps",
                        order)
                .isEmpty();
        softly.assertThat(escaped.outcome())
                .as("%s: and the contender that loses is refused by the state machine and by "
                        + "nothing else. That refusal is the self-healing path - the listener "
                        + "rethrows it, the broker redelivers, and the sink reads a batch it may "
                        + "no longer move", order)
                .allSatisfy(thrown -> softly.assertThat(thrown)
                        .isInstanceOf(IllegalStateException.class));
        softly.assertThat(prematurelyFailed(courtCentre, cutoff))
                .as("%s: and no batch is given up on that was not stale by the rule. The fence is "
                        + "the predicate itself, re-evaluated against the row as it stands when "
                        + "the write lands; a rule computed into a list beforehand would fail a "
                        + "batch whose render had been accepted in between - orphaning that render "
                        + "and rendering the same court centre day twice", order)
                .isZero();
        softly.assertThat(strandedRegisters(courtCentre))
                .as("%s: no register is left awaiting a document while stamped to a batch nothing "
                        + "will finish. Unbatched means batch_id IS NULL, so such a row is "
                        + "invisible to every later run and to every command - the lost register "
                        + "this increment exists to end", order)
                .isZero();
        softly.assertThat(liveBatches(courtCentre))
                .as("%s: at most one batch of the key is still owed something, because two would "
                        + "render and e-mail one court centre day twice", order)
                .isLessThanOrEqualTo(1L);
        softly.assertThat(aggregatesPerAddress(courtCentre))
                .as("%s: the day's one Youth Offending Team holds exactly one aggregate for the "
                        + "register date (SC-003), whichever way the race went - one, because the "
                        + "night is settled before this is read and a day nobody was told about "
                        + "would satisfy 'never twice' by having told nobody at all", order)
                .containsExactly(1L);
        softly.assertThat(liveRegisters(courtCentre))
                .as("%s: and both registers are still the day's, rather than one of them having "
                        + "been superseded or lost by a race nothing shared a key with", order)
                .isEqualTo(2L);
    }

    /**
     * A court centre day with two registers, batched and aged past the cutoff.
     *
     * @param courtCentre this round's court centre
     * @param requested   whether the batch has had its render requested, so GENERATING rather than
     *                    PENDING
     * @return the batch as the row stood at assembly
     */
    private RegisterBatch staleBatch(final UUID courtCentre, final boolean requested) {
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        record(courtCentre, first);
        record(courtCentre, second);
        final List<RegisterRecord> waiting = waiting(courtCentre);
        final RegisterBatch batch = store.assemble(batchFor(courtCentre, waiting), waiting);
        if (requested) {
            store.markRequested(batch.batchId(), UUID.randomUUID());
        }
        ageBatch(batch.batchId());
        return batch;
    }

    /**
     * The rest of the night, run after the race so the invariants are asserted on a settled day.
     *
     * <p>Two halves, and which of them has anything to do is exactly what the race decided. A batch
     * the race left still owed something - PENDING, GENERATING or holding a document - is walked to
     * its ending and notified once, as the renderer and the notifier do it; registers the pass gave
     * back are assembled into tonight's batch and walked the same way, as the same run does it.
     *
     * <p><strong>Every round settles, and that is what makes the aggregate invariant a claim.</strong>
     * A round whose batch was left GENERATING used to have neither half to run, so the day ended
     * with no notification at all and "no Youth Offending Team holds two aggregates" was satisfied
     * by holding none. A render this service did not give up on is one that goes on to produce its
     * document and its e-mail, so the fixture finishes it rather than stopping where the race did.
     *
     * @param courtCentre this round's court centre
     */
    private void settleTheNight(final UUID courtCentre) {
        store.batchesOn(MONDAY).stream()
                .filter(batch -> courtCentre.equals(batch.courtCentreId()))
                .filter(batch -> LIVE.contains(batch.status().name()))
                .forEach(this::finished);
        final List<RegisterRecord> given = waiting(courtCentre);
        if (!given.isEmpty()) {
            finished(store.assemble(batchFor(courtCentre, given), given));
        }
    }

    /**
     * A batch walked from wherever the race left it to its document, and told about once.
     *
     * @param batch the batch as it stood when it was read
     */
    private void finished(final RegisterBatch batch) {
        if (batch.status() == BatchStatus.PENDING) {
            store.markRequested(batch.batchId(), UUID.randomUUID());
        }
        if (batch.status() != BatchStatus.GENERATED) {
            store.markGenerated(batch.batchId(), UUID.randomUUID(), Instant.now(),
                    CompletedBy.EVENT);
        }
        notified(batch);
    }

    /** One aggregate for the day's one Youth Offending Team, and the batch settled on its tally. */
    private void notified(final RegisterBatch batch) {
        notifications.insert(new RegisterNotification(UUID.randomUUID(), batch.batchId(),
                YOT_ADDRESS, "Wandsworth Youth Offending Team", "cr_standard", UUID.randomUUID(),
                NotificationStatus.ACCEPTED, 201, Instant.now(), 1));
        store.markNotified(batch.batchId(), new NotificationSummary(1, 0, BatchStatus.NOTIFIED));
    }

    /**
     * What escaped each of the two contenders, kept apart because only one of them may throw.
     *
     * @param release what the pass threw, which must be nothing whoever won the race
     * @param outcome what the render acceptance or the document arrival threw
     */
    private record Escapes(List<Throwable> release, List<Throwable> outcome) {
    }

    /**
     * Runs the two contenders in this round's order, and answers with whatever escaped each.
     *
     * @param order   which is let go first, or whether both are let go at once
     * @param release the pass
     * @param outcome the render acceptance or the document arrival racing it
     * @return what each of them threw
     */
    private static Escapes raced(final Order order, final Runnable release,
            final Runnable outcome) {
        return switch (order) {
            case RELEASE_FIRST -> sequentially(release, outcome, true);
            case OUTCOME_FIRST -> sequentially(outcome, release, false);
            case TOGETHER -> concurrently(release, outcome);
        };
    }

    /** One after the other, so the winner is the one named rather than the one that got there. */
    private static Escapes sequentially(final Runnable first, final Runnable second,
            final boolean releaseFirst) {
        final List<Throwable> from = escaping(first);
        final List<Throwable> then = escaping(second);
        return releaseFirst ? new Escapes(from, then) : new Escapes(then, from);
    }

    /**
     * Both at once, off one latch, so either may win and the invariants have to hold regardless.
     *
     * <p>A pool of two and a latch both threads wait on, rather than two threads started in turn:
     * the second start would otherwise be the head start, and the round would be a staged order
     * wearing a race's clothes.
     */
    private static Escapes concurrently(final Runnable release, final Runnable outcome) {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            final CountDownLatch start = new CountDownLatch(1);
            final Future<List<Throwable>> first = pool.submit(() -> {
                start.await();
                return escaping(release);
            });
            final Future<List<Throwable>> second = pool.submit(() -> {
                start.await();
                return escaping(outcome);
            });
            start.countDown();
            return new Escapes(first.get(RACE_SECONDS, TimeUnit.SECONDS),
                    second.get(RACE_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the race was interrupted", interrupted);
        } catch (ExecutionException | TimeoutException unfinished) {
            throw new IllegalStateException("a contender never finished", unfinished);
        }
    }

    /**
     * Runs one contender and collects what it threw, because a refusal is a result here.
     *
     * <p>The loser of a race is refused, and the suite's whole subject is what that refusal leaves
     * behind. Collected rather than propagated, and asserted on by
     * {@link #assertInvariants(UUID, Order, Instant, Escapes)} - which is also what keeps the red
     * run an assertion while the operation is still a seam.
     */
    private static List<Throwable> escaping(final Runnable contender) {
        final Throwable refused = catchThrowable(contender::run);
        return refused == null ? List.of() : List.of(refused);
    }

    /** The registers of this court centre's day that are still waiting to be batched. */
    private List<RegisterRecord> waiting(final UUID courtCentre) {
        return store.activeUnbatched().stream()
                .filter(record -> courtCentre.equals(record.key().courtCentreId()))
                .toList();
    }

    /** The batch the assembler would make of them, asked for by the schedule. */
    private RegisterBatch batchFor(final UUID courtCentre, final List<RegisterRecord> records) {
        return new RegisterBatch(UUID.randomUUID(), courtCentre, null, null, MONDAY,
                records.isEmpty() ? null : records.getFirst().fileName(), null, null,
                BatchStatus.PENDING, null, null, true, null, null, null, null, null, null, 0,
                null, 0);
    }

    /** A cutoff in the past, so a round can only reach the batch it aged itself. */
    private static Instant cutoff() {
        return Instant.now().minus(STALE_AFTER);
    }

    /** Ages a batch's two in-flight stamps, as the night between two runs does. */
    private void ageBatch(final UUID batchId) {
        final int aged = ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        UPDATE register_batch
                           SET assembled_at = assembled_at - make_interval(secs => :age),
                               requested_at = requested_at - make_interval(secs => :age)
                         WHERE batch_id = :batchId
                        """)
                .param("age", (double) LAST_NIGHT.toSeconds())
                .param("batchId", batchId)
                .update();
        if (aged != ONE_BATCH) {
            throw new IllegalStateException(
                    "expected one batch to age for " + batchId + ", aged " + aged);
        }
    }

    /** A batch given up on although its own stamp says it was not stale when the write landed. */
    private long prematurelyFailed(final UUID courtCentre, final Instant cutoff) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre
                           AND status = 'FAILED'
                           AND failure_reason = :reason
                           AND COALESCE(requested_at, assembled_at) > :cutoff
                        """)
                .param("courtCentre", courtCentre)
                .param("reason", NOT_COMPLETED)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    /** A register still awaiting its document while stamped to a batch nothing will finish. */
    private long strandedRegisters(final UUID courtCentre) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM processed_output waiting
                          JOIN register_batch dead ON dead.batch_id = waiting.batch_id
                         WHERE waiting.court_centre_id = :courtCentre
                           AND waiting.status = 'RECORDED'
                           AND waiting.superseded_at IS NULL
                           AND dead.status NOT IN ('PENDING', 'GENERATING', 'GENERATED')
                        """)
                .param("courtCentre", courtCentre)
                .query(Long.class)
                .single();
    }

    /** How many batches of this court centre's day are still owed something. */
    private long liveBatches(final UUID courtCentre) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                           AND status IN (:live)
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", MONDAY)
                .param("live", LIVE)
                .query(Long.class)
                .single();
    }

    /** How many aggregates each recipient of this court centre's day holds. */
    private List<Long> aggregatesPerAddress(final UUID courtCentre) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM register_notification told
                          JOIN register_batch batch ON batch.batch_id = told.batch_id
                         WHERE batch.court_centre_id = :courtCentre
                           AND batch.register_date = :registerDate
                         GROUP BY told.email_address
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", MONDAY)
                .query(Long.class)
                .list();
    }

    /** How many of this court centre's registers are still the day's, in whatever state. */
    private long liveRegisters(final UUID courtCentre) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM processed_output
                         WHERE court_centre_id = :courtCentre AND superseded_at IS NULL
                        """)
                .param("courtCentre", courtCentre)
                .query(Long.class)
                .single();
    }

    /**
     * Where the round left the batch it raced over, read before the night is settled.
     *
     * <p>Before, because settling it moves a GENERATED batch on to NOTIFIED and the question here
     * is what the race decided, not what the notifier did afterwards.
     *
     * @param status        the state the race left the batch in
     * @param failureReason the bounded reason where it ended FAILED, and null otherwise
     */
    private record Ending(String status, String failureReason) {
    }

    private Ending endingOf(final UUID batchId) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("SELECT status, failure_reason FROM register_batch WHERE batch_id = :batchId")
                .param("batchId", batchId)
                .query((rs, row) -> new Ending(rs.getString("status"),
                        rs.getString("failure_reason")))
                .single();
    }

    /** One hearing's register, recorded the way the pipeline records it. */
    private void record(final UUID courtCentre, final UUID hearingId) {
        final DistributionCommand command = new DistributionCommand(
                ProcessedLogTestSupport.SOURCE, UUID.randomUUID(), hearingId,
                LocalDate.ofInstant(MONDAY_SHARED, LONDON), MONDAY_SHARED, "Hearing_Resulted");
        ProcessedLogTestSupport.repository(LEASE).insertNew(command,
                RequestFingerprint.of(command),
                new RunClaim(command.source(), command.requestId(), "runner-1", UUID.randomUUID(),
                        "msg-1"));
        store.recordAndComplete(command, document(courtCentre, hearingId), OU_CODE, APPLICANT,
                RecordedFlagState.ON,
                () -> new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
    }

    private CourtRegisterDocument document(final UUID courtCentre, final UUID hearingId) {
        return new CourtRegisterDocument(
                MONDAY_SHARED.toString(),
                HEARING_DATE.toString(),
                hearingId.toString(),
                courtCentre.toString(),
                "court-register_" + MONDAY + '_' + OU_CODE + '_' + hearingId + ".pdf",
                null,
                new CourtRegisterHearingVenue("Lavender Hill LJA", "Lavender Hill Youth Court",
                        null),
                List.of(new CourtRegisterRecipient(
                        "Wandsworth Youth Offending Team", YOT_ADDRESS, null, "cr_standard")),
                List.of(new CourtRegisterDefendant(
                        "b2b3f5a1-6c9d-4e21-8a7f-3d5c1e9b0426", "SMITH, John", "2008-04-11",
                        null, null, null, "MALE", "Not Applicable", null, null,
                        List.of(), List.of(), List.of(), List.of())));
    }
}
