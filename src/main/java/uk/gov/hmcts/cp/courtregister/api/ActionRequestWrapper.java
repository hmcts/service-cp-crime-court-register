package uk.gov.hmcts.cp.courtregister.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * A request whose {@code CPP-ACTION} header is the server's answer rather than the caller's.
 *
 * <p>The wrapper overrides the three header reads together, because the authorisation filter uses
 * {@code getHeader} and something downstream may well use the other two: a value that could be seen
 * through one accessor and not another would be a hole with a longer life than the first one.
 * Header names are case-insensitive in HTTP and are treated so here.
 *
 * <p><strong>A {@code null} action is an answer too, and it is the header's absence.</strong> Every
 * request is wrapped, including the ones this service names no action for, because "this service
 * derived nothing" and "the caller said it was the regeneration" must not arrive at the
 * authorisation filter as the same thing. A {@code HEAD} on a path served for {@code GET} is
 * exactly such a request - Spring answers it through the {@code @GetMapping}, and the filter's
 * lookup is keyed by method as well as path - so without this it would have been authorised
 * against a name the caller chose (FR-036). Stripped, it falls to the library's computed
 * {@code "HEAD /operations/flag"}, which matches no rule and is refused.
 *
 * <p>Nothing else about the request is altered. The body, the query string and every other header
 * arrive at the controller exactly as they were sent.
 */
/* default */ class ActionRequestWrapper extends HttpServletRequestWrapper {

    /**
     * The action this service derived for the request, or {@code null} where it derived none -
     * which overrides whatever arrived, either way.
     */
    private final String action;

    /**
     * Wraps one request around one derived action, or around the absence of one.
     *
     * @param request the request as it arrived
     * @param action  the action name this service derived from the path and the method, or
     *                {@code null} where this service names no action for the request
     */
    /* default */ ActionRequestWrapper(final HttpServletRequest request, final String action) {
        super(request);
        this.action = action;
    }

    @Override
    public String getHeader(final String name) {
        return OperationsActionFilter.ACTION_HEADER.equalsIgnoreCase(name)
                ? action
                : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(final String name) {
        return OperationsActionFilter.ACTION_HEADER.equalsIgnoreCase(name)
                ? Collections.enumeration(action == null ? List.of() : List.of(action))
                : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        final List<String> names = Collections.list(super.getHeaderNames());
        names.removeIf(OperationsActionFilter.ACTION_HEADER::equalsIgnoreCase);
        if (action != null) {
            names.add(OperationsActionFilter.ACTION_HEADER);
        }
        return Collections.enumeration(names);
    }
}
