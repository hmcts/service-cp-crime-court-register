package uk.gov.hmcts.cp.courtregister.pipeline;

import tools.jackson.databind.JsonNode;

/**
 * Which day the register says the hearing was held on.
 *
 * <p>Ports {@code NowsHelper/service/RegisterFragmentService.js:46-55}. The rule has two steps and
 * the legacy writes them in this order: if the hearing lists sitting days, look for one whose London
 * day is the latest ordered date and carry <em>that day's time</em>; otherwise carry the ordered
 * date, which has no time of its own and is therefore midnight. A hearing that lists no sitting days
 * at all gets no hearing date — the legacy's {@code if} has no {@code else}, so the function returns
 * {@code undefined}, and an absent hearing date is what the register then carries.
 *
 * <p><strong>An empty list is not an absent one.</strong> {@code if (hearingObj.hearingDays)} is
 * entered when the field is {@code []}, because an empty array is truthy in JavaScript — and the
 * {@code SetCourtRegister} fixture has exactly that. Reading "empty" as "absent" would take the
 * other branch and give the register no date at all.
 *
 * <p><strong>Two places the legacy stamps the wall clock, and this does not.</strong>
 * {@code moment.tz(undefined, zone)} is the current time, so the legacy answers a hearing with no
 * ordered dates — and a sitting day recorded without a {@code sittingDay} — with whenever the
 * function happened to run. Neither is reproduced: the transformation is pure and has no clock
 * (constitution Principle V), so a hearing with nothing to date by has no hearing date, and a
 * sitting day that names no day matches nothing.
 */
// PMD.OnlyOneReturn: the two absences below are distinct legacy branches — no sitting days at all,
// and nothing to match them against — and collapsing them into one exit would lose which of the
// legacy's two paths a hearing took.
@SuppressWarnings("PMD.OnlyOneReturn")
final class HearingDates {

    /** The hearing's own list of sitting days. */
    private static final String HEARING_DAYS = "hearingDays";

    /** The day one of those sittings was held on. */
    private static final String SITTING_DAY = "sittingDay";

    private HearingDates() {
    }

    /**
     * The hearing date a register covering these results carries.
     *
     * @param orderedDate the latest date any of the gathered results was ordered; may be
     *                    {@code null} when the hearing gathered none
     * @param hearing     the hearing payload, exactly as the producer sent it
     * @param dates       the register's date handling
     * @return the hearing date as an instant, or {@code null} where the legacy answers nothing
     * @throws uk.gov.hmcts.cp.courtregister.domain.TransformationFailedException if a sitting day or
     *     the ordered date cannot be read as a date
     */
    /* default */ static String resolve(
            final String orderedDate, final JsonNode hearing, final Dates dates) {

        final JsonNode hearingDays = hearing == null ? null : hearing.get(HEARING_DAYS);
        if (hearingDays == null || hearingDays.isNull()) {
            return null;
        }
        if (orderedDate == null) {
            // Nothing was ordered, so there is no day for the sittings to be matched against and
            // nothing to fall back to. The legacy formats `undefined` here, which is the clock.
            return null;
        }
        final String sittingDay = sittingDayFalling(hearingDays, orderedDate, dates);
        return dates.dateTime(sittingDay == null ? orderedDate : sittingDay);
    }

    /**
     * The first sitting day whose London day is the ordered date, as {@code find} answers it.
     *
     * @param hearingDays the hearing's sitting days
     * @param orderedDate the ordered date to match against
     * @param dates       the register's date handling
     * @return that sitting day, or {@code null} where none falls on the ordered date
     */
    private static String sittingDayFalling(
            final JsonNode hearingDays, final String orderedDate, final Dates dates) {

        for (final JsonNode hearingDay : hearingDays) {
            final JsonNode sittingDay = hearingDay.get(SITTING_DAY);
            if (sittingDay != null
                    && !sittingDay.isNull()
                    && orderedDate.equals(dates.localDate(sittingDay.stringValue()))) {
                return sittingDay.stringValue();
            }
        }
        return null;
    }
}
