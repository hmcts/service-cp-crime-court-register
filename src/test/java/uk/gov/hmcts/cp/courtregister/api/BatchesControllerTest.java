package uk.gov.hmcts.cp.courtregister.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.cp.courtregister.application.BatchListing;
import uk.gov.hmcts.cp.courtregister.application.BatchListingService;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.NotificationStatus;
import uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException;

/**
 * What {@code GET /operations/batches?date=D} answers, and what it refuses.
 *
 * <p>The listing itself has a suite of its own; this one is about the mapping and the refusals -
 * that the shape is data-model §2, that the one argument is refused by name and never by value,
 * and that a store which will not answer is a {@code 503} carrying a bounded code rather than a
 * stack trace.
 *
 * <p>Both estate filters are off. The authorisation and audit conditions have suites of their own.
 */
@WebMvcTest(controllers = BatchesController.class, properties = {
    "authz.http.enabled=false",
    "audit.http.enabled=false",
    "cp.audit.enabled=false",
})
@DisplayName("the batch listing endpoint")
class BatchesControllerTest {

    /** The path, written out so a change to it fails here rather than silently 404ing. */
    private static final String PATH = "/operations/batches";

    /** The register date a support call is about. */
    private static final String TYPED_DATE = "2026-09-04";

    private static final LocalDate DATE = LocalDate.parse(TYPED_DATE);

    /** A value nothing else in this repository produces, so a leak can only be this one. */
    private static final String NOT_A_DATE = "ZQX7NOTADATE";

    private static final UUID BATCH = UUID.fromString("11111111-2222-4333-8444-555555555555");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BatchListingService listings;

    @Nested
    @DisplayName("a date that was asked for")
    class ADate {

