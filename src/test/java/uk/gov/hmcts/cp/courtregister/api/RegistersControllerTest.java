package uk.gov.hmcts.cp.courtregister.api;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.cp.courtregister.application.BatchListingService;
import uk.gov.hmcts.cp.courtregister.application.RecordedWhileOff;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException;

/**
 * What {@code GET /operations/registers/recorded-while-off} answers.
 *
 * <p>The rows a rollback has to account for. The endpoint takes nothing, so the whole of its
 * surface is the shape of the answer and the one refusal it can make.
 */
@WebMvcTest(controllers = RegistersController.class, properties = {
    "authz.http.enabled=false",
    "audit.http.enabled=false",
    "cp.audit.enabled=false",
})
@DisplayName("the recorded-while-off endpoint")
class RegistersControllerTest {

    /** The path, written out so a change to it fails here rather than silently 404ing. */
    private static final String PATH = "/operations/registers/recorded-while-off";

    private static final UUID RECORD = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301");

    private static final UUID HEARING = UUID.fromString("9e2b4c60-1d38-4a75-9f04-6b3c8d1e5a72");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BatchListingService listings;

    @Nested
    @DisplayName("what is waiting")
    class Waiting {

        @Test
        void it_should_answer_the_listing_shape() throws Exception {
            when(listings.recordedWhileOff()).thenReturn(List.of(new RecordedWhileOff(RECORD,
                    HEARING, LocalDate.parse("2026-09-04"), RecordedFlagState.OFF)));

            mvc.perform(get(PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records.length()").value(1))
                    .andExpect(jsonPath("$.records[0].recordId").value(RECORD.toString()))
                    .andExpect(jsonPath("$.records[0].hearingId").value(HEARING.toString()))
                    .andExpect(jsonPath("$.records[0].registerDate").value("2026-09-04"))
                    .andExpect(jsonPath("$.records[0].flag").value("OFF"));
        }

        @Test
        void nothing_waiting_should_answer_an_empty_listing_rather_than_silence() throws Exception {
            when(listings.recordedWhileOff()).thenReturn(List.of());

            mvc.perform(get(PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records").isArray())
                    .andExpect(jsonPath("$.records.length()").value(0));
        }

        @Test
        void a_record_whose_flag_was_never_read_should_say_so() throws Exception {
            when(listings.recordedWhileOff()).thenReturn(List.of(new RecordedWhileOff(RECORD,
                    HEARING, LocalDate.parse("2026-09-04"), RecordedFlagState.UNKNOWN)));

            mvc.perform(get(PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records[0].flag").value("UNKNOWN"));
        }
    }

    @Nested
    @DisplayName("a store that will not answer")
    class NotRead {

        @Test
        void it_should_be_refused_503_listing_failed() throws Exception {
            when(listings.recordedWhileOff()).thenThrow(new StoreUnavailableException(
                    "listing-failed", new SQLException("ZQX7STOREWORDS")));

            mvc.perform(get(PATH))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.reason").value("listing-failed"));
        }

        @Test
        void it_should_carry_nothing_the_store_said_about_itself() throws Exception {
            when(listings.recordedWhileOff()).thenThrow(new StoreUnavailableException(
                    "listing-failed", new SQLException("ZQX7STOREWORDS")));

            final String body = mvc.perform(get(PATH))
                    .andReturn().getResponse().getContentAsString();

            Assertions.assertThat(body)
                    .doesNotContain("ZQX7STOREWORDS")
                    .doesNotContain("SQLException")
                    .doesNotContain(PATH);
        }
    }
}
