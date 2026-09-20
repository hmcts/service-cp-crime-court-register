package uk.gov.hmcts.cp.courtregister.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Names the action a request is, from its path and its method, and lets nobody else name it.
 *
 * <p>{@code cp-auth-rules-filter} resolves the action in a strict priority - a vendor token in
 * {@code Content-Type}, then one in {@code Accept}, then the {@code CPP-ACTION} header verbatim,
 * then the computed fallback {@code "<METHOD> <path>"}. This service's endpoints take and return
 * plain {@code application/json}, so without this filter every call would fall through to the
 * fallback, which matches no rule in {@code acl/operations-rules.drl} and is therefore refused.
 *
 * <p><strong>And the priority above the fallback is the caller's own header.</strong> A caller
 * authorised for {@code list-batches} could send
 * {@code CPP-ACTION: courtregister-operations.generate-register} and have the rules evaluated
 * against an action they were never admitted to. So the header is not read here, it is
 * <em>written</em>: the request is wrapped so the server's value is what
 * {@code getHeader("CPP-ACTION")} answers, whatever arrived on the wire (research R2).
 *
 * <p>The filter sits at {@link org.springframework.core.Ordered#HIGHEST_PRECEDENCE}, ahead of the
 * authorisation filter at {@code +30} and the audit filter at {@code +50}, because both of those
 * read the action this one derives.
 *
 * <p>A path this filter does not recognise is passed through <strong>untouched</strong>, header and
 * all: actuator is not part of this surface, and a request to a path that maps to nothing must not
 * be given an action name that could match a rule.
 */
public class OperationsActionFilter extends OncePerRequestFilter {

    /** The header the authorisation filter reads the action out of. */
    /* default */ static final String ACTION_HEADER = "CPP-ACTION";

    /** Every action this service names, prefixed with the surface it belongs to. */
    private static final String PREFIX = "courtregister-operations.";

    /** What separates a method from a path in this filter's one lookup key. */
    private static final char SPACE = ' ';

    /** The six endpoints whose path is a fixed string, keyed by method and path together. */
    private static final Map<String, String> FIXED = Map.of(
            "GET /operations/flag", PREFIX + "check-flag",
            "GET /operations/batches", PREFIX + "list-batches",
            "GET /operations/registers/recorded-while-off", PREFIX + "list-recorded-while-off",
            "POST /operations/batches/generate", PREFIX + "generate-register",
            "POST /operations/registers/supersede", PREFIX + "supersede-before",
            "POST /operations/exception-reports", PREFIX + "report-exceptions");

    /** The seventh, and the one path parameter in the whole document. */
    private static final Pattern NOTIFY = Pattern.compile("/operations/batches/[^/]+/notify");

    /** The method that endpoint answers on; anything else is not it. */
    private static final String POST = "POST";

    /** The action a notify request is. */
    private static final String NOTIFY_ACTION = PREFIX + "notify-register";

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
            final HttpServletResponse response, final FilterChain chain)
            throws ServletException, IOException {
        final String action = actionFor(request.getMethod(), pathOf(request));
        if (action == null) {
            chain.doFilter(request, response);
        } else {
            chain.doFilter(new ActionRequestWrapper(request, action), response);
        }
    }

    /**
     * Which action a method and a path are, or {@code null} where they are none of this service's.
     *
     * @param method the request's method, as the container reports it
     * @param path   the request's path, without the context path
     * @return the action name to authorise against, or {@code null} to leave the request alone
     */
    // PMD.OnlyOneReturn: the absence of an action is not a value to carry down the method; saying
    // so where it is decided is what keeps the notify lookup off a path that is not one.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static String actionFor(final String method, final String path) {
        if (method == null || path == null) {
            return null;
        }
        final String fixed = FIXED.get(method.toUpperCase(Locale.ROOT) + SPACE + path);
        if (fixed != null) {
            return fixed;
        }
        if (POST.equalsIgnoreCase(method) && NOTIFY.matcher(path).matches()) {
            return NOTIFY_ACTION;
        }
        return null;
    }

    /**
     * The path this request is for, preferring what the servlet mapping resolved.
     *
     * @param request the request as it arrived
     * @return the path to match on, which is never {@code null} for a request that reached a filter
     */
    private static String pathOf(final HttpServletRequest request) {
        final String servletPath = request.getServletPath();
        return servletPath == null || servletPath.isEmpty()
                ? request.getRequestURI()
                : servletPath;
    }
}