        @Test
        void it_should_answer_the_listing_shape() throws Exception {
            when(listings.batchesOn(DATE)).thenReturn(List.of(new BatchListing(BATCH,
                    "Lewes Youth Court", BatchStatus.NOTIFIED, 12,
                    List.of(new BatchListing.Recipient("j***@yot.example.gov.uk",
                            NotificationStatus.ACCEPTED)))));

            mvc.perform(get(PATH).param("date", TYPED_DATE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.date").value(TYPED_DATE))
                    .andExpect(jsonPath("$.batches.length()").value(1))
                    .andExpect(jsonPath("$.batches[0].batchId").value(BATCH.toString()))
                    .andExpect(jsonPath("$.batches[0].courtHouse").value("Lewes Youth Court"))
                    .andExpect(jsonPath("$.batches[0].state").value("NOTIFIED"))
                    .andExpect(jsonPath("$.batches[0].records").value(12))
                    .andExpect(jsonPath("$.batches[0].recipients[0].address")
                            .value("j***@yot.example.gov.uk"))
                    .andExpect(jsonPath("$.batches[0].recipients[0].outcome").value("ACCEPTED"));
        }

        @Test
        void a_batch_with_no_court_house_should_omit_the_key() throws Exception {
            when(listings.batchesOn(DATE)).thenReturn(List.of(new BatchListing(BATCH, null,
                    BatchStatus.PENDING, 0, List.of())));

            mvc.perform(get(PATH).param("date", TYPED_DATE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.batches[0].courtHouse").doesNotExist())
                    .andExpect(jsonPath("$.batches[0].recipients").isArray());
        }

        @Test
        void a_date_holding_nothing_should_answer_an_empty_listing_rather_than_silence()
                throws Exception {
            when(listings.batchesOn(DATE)).thenReturn(List.of());

            mvc.perform(get(PATH).param("date", TYPED_DATE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.date").value(TYPED_DATE))
                    .andExpect(jsonPath("$.batches").isArray())
                    .andExpect(jsonPath("$.batches.length()").value(0));
        }
    }

    @Nested
    @DisplayName("the one argument")
    class TheArgument {

        @Test
        void an_absent_date_should_be_refused_400_missing_argument() throws Exception {
            mvc.perform(get(PATH))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("missing-argument"))
                    .andExpect(jsonPath("$.argument").value("date"));

            verifyNoInteractions(listings);
        }

        @Test
        void an_empty_date_should_be_refused_400_missing_argument() throws Exception {
            mvc.perform(get(PATH).param("date", "  "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("missing-argument"));

            verifyNoInteractions(listings);
        }

        @Test
        void a_date_that_will_not_read_should_be_refused_400_by_the_arguments_name()
                throws Exception {
            mvc.perform(get(PATH).param("date", NOT_A_DATE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("unreadable-argument"))
                    .andExpect(jsonPath("$.argument").value("date"));

            verifyNoInteractions(listings);
        }

        @Test
        void a_date_that_will_not_read_should_not_come_back_in_the_body() throws Exception {
            final String body = mvc.perform(get(PATH).param("date", NOT_A_DATE))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            Assertions.assertThat(body)
                    .doesNotContain(NOT_A_DATE)
                    .doesNotContain(PATH);
        }
    }

    @Nested
    @DisplayName("a store that will not answer")
    class NotRead {

        @Test
        void it_should_be_refused_503_listing_failed() throws Exception {
            when(listings.batchesOn(any())).thenThrow(new StoreUnavailableException(
                    "listing-failed", new SQLException("ZQX7STOREWORDS")));

            mvc.perform(get(PATH).param("date", TYPED_DATE))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.reason").value("listing-failed"));
        }

        @Test
        void it_should_carry_nothing_the_store_said_about_itself() throws Exception {
            when(listings.batchesOn(any())).thenThrow(new StoreUnavailableException(
                    "listing-failed", new SQLException("ZQX7STOREWORDS")));

            final String body = mvc.perform(get(PATH).param("date", TYPED_DATE))
                    .andReturn().getResponse().getContentAsString();

            Assertions.assertThat(body)
                    .doesNotContain("ZQX7STOREWORDS")
                    .doesNotContain("SQLException")
                    .doesNotContain(PATH);
        }

        @Test
        void a_statement_the_repository_did_not_translate_should_be_refused_the_same_way()
                throws Exception {
            when(listings.batchesOn(any())).thenThrow(
                    new DataAccessResourceFailureException("ZQX7STOREWORDS"));

            mvc.perform(get(PATH).param("date", TYPED_DATE))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.reason").value("listing-failed"));
        }

        @Test
        void a_failure_the_driver_says_is_worth_retrying_should_be_refused_the_same_way()
                throws Exception {
            when(listings.batchesOn(any())).thenThrow(
                    new TransientDataAccessResourceException("ZQX7STOREWORDS"));

            mvc.perform(get(PATH).param("date", TYPED_DATE))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.reason").value("listing-failed"));
        }
    }

    /**
     * A defect is not an outage, and must not be answered as one.
     *
     * <p>FR-023 and the design rules' status map keep {@code 503} for a dependency this endpoint
     * exists to read being unavailable, and {@code 500} for an unexpected defect. The distinction
     * is what a runbook acts on: a 503 is retried, and a defect retried is a defect retried for
     * ever. So anything out of the listing that is not one of the store's two unreachable shapes
     * leaves this controller untouched, and the container answers it under the body
     * {@code OperationsErrorAttributes} renders.
     */
    @Nested
    @DisplayName("a defect in the listing")
    class ADefect {

        @Test
        void it_should_not_be_answered_as_the_store_being_unavailable() {
            when(listings.batchesOn(any())).thenThrow(new IllegalStateException("ZQX7DEFECT"));

            Assertions.assertThatThrownBy(() -> mvc.perform(get(PATH).param("date", TYPED_DATE)))
                    .as("it reaches the container rather than being classified as an outage")
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void a_defect_wearing_the_stores_exception_type_should_not_be_an_outage_either() {
            when(listings.batchesOn(any())).thenThrow(
                    new DataIntegrityViolationException("ZQX7DEFECT"));

            Assertions.assertThatThrownBy(() -> mvc.perform(get(PATH).param("date", TYPED_DATE)))
                    .as("a violated constraint is this service's defect and not the store being "
                            + "unreachable; answered 503 it is a defect a runbook retries for ever")
                    .rootCause()
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void a_query_this_service_built_wrongly_should_not_be_an_outage_either() {
            when(listings.batchesOn(any())).thenThrow(
                    new InvalidDataAccessApiUsageException("ZQX7DEFECT"));

            Assertions.assertThatThrownBy(() -> mvc.perform(get(PATH).param("date", TYPED_DATE)))
                    .rootCause()
                    .isInstanceOf(InvalidDataAccessApiUsageException.class);
        }
    }
}
