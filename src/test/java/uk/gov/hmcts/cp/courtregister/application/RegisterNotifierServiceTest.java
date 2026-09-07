package uk.gov.hmcts.cp.courtregister.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.invocation.InvocationOnMock;
import uk.gov.hmcts.cp.courtregister.adapter.http.RetryPause;
import uk.gov.hmcts.cp.courtregister.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;
import uk.gov.hmcts.cp.courtregister.domain.CourtCentreDay;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.courtregister.domain.FailureClassification;
import uk.gov.hmcts.cp.courtregister.domain.NotificationFailedException;
import uk.gov.hmcts.cp.courtregister.domain.NotificationStatus;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterNotification;
import uk.gov.hmcts.cp.courtregister.domain.RegisterRecord;
import uk.gov.hmcts.cp.courtregister.domain.StoreRefusedRowException;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterNotificationRepository;
import uk.gov.hmcts.cp.courtregister.support.AdjustableClock;
import uk.gov.hmcts.cp.courtregister.support.CapturedLog;
import uk.gov.hmcts.cp.courtregister.support.PersonalDataMarkers;

/**
 * One generated batch, from its recipients to the e-mails they are told by.
 *
 * <p>The order is the subject of the first group and the reason this class exists at all: every
 * recipient's {@code register_notification} row is minted PENDING <em>before</em> the first POST is
 * made, so an e-mail that was asked for and never answered is a row that says so rather than an
 * absence indistinguishable from one that was never tried. That identity is also the one a retry
 * has to reuse - notificationnotify keys its aggregate on it and takes it as the path parameter -
 * so a second POST under a fresh one is a second e-mail to the same Youth Offending Team
 * (research §10).
 *
 * <p>Each recipient is then settled on its own and the batch on the tally alone. One team's refusal
 * says nothing about another team's e-mail, so the batch carries on and ends PARTIALLY_NOTIFIED
 * rather than failing; the teams that were told are not told again, which is what
 * {@code resendFailed} is for.
 *
 * <p><strong>Defect fix P1 is pinned here.</strong> progression's {@code CourtCentreAggregate}
 * answers an empty recipient list with {@code CourtRegisterNotificationIgnored}, an event no
 * descriptor subscribes to, and the batch sticks at GENERATED for ever, visible to nobody.
 * {@code a_batch_with_no_recipients_ends_notified_nobody_not_generated_forever} is the row's
 * pinning test: the batch reaches the terminal NOTIFIED_NOBODY and is counted on
 * {@code courtregister_batches_total{outcome}}, and nothing is posted and no row is minted, because
 * there was nobody to post to and no attempt to record.
 *
 * <p><strong>Defect fix P9's run-time half is pinned here too</strong>, as its register row says it
 * would be. The startup half - a blank or non-UUID {@code cr_standard} refusing to start - is
 * {@code ConfigurationValidationTest}'s; what this suite holds down is that a per-e-mail failure is
 * a FAILED row carrying the status that came back and a batch that ends PARTIALLY_NOTIFIED, rather
 * than the legacy's INFO line and a batch reporting the state it would have reported had everybody
 * been e-mailed.
 *
 * <p>The recipient union itself is not re-pinned here. Which addresses a batch has is
 * {@code RecipientSetTest}'s question (defect fix P4); this suite puts two records with an
 * overlapping address in front of the service and asks only that one distinct address becomes one
 * row and one POST.
 *
 * <p>The repository is doubled as a small ledger rather than with fixed answers, because the two
 * reads and the two writes are four moments in one row's life: a resend reads back what
 * {@code notify} wrote, and a suite whose reads did not reflect its own writes could pass a service
 * that settled the wrong rows.
 *
 * <p>The privacy cases are last and are a "never": every defendant on a court register is a child
 * and the recipients are a protected list, so the service's own lines carry notification ids, batch
 * ids and bounded codes, and neither an address, nor a recipient's name, nor a defendant's name is
 * ever one of them - nor a metric label (constitution Principle VII). The addresses and names live
 * in the notification rows, which are the only place that may hold them.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("one generated batch, from its recipients to their e-mails")
class RegisterNotifierServiceTest {

    /** Quoted by every case, so the red run names the seam it is waiting on. */
    private static final String PENDING =
            "T059 implements the notifier service; this is its red run";

    /** Returned when a meter is absent, so a missing count fails as an assertion. */
    private static final double ABSENT = -1;

    /** What the recorder writes when a row is minted, and when a POST is made. */
    private static final String MINTED = "minted";
    private static final String POSTED = "posted";

    private static final UUID BATCH_ID = UUID.fromString("a4c1f0d2-7e6b-4c8a-9f31-2d5b6e0a7c14");
    private static final UUID COURT_CENTRE =
            UUID.fromString("853b1ff8-fc2a-44d1-a621-0cd16419f54a");
    private static final String OU_CODE = "B01LY00";
    private static final String COURT_HOUSE = "Lavender Hill Youth Court";
    private static final LocalDate REGISTER_DATE = LocalDate.of(2026, 3, 2);
    private static final String FILE_NAME = "courtregister_2026-03-02.json";

    private static final UUID PAYLOAD_FILE_ID =
            UUID.fromString("6f6b1a8e-4c67-4f0f-9b2b-5f0f6a3a1d21");

    /** The rendered document, which is what every e-mail of this batch attaches by reference. */
    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("2b8a9d10-77c5-4a6d-8f3e-0d51c9a2e4b7");

    /** The {@code cr_standard} template id, resolved once at wiring (defect fix P9). */
    private static final UUID TEMPLATE_ID =
            UUID.fromString("0b7f2c31-6a4d-4e59-8c02-9a1e3f5d7b60");

    private static final Instant ASSEMBLED_AT = Instant.parse("2026-03-02T18:00:00Z");
    private static final Instant REQUESTED_AT = Instant.parse("2026-03-02T18:00:12Z");
    private static final Instant GENERATED_AT = Instant.parse("2026-03-02T18:01:40Z");
    private static final Instant NOTIFIED_AT = Instant.parse("2026-03-02T18:02:00Z");

    /** The instant every attempt of this suite is settled at: what {@code sent_at} must record. */
    private static final Instant SETTLED_AT = Instant.parse("2026-03-02T18:02:30Z");

    /**
     * Three Youth Offending Teams across two records, the middle one subscribed to both.
     *
     * <p>The overlap is why the batch has three addresses and not four: one distinct address is one
     * row, one POST and one e-mail, however many of the day's hearings it was matched to.
     */
    private static final String YOT_A = "Lambeth Youth Offending Team";
    private static final String YOT_B = "Wandsworth Youth Offending Team";
    private static final String YOT_C = "Merton Youth Offending Team";
    private static final String ADDRESS_A = "yot.lambeth@example.gov.uk";
    private static final String ADDRESS_B = "yot.wandsworth@example.gov.uk";
    private static final String ADDRESS_C = "yot.merton@example.gov.uk";

    /** A defendant name, so the privacy case has something it would be a breach to log. */
    private static final String DEFENDANT = "Fred Smith";

    /** The one status the contract calls success. */
    private static final int ACCEPTED = 202;

    /** The attempt count of a row nothing has been posted for yet, which is the column's default. */
    private static final int MINTED_NEVER_SETTLED = 0;

    /** The attempt a transiently-refused address is refused on, and only that one. */
    private static final int FIRST_ATTEMPT = 1;

    /** notificationnotify refused the command outright; another attempt answers the same. */
    private static final int REFUSED = 400;

    /** notificationnotify could not take the command now; another attempt may answer 202. */
    private static final int UNAVAILABLE = 503;

    /** The shared transport's own defaults, which are what the run's budget is made of. */
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(2);
    private static final Duration ATTEMPT_WORST_CASE = Duration.ofSeconds(15);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GenerationMetrics metrics = new GenerationMetrics(registry);
    private final AdjustableClock clock = AdjustableClock.startingAt(SETTLED_AT);
    private final RegisterStore store = mock(RegisterStore.class);
    private final RegisterBatchRepository batches = mock(RegisterBatchRepository.class);
    private final RegisterNotificationRepository notifications =
            mock(RegisterNotificationRepository.class);
    private final RegisterNotifier notifier = mock(RegisterNotifier.class);

    /**
     * The one policy all this service's clients hold, built from the settings they share.
     *
     * <p>The same object the generation leg is given (defect fix C3): the taxonomy is stated once
     * and only the loop belongs to whoever holds the budget an attempt is spent out of.
     */
    private final RetryPolicy retryPolicy = new RetryPolicy(MAX_ATTEMPTS, INITIAL_BACKOFF,
            MAX_BACKOFF, ATTEMPT_WORST_CASE);

    /** What would have been waited, rather than what was: a suite is not paid for in seconds. */
    private final List<Duration> waited = new ArrayList<>();

    private final RetryPause pause = waited::add;

    /** The {@code register_notification} table, keyed on the identity a row was minted with. */
    private final Map<UUID, RegisterNotification> ledger = new LinkedHashMap<>();

    /** Mint and POST in the order they happened, which is the first group's whole subject. */
    private final List<String> sequence = new ArrayList<>();

    /** Each row exactly as it was inserted, before any settlement overwrote it in the ledger. */
    private final List<RegisterNotification> minted = new ArrayList<>();

    /** Each row exactly as it was settled. */
    private final List<RegisterNotification> settled = new ArrayList<>();

    /** Each row a POST was made for, and the document id that POST attached. */
    private final List<RegisterNotification> posted = new ArrayList<>();
    private final List<UUID> postedDocuments = new ArrayList<>();

    /** What the batch was settled on, in the order the settlements were made. */
    private final List<BatchSettlement> settlements = new ArrayList<>();

    private final RegisterNotifierService service = new RegisterNotifierService(
            store, batches, notifications, notifier, metrics, TEMPLATE_ID, retryPolicy, pause,
            clock);

    @InjectSoftAssertions
    private SoftAssertions softly;

    @BeforeEach
    void seedTheGeneratedBatch() {
        when(batches.findById(BATCH_ID)).thenReturn(Optional.of(generated()));
        when(store.batched(BATCH_ID)).thenReturn(List.of(
                registerRecord(List.of(recipient(YOT_A, ADDRESS_A), recipient(YOT_B, ADDRESS_B))),
                registerRecord(List.of(recipient(YOT_B, ADDRESS_B), recipient(YOT_C, ADDRESS_C)))));

        doAnswer(this::mint).when(notifications).insert(any());
        doAnswer(this::settle).when(notifications).update(any());
        doAnswer(this::rowsOf).when(notifications).findByBatchId(any());
        doAnswer(this::unsettledRowsOf).when(notifications).findUnsettledByBatchId(any());
        doAnswer(this::recordSettlement).when(store).markNotified(any(), any());
        doAnswer(this::acceptThePost).when(notifier).send(any(), any(), any());
    }

    /** The batch as the outcome sink left it: GENERATED, with the document it may now send. */
    private static RegisterBatch generated() {
        return batch(BatchStatus.GENERATED, null);
    }

    /** The batch a resend is asked for: PARTIALLY_NOTIFIED, some team still owed its e-mail. */
    private static RegisterBatch partiallyNotified() {
        return batch(BatchStatus.PARTIALLY_NOTIFIED, NOTIFIED_AT);
    }

    private static RegisterBatch batch(final BatchStatus status, final Instant notifiedAt) {
        return new RegisterBatch(BATCH_ID, COURT_CENTRE, OU_CODE, COURT_HOUSE, REGISTER_DATE,
                FILE_NAME, PAYLOAD_FILE_ID, DOCUMENT_FILE_ID, status, null, null, true,
                CompletedBy.EVENT, ASSEMBLED_AT, REQUESTED_AT, GENERATED_AT, notifiedAt, null, 1,
                null, 0);
    }

    /**
     * One recorded register addressed to the given teams.
     *
     * <p>The recipients are read off the document, which is where they were validated and stored,
     * and the defendant is there so the privacy case has a name it would be a breach to log.
     *
     * @param recipients who this record's register is addressed to, or {@code null} where it
     *                   matched nobody, which is what the document carries rather than an empty list
     * @return the record, as the batch half reads it back
     */
    private static RegisterRecord registerRecord(final List<CourtRegisterRecipient> recipients) {
        final UUID hearingId = UUID.randomUUID();
        return new RegisterRecord(UUID.randomUUID(), hearingId, GENERATED_AT,
                new CourtCentreDay(COURT_CENTRE, REGISTER_DATE), GENERATED_AT, FILE_NAME,
                "Applicant", RecordedFlagState.ON,
                new CourtRegisterDocument("2026-03-02", "2026-03-02T09:00:00Z",
                        hearingId.toString(), COURT_CENTRE.toString(), FILE_NAME, "Applicant", null,
                        recipients,
                        List.of(new CourtRegisterDefendant(UUID.randomUUID().toString(), DEFENDANT,
                                "2009-11-23", null, null, null, null, null, null, null, null, null,
                                null, null))));
    }

    /** A matched subscription, under the template name every court-register e-mail is sent with. */
    private static CourtRegisterRecipient recipient(final String name, final String address) {
        return new CourtRegisterRecipient(name, address, null,
                RegisterNotifierService.TEMPLATE_NAME);
    }

    /**
     * A row a resend finds already on the table, under the identity it was first attempted with.
     *
     * @param name    the team's name
     * @param address the team's address
     * @param status  where that first attempt left it
     * @param code    the status notificationnotify answered with, or {@code null} where nothing did
     * @return the row, which is also put on the ledger the repository answers from
     */
    private RegisterNotification seeded(final String name, final String address,
            final NotificationStatus status, final Integer code) {
        final RegisterNotification row = new RegisterNotification(UUID.randomUUID(), BATCH_ID,
                address, name, RegisterNotifierService.TEMPLATE_NAME, TEMPLATE_ID, status, code,
                SETTLED_AT, 1);
        ledger.put(row.notificationId(), row);
        return row;
    }

    /**
     * A row a run minted and never settled, which is the shape a crash leaves behind.
     *
     * <p>No status, no {@code sent_at} and no attempt: whether the POST was ever made is exactly
     * what this row cannot say, which is why it is owed a re-request rather than left alone.
     *
     * @param name    the team's name
     * @param address the team's address
     * @return the row, which is also put on the ledger the repository answers from
     */
    private RegisterNotification abandoned(final String name, final String address) {
        final RegisterNotification row = new RegisterNotification(UUID.randomUUID(), BATCH_ID,
                address, name, RegisterNotifierService.TEMPLATE_NAME, TEMPLATE_ID,
                NotificationStatus.PENDING, null, null, MINTED_NEVER_SETTLED);
        ledger.put(row.notificationId(), row);
        return row;
    }

    /**
     * One address another mechanism mints a row for between this run's read and its own insert.
     *
     * <p>The operator's resend and the outcome sink can reach one batch at the same time, so the
     * (batch, address) key is a race two ordinary runs can lose - and the row the winner wrote is
     * the row notificationnotify's aggregate is keyed by. The double writes it to the ledger at the
     * moment of the refusal, which is exactly when it becomes visible to the loser.
     *
     * @param name    the team's name
     * @param address the team's address
     * @return the row that won, under the identity the winner minted it with
     */
    private RegisterNotification racedTo(final String name, final String address) {
        final RegisterNotification winner = new RegisterNotification(UUID.randomUUID(), BATCH_ID,
                address, name, RegisterNotifierService.TEMPLATE_NAME, TEMPLATE_ID,
                NotificationStatus.PENDING, null, null, MINTED_NEVER_SETTLED);
        doAnswer(invocation -> {
            ledger.put(winner.notificationId(), winner);
            throw new StoreRefusedRowException("the store refused a notification row for one "
                    + "recipient of batch " + BATCH_ID + ", which "
                    + "register_notification_unique_recipient does when the row is already held");
        }).when(notifications).insert(argThat(sentTo(address)));
        return winner;
    }

    /**
     * Puts one batch's recipients in front of the service and asks it to tell them.
     *
     * <p>The call is made inside {@code assertThatCode(...).doesNotThrowAnyException()} so that the
     * seam's refusal is recorded as an assertion rather than ending the case, and what it did not
     * produce is then asserted on as it stands - the red run is the seam and the green run is the
     * same assertions unchanged.
     *
     * @return what the service answered, or {@code null} where the seam refused
     */
    private NotificationSummary notifyBatch() {
        return answered(() -> service.notify(BATCH_ID));
    }

    /**
     * Asks the service to resend only what failed.
     *
     * @return what the service answered, or {@code null} where the seam refused
     */
    private NotificationSummary resend() {
        return answered(() -> service.resendFailed(BATCH_ID));
    }

    private NotificationSummary answered(final Supplier<NotificationSummary> call) {
        final List<NotificationSummary> summary = new ArrayList<>();
        softly.assertThatCode(() -> summary.add(call.get()))
                .as(PENDING)
                .doesNotThrowAnyException();
        return summary.isEmpty() ? null : summary.get(0);
    }

    /** notificationnotify accepts every recipient, which is the default of every case. */
    private Object acceptThePost(final InvocationOnMock invocation) {
        recordPost(invocation);
        return new NotificationOutcome(NotificationStatus.ACCEPTED, ACCEPTED);
    }

    /**
     * One address notificationnotify refuses, with a status line to record against its row.
     *
     * <p>Stubbed with {@code doAnswer(...).when(...)} rather than {@code when(...).thenAnswer(...)}
     * because the latter would really call {@code send} while stubbing, and the accepting default
     * set in {@code seedTheGeneratedBatch} would answer it - recording a POST this case never made.
     *
     * @param address the address that is refused
     * @param code    the status it is refused with
     */
    private void refuses(final String address, final int code) {
        doAnswer(invocation -> {
            recordPost(invocation);
            throw new NotificationFailedException(FailureClassification.NON_TRANSIENT, code);
        }).when(notifier).send(argThat(sentTo(address)), any(), any());
    }

    /**
     * One address nothing answers for at all: a connection that never produced a status line.
     *
     * @param address the address whose attempt reaches no verdict
     */
    private void answersNothingFor(final String address) {
        doAnswer(invocation -> {
            recordPost(invocation);
            throw new NotificationFailedException(FailureClassification.TRANSIENT);
        }).when(notifier).send(argThat(sentTo(address)), any(), any());
    }

    /**
     * One address notificationnotify cannot take the command for now, and then can.
     *
     * <p>The shape the whole retry exists for: a 503, a 429 or a read timeout says nothing about
     * whether the same command under the same identity would be accepted a moment later.
     *
     * @param address the address whose first attempt is refused transiently
     * @param code    the status that first attempt is refused with
     */
    private void refusesOnceThenAccepts(final String address, final int code) {
        final AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            recordPost(invocation);
            if (attempts.incrementAndGet() == FIRST_ATTEMPT) {
                throw new NotificationFailedException(FailureClassification.TRANSIENT, code);
            }
            return new NotificationOutcome(NotificationStatus.ACCEPTED, ACCEPTED);
        }).when(notifier).send(argThat(sentTo(address)), any(), any());
    }

    /**
     * One address notificationnotify never manages to take the command for.
     *
     * @param address the address every attempt is refused for
     * @param code    the status each of them is refused with
     */
    private void refusesTransiently(final String address, final int code) {
        doAnswer(invocation -> {
            recordPost(invocation);
            throw new NotificationFailedException(FailureClassification.TRANSIENT, code);
        }).when(notifier).send(argThat(sentTo(address)), any(), any());
    }

    private static ArgumentMatcher<RegisterNotification> sentTo(final String address) {
        return row -> row != null && address.equals(row.emailAddress());
    }

    private void recordPost(final InvocationOnMock invocation) {
        sequence.add(POSTED);
        posted.add(invocation.getArgument(0));
        postedDocuments.add(invocation.getArgument(1));
    }

    private Object mint(final InvocationOnMock invocation) {
        final RegisterNotification row = invocation.getArgument(0);
        sequence.add(MINTED);
        minted.add(row);
        ledger.put(row.notificationId(), row);
        return null;
    }

    private Object settle(final InvocationOnMock invocation) {
        final RegisterNotification row = invocation.getArgument(0);
        settled.add(row);
        ledger.put(row.notificationId(), row);
        return 1;
    }

    private Object rowsOf(final InvocationOnMock invocation) {
        return rows(invocation.getArgument(0)).toList();
    }

    private Object unsettledRowsOf(final InvocationOnMock invocation) {
        return rows(invocation.getArgument(0))
                .filter(row -> row.status() != NotificationStatus.ACCEPTED)
                .toList();
    }

    private Stream<RegisterNotification> rows(final UUID batchId) {
        return ledger.values().stream().filter(row -> row.batchId().equals(batchId));
    }

    private Object recordSettlement(final InvocationOnMock invocation) {
        settlements.add(new BatchSettlement(invocation.getArgument(0), invocation.getArgument(1)));
        return null;
    }

    /** The addresses a POST was made for, in the order the POSTs were made. */
    private List<String> postedAddresses() {
        return posted.stream().map(RegisterNotification::emailAddress).toList();
    }

    /** The addresses a row was minted for, in the order the rows were minted. */
    private List<String> mintedAddresses() {
        return minted.stream().map(RegisterNotification::emailAddress).toList();
    }

    /** The identities the rows were minted under, in the order they were minted. */
    private List<UUID> mintedIds() {
        return minted.stream().map(RegisterNotification::notificationId).toList();
    }

    /**
     * Every row the table now holds, as the three components a settlement may write.
     *
     * <p>Read off the whole table rather than by looking one row up, so that a case whose row was
     * never minted at all fails as a missing tuple rather than as a null dereference.
     *
     * @return one (address, status, response code) tuple per row, in the order the rows were minted
     */
    private List<Tuple> rowsAsTheyStand() {
        return ledger.values().stream()
                .map(row -> tuple(row.emailAddress(), row.status(), row.responseCode()))
                .toList();
    }

    private double notificationCount(final NotificationStatus status, final String responseCode) {
        final Counter counter = registry.find(GenerationMetrics.NOTIFICATIONS)
                .tag(GenerationMetrics.STATUS_TAG, code(status))
                .tag(GenerationMetrics.RESPONSE_CODE_TAG, responseCode)
                .counter();
        return counter == null ? ABSENT : counter.count();
    }

    private double batchCount(final BatchStatus outcome) {
        final Counter counter = registry.find(GenerationMetrics.BATCHES)
                .tag(GenerationMetrics.OUTCOME_TAG, code(outcome))
                .counter();
        return counter == null ? ABSENT : counter.count();
    }

    /** The bounded label a state is counted under, derived as {@code GenerationMetrics} does. */
    private static String code(final Enum<?> state) {
        return state.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Every line a log index would keep, at or above the given level, exception text included.
     *
     * <p>The rendering rather than the message alone, because the commonest way a recipient escapes
     * into an index is inside the text of an exception somebody else wrote.
     *
     * @param log   what the service wrote while the case ran
     * @param level the lowest level a reader outside this pod ever sees
     * @return what each of those events would put in front of that reader
     */
    private static List<String> atOrAbove(final CapturedLog log, final Level level) {
        return log.events().stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(level))
                .map(RegisterNotifierServiceTest::rendered)
                .toList();
    }

    private static String rendered(final ILoggingEvent event) {
        final IThrowableProxy thrown = event.getThrowableProxy();
        return thrown == null
                ? event.getFormattedMessage()
                : event.getFormattedMessage() + System.lineSeparator()
                        + ThrowableProxyUtil.asString(thrown);
    }

    /**
     * The rows, all of them minted before anything is asked of notificationnotify.
     */
    @Nested
    @DisplayName("the rows, minted before any POST")
    class MintingTheRowsFirst {

        @Test
        void every_recipients_row_should_be_minted_before_the_first_post_is_made() {
            notifyBatch();

            softly.assertThat(sequence)
                    .as("an identity used in a call and written down afterwards is an e-mail this "
                            + "service cannot show it asked for, so all three rows exist before "
                            + "notificationnotify is asked anything")
                    .containsExactly(MINTED, MINTED, MINTED, POSTED, POSTED, POSTED);
        }

        @Test
        void one_row_should_be_minted_for_each_distinct_recipient_of_the_batch() {
            notifyBatch();

            softly.assertThat(mintedAddresses())
                    .as("two records and four recipients, one team on both: the union is three "
                            + "addresses, so the same team is told once and the row that says so "
                            + "is the evidence it was told")
                    .containsExactly(ADDRESS_A, ADDRESS_B, ADDRESS_C);
        }

        @Test
        void each_row_should_be_minted_pending_under_its_own_identity() {
            notifyBatch();

            softly.assertThat(minted)
                    .as("PENDING is written before the POST rather than after it, so an attempt "
                            + "nothing answered is a row that says so rather than an absence")
                    .hasSize(3)
                    .allSatisfy(row -> assertThat(row.status())
                            .isEqualTo(NotificationStatus.PENDING));
            softly.assertThat(mintedIds())
                    .as("one identity per recipient, because notificationnotify keys its aggregate "
                            + "on it: two teams sharing one would be two e-mails against one "
                            + "aggregate")
                    .hasSize(3)
                    .doesNotHaveDuplicates()
                    .doesNotContainNull();
        }

        @Test
        void each_row_should_name_the_batch_the_recipient_and_the_resolved_template() {
            notifyBatch();

            softly.assertThat(minted)
                    .as("the row is what was sent and to whom: the batch it belongs to, the "
                            + "address and name it goes to, and the cr_standard id resolved once "
                            + "at wiring rather than per e-mail, which is the cause defect fix P9 "
                            + "removed")
                    .extracting(RegisterNotification::batchId, RegisterNotification::emailAddress,
                            RegisterNotification::recipientName,
                            RegisterNotification::templateName, RegisterNotification::templateId)
                    .containsExactly(
                            tuple(BATCH_ID, ADDRESS_A, YOT_A,
                                    RegisterNotifierService.TEMPLATE_NAME, TEMPLATE_ID),
                            tuple(BATCH_ID, ADDRESS_B, YOT_B,
                                    RegisterNotifierService.TEMPLATE_NAME, TEMPLATE_ID),
                            tuple(BATCH_ID, ADDRESS_C, YOT_C,
                                    RegisterNotifierService.TEMPLATE_NAME, TEMPLATE_ID));
        }
    }

    /**
     * What each recipient's own attempt is settled as, and that the next one is asked either way.
     */
    @Nested
    @DisplayName("each recipient, settled on its own")
    class SettlingEachRecipient {

        @Test
        void every_post_should_carry_the_rows_identity_and_the_batchs_document() {
            notifyBatch();

            softly.assertThat(posted)
                    .as("the POST is made under the identity the row was minted with, because that "
                            + "identity is the path parameter and the key of notificationnotify's "
                            + "own aggregate")
                    .extracting(RegisterNotification::notificationId)
                    .containsExactlyElementsOf(mintedIds());
            softly.assertThat(postedDocuments)
                    .as("the register travels by reference: every e-mail of the batch attaches the "
                            + "one rendered document by file-service id, so a register about "
                            + "children is never carried through this service twice")
                    .containsExactly(DOCUMENT_FILE_ID, DOCUMENT_FILE_ID, DOCUMENT_FILE_ID);
        }

        @Test
        void a_recipient_notificationnotify_accepted_should_be_settled_accepted_with_the_status() {
            notifyBatch();

            softly.assertThat(settled)
                    .as("202 is the only success the contract admits, and the status line is kept "
                            + "because accepted and answered are different facts")
                    .hasSize(3)
                    .allSatisfy(row -> assertThat(row)
                            .extracting(RegisterNotification::status,
                                    RegisterNotification::responseCode,
                                    RegisterNotification::sentAt)
                            .containsExactly(NotificationStatus.ACCEPTED, ACCEPTED, SETTLED_AT));
        }

        @Test
        void a_recipient_notificationnotify_refused_should_be_settled_failed_with_the_status() {
            refuses(ADDRESS_B, REFUSED);

            notifyBatch();

            softly.assertThat(rowsAsTheyStand())
                    .as("progression logged a line and moved on, so a refused e-mail and an e-mail "
                            + "nobody tried looked alike; the row carries FAILED and the status "
                            + "that made it one, which is defect fix P9's run-time half")
                    .contains(tuple(ADDRESS_B, NotificationStatus.FAILED, REFUSED));
        }

        @Test
        void a_recipient_nothing_answered_for_should_be_settled_failed_with_no_status() {
            answersNothingFor(ADDRESS_B);

            notifyBatch();

            softly.assertThat(rowsAsTheyStand())
                    .as("a connection that never produced a status line has none to record, and a "
                            + "row carrying an invented one would say an attempt was answered when "
                            + "nothing answered at all")
                    .contains(tuple(ADDRESS_B, NotificationStatus.FAILED, null));
        }

        @Test
        void a_refusal_should_not_stop_the_teams_after_it_being_told() {
            refuses(ADDRESS_A, REFUSED);

            notifyBatch();

            softly.assertThat(postedAddresses())
                    .as("one team's refusal says nothing about another team's e-mail, and an "
                            + "exception that ended the batch would turn one bad address into a "
                            + "night's silence for a whole court centre")
                    .containsExactly(ADDRESS_A, ADDRESS_B, ADDRESS_C);
            softly.assertThat(rowsAsTheyStand())
                    .as("and each team asked after the refusal is settled on its own answer")
                    .contains(tuple(ADDRESS_B, NotificationStatus.ACCEPTED, ACCEPTED),
                            tuple(ADDRESS_C, NotificationStatus.ACCEPTED, ACCEPTED));
        }
    }

    /**
     * The three terminal states a generated batch can reach, and the tally each is read from.
     */
    @Nested
    @DisplayName("the batch, settled on the tally")
    class SettlingTheBatch {

        @Test
        void a_batch_every_recipient_accepted_should_be_notified() {
            final NotificationSummary summary = notifyBatch();

            softly.assertThat(summary)
                    .as("three teams, three 202s: the batch has told everybody who subscribes")
                    .isEqualTo(new NotificationSummary(3, 0, BatchStatus.NOTIFIED));
            softly.assertThat(settlements)
                    .as("and the batch row is settled once, on that tally and on nothing else")
                    .containsExactly(new BatchSettlement(BATCH_ID,
                            new NotificationSummary(3, 0, BatchStatus.NOTIFIED)));
            softly.assertThat(batchCount(BatchStatus.NOTIFIED))
                    .as("the terminal state is counted where every other terminal state is, so a "
                            + "night is one sum over four endings rather than four questions")
                    .isEqualTo(1);
        }

        @Test
        void a_batch_with_a_refused_recipient_should_be_partially_notified() {
            refuses(ADDRESS_B, REFUSED);

            final NotificationSummary summary = notifyBatch();

            softly.assertThat(summary)
                    .as("a batch with any failed recipient ends PARTIALLY_NOTIFIED rather than "
                            + "NOTIFIED, so it does not report the state it would have reported "
                            + "had everybody been e-mailed (defect fix P9)")
                    .isEqualTo(new NotificationSummary(2, 1, BatchStatus.PARTIALLY_NOTIFIED));
            softly.assertThat(batchCount(BatchStatus.PARTIALLY_NOTIFIED))
                    .as("counted under its own outcome, because a batch that told some teams and "
                            + "not others is not a batch that told everybody")
                    .isEqualTo(1);
            softly.assertThat(batchCount(BatchStatus.NOTIFIED))
                    .as("and the ending it did not reach has no reading at all")
                    .isEqualTo(ABSENT);
        }

        @Test
        void a_batch_with_no_recipients_ends_notified_nobody_not_generated_forever() {
            when(store.batched(BATCH_ID)).thenReturn(List.of(registerRecord(null)));

            final NotificationSummary summary = notifyBatch();

            softly.assertThat(summary)
                    .as("defect fix P1: progression answers an empty recipient list with an event "
                            + "no descriptor subscribes to, and the batch sticks at GENERATED for "
                            + "ever, visible to nobody; NOTIFIED_NOBODY says the document was "
                            + "rendered and there was nobody to send it to, and it is terminal")
                    .isEqualTo(new NotificationSummary(0, 0, BatchStatus.NOTIFIED_NOBODY));
            softly.assertThat(settlements)
                    .as("and it is written to the batch row rather than left to be inferred from a "
                            + "batch nothing ever moved")
                    .containsExactly(new BatchSettlement(BATCH_ID,
                            new NotificationSummary(0, 0, BatchStatus.NOTIFIED_NOBODY)));
            softly.assertThat(batchCount(BatchStatus.NOTIFIED_NOBODY))
                    .as("counted rather than inferred from an absence, which is what makes the "
                            + "state alertable at all")
                    .isEqualTo(1);
            softly.assertThat(sequence)
                    .as("nothing is posted and no row is minted, because there is nobody to post "
                            + "to and no attempt to record")
                    .isEmpty();
            verify(notifications, never()).insert(any());
            verify(notifier, never()).send(any(), any(), any());
        }
    }

    /**
     * What a resend re-requests, and what it leaves alone.
     */
    @Nested
    @DisplayName("the resend, of what failed and nothing else")
    class ResendingOnlyWhatFailed {

        private RegisterNotification told;
        private RegisterNotification owed;
        private RegisterNotification alsoTold;

        /**
         * The batch as a night that told two of its three teams left it.
         *
         * <p>One row per recipient of the union, because that is the shape a resend is about: the
         * question it answers is which of the teams this batch is addressed to are still owed an
         * e-mail, and a batch holding fewer rows than it has recipients is the separate question
         * {@code RecoveringAnUnsettledRow} asks.
         */
        @BeforeEach
        void seedOneToldTeamAndOneOwedTeam() {
            when(batches.findById(BATCH_ID)).thenReturn(Optional.of(partiallyNotified()));
            told = seeded(YOT_A, ADDRESS_A, NotificationStatus.ACCEPTED, ACCEPTED);
            owed = seeded(YOT_B, ADDRESS_B, NotificationStatus.FAILED, REFUSED);
            alsoTold = seeded(YOT_C, ADDRESS_C, NotificationStatus.ACCEPTED, ACCEPTED);
        }

        @Test
        void a_resend_should_re_request_only_the_rows_that_failed() {
            resend();

            softly.assertThat(postedAddresses())
                    .as("the teams that were told are not told twice: a resend that re-requested "
                            + "the accepted rows would send a second register to a Youth Offending "
                            + "Team that already has one")
                    .containsExactly(ADDRESS_B);
        }

        @Test
        void a_resend_should_reuse_the_notification_id_the_row_was_minted_with() {
            resend();

            softly.assertThat(posted)
                    .as("notificationnotify keys its Notification aggregate on the id in the path, "
                            + "so a second POST under the id the row already holds reaches the "
                            + "attempt it is retrying; a fresh one would send a second e-mail "
                            + "(research §10)")
                    .extracting(RegisterNotification::notificationId)
                    .containsExactly(owed.notificationId());
        }

        @Test
        void a_resend_should_settle_the_row_it_already_holds_rather_than_mint_a_new_one() {
            resend();

            softly.assertThat(rowsAsTheyStand())
                    .as("the failed row is where the attempt is recorded, so the resend settles it "
                            + "under its own identity instead of starting a second row for the "
                            + "same team - which the (batch, address) key refuses anyway")
                    .contains(tuple(ADDRESS_B, NotificationStatus.ACCEPTED, ACCEPTED));
            softly.assertThat(ledger.keySet())
                    .as("and the table still holds the three rows it held, one per distinct address")
                    .containsExactly(told.notificationId(), owed.notificationId(),
                            alsoTold.notificationId());
            verify(notifications, never()).insert(any());
        }

        @Test
        void a_resend_whose_last_failure_is_accepted_should_settle_the_batch_notified() {
            final NotificationSummary summary = resend();

            softly.assertThat(summary)
                    .as("the tally is over the whole batch as it now stands and not over the "
                            + "resend: the teams that were already told still count as told, so "
                            + "the batch reaches NOTIFIED when the last failure is accepted")
                    .isEqualTo(new NotificationSummary(3, 0, BatchStatus.NOTIFIED));
            softly.assertThat(settlements)
                    .as("and that is what the batch row is settled on")
                    .containsExactly(new BatchSettlement(BATCH_ID,
                            new NotificationSummary(3, 0, BatchStatus.NOTIFIED)));
        }

        @Test
        void a_resend_that_is_refused_again_should_leave_the_batch_partially_notified() {
            refuses(ADDRESS_B, REFUSED);

            final NotificationSummary summary = resend();

            softly.assertThat(summary)
                    .as("a second refusal is not a worse state, it is the same one: the row stays "
                            + "resendable under its own identity and the batch stays "
                            + "PARTIALLY_NOTIFIED")
                    .isEqualTo(new NotificationSummary(2, 1, BatchStatus.PARTIALLY_NOTIFIED));
        }
    }

    /**
     * The refusal that may answer differently, asked again inside the run's own budget.
     *
     * <p>The taxonomy is the shared {@code adapter/http/RetryPolicy}'s and the counting is this
     * service's, exactly as the generation leg splits them: the client classifies one attempt and
     * knows nothing about what is left of the budget, and the object that holds the budget decides
     * whether there is room for another. A 503, a 429 or a read timeout for one recipient is a
     * moment in notificationnotify's night and not a verdict about the e-mail, and settling the row
     * FAILED on the first of them turns it into one - a whole batch PARTIALLY_NOTIFIED and an
     * operator resend, for something that would have been accepted a second later.
     *
     * <p>The retry is safe because it is the same POST: the notification id is the row's own, so
     * notificationnotify's aggregate is reached rather than a second e-mail asked for (research
     * §10).
     */
    @Nested
    @DisplayName("the transient refusal, asked again")
    class RetryingWhatMayAnswerDifferently {

        @Test
        void a_transient_refusal_should_be_asked_again_under_the_same_identity_and_be_accepted() {
            refusesOnceThenAccepts(ADDRESS_B, UNAVAILABLE);

            final NotificationSummary summary = notifyBatch();

            softly.assertThat(postedAddresses())
                    .as("a 503 says notificationnotify could not take the command now, not that "
                            + "this team is not to be told; the second POST is the same command "
                            + "under the same identity")
                    .containsExactly(ADDRESS_A, ADDRESS_B, ADDRESS_B, ADDRESS_C);
            softly.assertThat(posted.stream()
                            .filter(row -> ADDRESS_B.equals(row.emailAddress()))
                            .map(RegisterNotification::notificationId)
                            .distinct())
                    .as("and it is one identity across both attempts, because a fresh one would "
                            + "reach a fresh aggregate and send a second register")
                    .hasSize(1);
            softly.assertThat(settled)
                    .as("the row records the attempt that was accepted and how many it took")
                    .extracting(RegisterNotification::emailAddress, RegisterNotification::status,
                            RegisterNotification::responseCode, RegisterNotification::attempts)
                    .contains(tuple(ADDRESS_B, NotificationStatus.ACCEPTED, ACCEPTED, 2));
            softly.assertThat(summary)
                    .as("so one unlucky moment does not cost a court centre its PARTIALLY_NOTIFIED "
                            + "night and an operator resend")
                    .isEqualTo(new NotificationSummary(3, 0, BatchStatus.NOTIFIED));
        }

        @Test
        void a_non_transient_refusal_should_not_be_asked_again() {
            refuses(ADDRESS_B, REFUSED);

            notifyBatch();

            softly.assertThat(postedAddresses())
                    .as("the command was understood and declined, and the same command under the "
                            + "same identity will be declined again; waiting to prove it would cost "
                            + "the run its budget for the teams after it")
                    .containsExactly(ADDRESS_A, ADDRESS_B, ADDRESS_C);
            softly.assertThat(waited)
                    .as("and nothing is waited for a refusal no wait can change")
                    .isEmpty();
        }

        @Test
        void a_transient_refusal_that_never_clears_should_be_failed_with_the_last_status() {
            refusesTransiently(ADDRESS_B, UNAVAILABLE);

            final NotificationSummary summary = notifyBatch();

            softly.assertThat(postedAddresses())
                    .as("the attempt budget is the shared one, and it is spent per recipient: "
                            + "three attempts for the team that could not be told and one each for "
                            + "the teams that could")
                    .containsExactly(ADDRESS_A, ADDRESS_B, ADDRESS_B, ADDRESS_B, ADDRESS_C);
            softly.assertThat(settled)
                    .as("and the row carries the status that made the last attempt a refusal, so "
                            + "an exhausted budget is told from a route that has stopped reaching "
                            + "the command endpoint")
                    .extracting(RegisterNotification::emailAddress, RegisterNotification::status,
                            RegisterNotification::responseCode, RegisterNotification::attempts)
                    .contains(tuple(ADDRESS_B, NotificationStatus.FAILED, UNAVAILABLE, 3));
            softly.assertThat(summary)
                    .as("a team that could not be told in three attempts is a team that was not "
                            + "told, which is what PARTIALLY_NOTIFIED says")
                    .isEqualTo(new NotificationSummary(2, 1, BatchStatus.PARTIALLY_NOTIFIED));
        }

        @Test
        void the_wait_between_two_attempts_should_be_the_shared_back_off() {
            refusesTransiently(ADDRESS_B, UNAVAILABLE);

            notifyBatch();

            softly.assertThat(waited)
                    .as("initial-backoff doubling per attempt and bounded by max-backoff, from the "
                            + "one policy every client of this service holds: a client with a "
                            + "back-off of its own is what defect fix C3 removed")
                    .containsExactly(INITIAL_BACKOFF, MAX_BACKOFF);
        }

        @Test
        void a_resend_should_spend_the_same_budget_as_a_first_notification() {
            when(batches.findById(BATCH_ID)).thenReturn(Optional.of(partiallyNotified()));
            seeded(YOT_A, ADDRESS_A, NotificationStatus.ACCEPTED, ACCEPTED);
            seeded(YOT_B, ADDRESS_B, NotificationStatus.FAILED, REFUSED);
            seeded(YOT_C, ADDRESS_C, NotificationStatus.ACCEPTED, ACCEPTED);
            refusesOnceThenAccepts(ADDRESS_B, UNAVAILABLE);

            final NotificationSummary summary = resend();

            softly.assertThat(postedAddresses())
                    .as("a resend is the same POST made again, so it is asked again on the same "
                            + "terms rather than getting one attempt where a notification gets "
                            + "three")
                    .containsExactly(ADDRESS_B, ADDRESS_B);
            softly.assertThat(summary)
                    .as("and the batch reaches NOTIFIED on the attempt that was accepted")
                    .isEqualTo(new NotificationSummary(3, 0, BatchStatus.NOTIFIED));
        }
    }

    /**
     * The row an interrupted run left behind, and how it is recovered.
     *
     * <p>A run that stopped between the 202 and the ACCEPTED write - or anywhere after the mint -
     * leaves a row PENDING. The redelivered {@code document-available} is refused by
     * {@code markGenerated}'s compare-and-set, so no duplicate e-mail is sent, but that refusal
     * recovers nothing either: unless PENDING counts as unsettled the team is never re-requested, the
     * batch is settled PARTIALLY_NOTIFIED against a team that can never be told, and a second
     * {@code notify} would insert a second row for every address the {@code (batch_id,
     * email_address)} key refuses.
     *
     * <p>So both halves are held down here: a PENDING row is re-requested under the identity it
     * already holds, and {@code notify} over a batch that already holds rows mints only the addresses
     * that have none.
     *
     * <p><strong>And the row an interrupted run never wrote at all is the third half.</strong> A run
     * can stop before the mint as easily as after it - between {@code markGenerated} and the first
     * insert, or between the second insert and the third - so a GENERATED batch can hold no rows or
     * only some of them while its records union to teams nobody has been told about. A resend that
     * read only the rows found nothing to re-request and settled the batch NOTIFIED_NOBODY, a
     * terminal state that says the document was rendered and there was nobody to send it to: the
     * P1 fix's own words, applied to a batch that had somebody all along, and terminal, so no
     * later resend could revisit it. The owed set is therefore derived from the recipients first
     * and the missing rows are minted before anything is settled, which leaves NOTIFIED_NOBODY
     * reachable only where the union itself is empty.
     */
    @Nested
    @DisplayName("the row an interrupted run left PENDING")
    class RecoveringAnUnsettledRow {

        @Test
        void a_row_left_pending_should_be_re_requested_under_the_identity_it_was_minted_with() {
            seeded(YOT_A, ADDRESS_A, NotificationStatus.ACCEPTED, ACCEPTED);
            final RegisterNotification owed = abandoned(YOT_B, ADDRESS_B);
            seeded(YOT_C, ADDRESS_C, NotificationStatus.ACCEPTED, ACCEPTED);

            final NotificationSummary summary = resend();

            softly.assertThat(posted)
                    .as("a row that reached no verdict is owed its e-mail exactly as a refused one "
                            + "is, and it is asked for under the identity it was minted with, "
                            + "because that identity is what makes the second POST reach the "
                            + "attempt it is retrying rather than send a second e-mail")
                    .extracting(RegisterNotification::notificationId)
                    .containsExactly(owed.notificationId());
            softly.assertThat(rowsAsTheyStand())
                    .as("and the row it already holds is where that attempt is settled")
                    .contains(tuple(ADDRESS_B, NotificationStatus.ACCEPTED, ACCEPTED));
            softly.assertThat(summary)
                    .as("so a batch parked with an unsettled row can still reach NOTIFIED, rather "
                            + "than standing at a state no re-request would ever revisit")
                    .isEqualTo(new NotificationSummary(3, 0, BatchStatus.NOTIFIED));
        }

        @Test
        void a_notify_over_a_batch_that_already_holds_rows_should_not_mint_a_second_row() {
            seeded(YOT_A, ADDRESS_A, NotificationStatus.ACCEPTED, ACCEPTED);
            abandoned(YOT_B, ADDRESS_B);

            final NotificationSummary summary = notifyBatch();

            softly.assertThat(mintedAddresses())
                    .as("UNIQUE (batch_id, email_address) refuses a second row for an address this "
                            + "batch already holds, so a notify that minted for every recipient "
                            + "could never be re-run; only the address with no row of its own is "
                            + "minted")
                    .containsExactly(ADDRESS_C);
            softly.assertThat(postedAddresses())
                    .as("the team that was told is not told twice, and the two that were not are "
                            + "asked for")
                    .containsExactly(ADDRESS_B, ADDRESS_C);
            softly.assertThat(summary)
                    .as("one row per distinct address either way, so the tally is over three "
                            + "recipients and not five")
                    .isEqualTo(new NotificationSummary(3, 0, BatchStatus.NOTIFIED));
        }

        @Test
        void a_resend_for_a_batch_that_holds_no_rows_should_mint_them_rather_than_tell_nobody() {
            final NotificationSummary summary = resend();

            softly.assertThat(mintedAddresses())
                    .as("the batch's records union to three Youth Offending Teams and the run that "
                            + "was supposed to mint their rows stopped before it wrote any, so the "
                            + "resend owes all three an e-mail and mints the rows that say so "
                            + "before anything is asked of notificationnotify")
                    .containsExactly(ADDRESS_A, ADDRESS_B, ADDRESS_C);
            softly.assertThat(postedAddresses())
                    .as("and each of them is then asked for under the identity its own row "
                            + "was just minted with")
                    .containsExactly(ADDRESS_A, ADDRESS_B, ADDRESS_C);
            softly.assertThat(summary)
                    .as("NOTIFIED_NOBODY says the document was rendered and there was nobody to "
                            + "send it to, and it is terminal; settling a batch whose records "
                            + "union to three teams in it would end the night claiming there was "
                            + "nobody to tell, where no later resend could revisit it")
                    .isEqualTo(new NotificationSummary(3, 0, BatchStatus.NOTIFIED));
            softly.assertThat(settlements)
                    .as("so the only state the batch row is settled in is the one its recipients "
                            + "produce")
                    .containsExactly(new BatchSettlement(BATCH_ID,
                            new NotificationSummary(3, 0, BatchStatus.NOTIFIED)));
        }

        /**
         * The (batch, address) key lost to another mechanism, which is a night rather than a bug.
         *
         * <p>An operator's resend and the outcome sink can reach one batch at the same time, and
         * both derive the same owed set from the same records: whichever gets to the insert second
         * is refused by {@code UNIQUE (batch_id, email_address)}. The row that won is the row
         * notificationnotify's aggregate is keyed by, so the loser reads it back and posts under
         * that identity - a run that let the refusal out instead would leave a team untold with the
         * whole batch's remaining recipients behind it, and one that minted a fresh identity would
         * send the same children's register twice.
         */
        @Test
        void a_row_another_mechanism_minted_first_should_be_read_back_and_posted_for_once() {
            final RegisterNotification winner = racedTo(YOT_B, ADDRESS_B);

            final NotificationSummary summary = notifyBatch();

            softly.assertThat(posted)
                    .as("the POST goes out under the identity the row that won holds, because that "
                            + "identity is the path parameter and the key of notificationnotify's "
                            + "own aggregate; the identity this run minted was refused and is not "
                            + "an e-mail anybody can be shown to have asked for")
                    .extracting(RegisterNotification::notificationId)
                    .contains(winner.notificationId());
            softly.assertThat(postedAddresses())
                    .as("and the team is asked for exactly once, with the teams after it still "
                            + "told: losing a key is two mechanisms doing the same work, not a "
                            + "reason to end the batch")
                    .containsExactly(ADDRESS_A, ADDRESS_B, ADDRESS_C);
            softly.assertThat(summary)
                    .as("so the batch reaches NOTIFIED over its three recipients")
                    .isEqualTo(new NotificationSummary(3, 0, BatchStatus.NOTIFIED));
        }

        @Test
        void a_resend_for_a_batch_that_holds_some_of_its_rows_should_mint_the_missing_ones() {
            seeded(YOT_A, ADDRESS_A, NotificationStatus.ACCEPTED, ACCEPTED);

            final NotificationSummary summary = resend();

            softly.assertThat(mintedAddresses())
                    .as("a run that stopped between two inserts leaves a batch whose rows are "
                            + "fewer than its recipients, and the teams with no row of their own "
                            + "are owed an e-mail exactly as a refused team is: their rows are "
                            + "minted here, and the team that has one keeps it")
                    .containsExactly(ADDRESS_B, ADDRESS_C);
            softly.assertThat(postedAddresses())
                    .as("the team that was told is not told twice, and the two whose rows were "
                            + "never written are asked for")
                    .containsExactly(ADDRESS_B, ADDRESS_C);
            softly.assertThat(summary)
                    .as("and the batch reaches NOTIFIED over all three of its recipients rather "
                            + "than over the one row it happened to hold")
                    .isEqualTo(new NotificationSummary(3, 0, BatchStatus.NOTIFIED));
        }
    }

    /**
     * The one counter this class moves per recipient, and the two labels it is read by.
     */
    @Nested
    @DisplayName("courtregister_notifications_total")
    class TheNotificationsCounter {

        @Test
        void an_accepted_recipient_should_be_counted_with_the_status_that_accepted_it() {
            notifyBatch();

            softly.assertThat(
                    notificationCount(NotificationStatus.ACCEPTED, String.valueOf(ACCEPTED)))
                    .as("three teams accepted on one series, because the question the counter "
                            + "answers is how many e-mails a night asked for and got")
                    .isEqualTo(3);
        }

        @Test
        void a_refused_recipient_should_be_its_own_series() {
            refuses(ADDRESS_B, REFUSED);

            notifyBatch();

            softly.assertThat(
                    notificationCount(NotificationStatus.FAILED, String.valueOf(REFUSED)))
                    .as("the refusal is read by the status that made it one, so a route that has "
                            + "stopped reaching the command endpoint is told from a template that "
                            + "no longer exists")
                    .isEqualTo(1);
            softly.assertThat(
                    notificationCount(NotificationStatus.ACCEPTED, String.valueOf(ACCEPTED)))
                    .as("and the two teams that were told are still counted as told")
                    .isEqualTo(2);
        }

        @Test
        void a_recipient_nothing_answered_for_should_be_counted_under_none() {
            answersNothingFor(ADDRESS_B);

            notifyBatch();

            softly.assertThat(notificationCount(NotificationStatus.FAILED,
                            GenerationMetrics.NO_RESPONSE))
                    .as("a bounded code rather than an absent label: an attempt that never got a "
                            + "status line is the failure most worth seeing, and an absent label "
                            + "would make it the one shape no query matches")
                    .isEqualTo(1);
        }
    }

    /**
     * The never: a recipient's address or name somewhere that outlives the run.
     */
    @Nested
    @DisplayName("what a log index and a metric label may keep")
    class Privacy {

        @Test
        void no_line_at_info_or_above_should_carry_a_recipient_or_a_defendant() {
            when(store.batched(BATCH_ID)).thenReturn(List.of(registerRecord(List.of(
                    recipient(PersonalDataMarkers.RECIPIENT_ORGANISATION,
                            PersonalDataMarkers.RECIPIENT_EMAIL),
                    recipient(YOT_B, ADDRESS_B)))));
            refuses(ADDRESS_B, REFUSED);

            try (CapturedLog log = CapturedLog.capturing(RegisterNotifierService.class)) {
                notifyBatch();

                softly.assertThat(atOrAbove(log, Level.INFO))
                        .as("every defendant on a court register is a child and the recipients are "
                                + "a protected list; the service's own lines carry notification "
                                + "ids, batch ids and bounded codes, and the addresses and names "
                                + "live in the notification rows, which are the only place that "
                                + "may hold them")
                        .allSatisfy(line -> assertThat(line).doesNotContain(
                                PersonalDataMarkers.RECIPIENT_ORGANISATION,
                                PersonalDataMarkers.RECIPIENT_EMAIL,
                                DEFENDANT,
                                ADDRESS_B,
                                YOT_B));
            }
        }

        @Test
        void no_recipient_series_should_carry_a_label_beyond_the_two_bounded_ones() {
            refuses(ADDRESS_B, REFUSED);

            notifyBatch();

            softly.assertThat(registry.find(GenerationMetrics.NOTIFICATIONS).counters())
                    .as("a metric label outlives a log line, so the recipient series are the two "
                            + "bounded dimensions and never the address a night was sent to")
                    .isNotEmpty()
                    .allSatisfy(counter -> assertThat(counter.getId().getTags())
                            .extracting("key")
                            .containsExactlyInAnyOrder(GenerationMetrics.STATUS_TAG,
                                    GenerationMetrics.RESPONSE_CODE_TAG));
        }
    }

    /** What the batch was settled on: the identity, and the tally it was settled from. */
    private record BatchSettlement(UUID batchId, NotificationSummary summary) {
    }
}
