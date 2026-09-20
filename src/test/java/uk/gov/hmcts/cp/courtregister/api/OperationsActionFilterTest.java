package uk.gov.hmcts.cp.courtregister.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Who decides what action a request is.
 *
 * <p>The answer has to be this service, for every path it serves, whatever the caller sent - the
 * authorisation library's own resolver reads {@code CPP-ACTION} from the wire before it falls back
 * to a computed name, and a caller admitted to a listing could otherwise present themselves as the
 * regeneration. So the cases below assert the header the <em>chain</em> sees, captured off the
 * wrapped request, rather than anything the filter returns.
 */
@DisplayName("the action a request is named as")
class OperationsActionFilterTest {

    /** The header the authorisation filter reads. */
    private static final String ACTION = "CPP-ACTION";

    /** Every action name this service admits, prefixed with its surface. */
    private static final String PREFIX = "courtregister-operations.";

    /** The filter under test, which holds no state between requests. */
    private final OperationsActionFilter filter = new OperationsActionFilter();

    /**
     * Runs one request through the filter and reports the action the chain was handed.
     *
     * @param method  the request's method
     * @param path    the request's path
     * @param sent    what the caller put in {@code CPP-ACTION}, or {@code null} for nothing
     * @return the header the next filter in the chain would read
     * @throws Exception where the filter or the chain refuses, which no case here expects
     */
    private String seenByTheChain(final String method, final String path, final String sent)
            throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (sent != null) {
            request.addHeader(ACTION, sent);
        }
        final FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        final ArgumentCaptor<HttpServletRequest> passed =
                ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain).doFilter(passed.capture(), any());
        return passed.getValue().getHeader(ACTION);
    }

    @Nested
    @DisplayName("derived from the path and the method")
    class Derived {

        @ParameterizedTest
        @CsvSource({
            "GET,  /operations/flag,                             check-flag",
            "GET,  /operations/batches,                          list-batches",
            "GET,  /operations/registers/recorded-while-off,     list-recorded-while-off",
            "POST, /operations/batches/generate,                 generate-register",
            "POST, /operations/batches/3f2504e0-4f89-11d3-9a0c-0305e82c3301/notify, notify-register",
            "POST, /operations/registers/supersede,              supersede-before",
            "POST, /operations/exception-reports,                report-exceptions",
        })
        void every_endpoint_should_be_given_its_own_action(final String method, final String path,
                final String verb) throws Exception {
            assertThat(seenByTheChain(method, path, null)).isEqualTo(PREFIX + verb);
        }

        @Test
        void the_notify_path_should_be_matched_whatever_the_batch_id_reads_like() throws Exception {
            assertThat(seenByTheChain("POST", "/operations/batches/not-a-uuid/notify", null))
                    .isEqualTo(PREFIX + "notify-register");
        }
    }

    @Nested
    @DisplayName("the caller's own header")
    class TheCallersHeader {

        @Test
        void a_forged_action_should_be_overridden_by_the_derived_one() throws Exception {
            assertThat(seenByTheChain("GET", "/operations/batches", PREFIX + "generate-register"))
                    .isEqualTo(PREFIX + "list-batches");
        }

        @Test
        void the_header_lookup_should_be_case_insensitive() throws Exception {
            final MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/operations/flag");
            request.addHeader(ACTION, PREFIX + "supersede-before");
            final FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            final ArgumentCaptor<HttpServletRequest> passed =
                    ArgumentCaptor.forClass(HttpServletRequest.class);
            verify(chain).doFilter(passed.capture(), any());
            assertThat(passed.getValue().getHeader("cpp-action")).isEqualTo(PREFIX + "check-flag");
            assertThat(Collections.list(passed.getValue().getHeaders("CpP-AcTiOn")))
                    .containsExactly(PREFIX + "check-flag");
        }

        @Test
        void the_header_should_be_among_the_names_the_request_reports() throws Exception {
            final MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/operations/flag");
            final FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            final ArgumentCaptor<HttpServletRequest> passed =
                    ArgumentCaptor.forClass(HttpServletRequest.class);
            verify(chain).doFilter(passed.capture(), any());
            final List<String> names = Collections.list(passed.getValue().getHeaderNames());
            assertThat(names).anySatisfy(name -> assertThat(name).isEqualToIgnoringCase(ACTION));
        }
    }

    @Nested
    @DisplayName("a path or a method this service does not serve")
    class NotOurs {

        @Test
        void an_unmapped_path_should_be_passed_through_with_nothing_added() throws Exception {
            assertThat(seenByTheChain("GET", "/actuator/health", null)).isNull();
        }

        @Test
        void an_unmapped_path_should_not_keep_whatever_the_caller_sent() throws Exception {
            assertThat(seenByTheChain("GET", "/actuator/health", PREFIX + "check-flag"))
                    .as("deriving the name server-side is worth nothing if a path this service "
                            + "does not recognise is the way round it")
                    .isNull();
        }

        @Test
        void a_head_on_a_path_served_for_get_should_not_carry_a_name_the_caller_chose()
                throws Exception {
            assertThat(seenByTheChain("HEAD", "/operations/flag", PREFIX + "generate-register"))
                    .as("Spring answers HEAD through the @GetMapping, so this reaches a served "
                            + "endpoint; the lookup is keyed by method as well as path, so this "
                            + "service derives nothing for it, and what is left must not be the "
                            + "caller's own word for what they are doing")
                    .isNull();
        }

        @Test
        void the_header_should_not_be_among_the_names_where_nothing_was_derived() throws Exception {
            final MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/actuator/health");
            request.addHeader(ACTION, PREFIX + "check-flag");
            final FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            final ArgumentCaptor<HttpServletRequest> passed =
                    ArgumentCaptor.forClass(HttpServletRequest.class);
            verify(chain).doFilter(passed.capture(), any());
            assertThat(Collections.list(passed.getValue().getHeaders(ACTION))).isEmpty();
            assertThat(Collections.list(passed.getValue().getHeaderNames()))
                    .noneSatisfy(name -> assertThat(name).isEqualToIgnoringCase(ACTION));
        }

        @Test
        void a_method_the_endpoint_does_not_answer_should_not_be_given_its_action()
                throws Exception {
            assertThat(seenByTheChain("GET", "/operations/batches/generate", null)).isNull();
        }

        @Test
        void the_notify_path_should_be_named_only_for_the_method_it_answers() throws Exception {
            assertThat(seenByTheChain("GET",
                    "/operations/batches/3f2504e0-4f89-11d3-9a0c-0305e82c3301/notify", null))
                    .isNull();
        }

        @Test
        void a_path_below_a_served_one_should_not_inherit_its_action() throws Exception {
            assertThat(seenByTheChain("GET", "/operations/flag/history", null)).isNull();
        }
    }
}
