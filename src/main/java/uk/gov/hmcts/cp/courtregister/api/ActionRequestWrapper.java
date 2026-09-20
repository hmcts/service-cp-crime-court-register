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
 * <p>Nothing else about the request is altered. The body, the query string and every other header
 * arrive at the controller exactly as they were sent.
 */
/* default */ class ActionRequestWrapper extends HttpServletRequestWrapper {

    /** The action this service derived for the request, which overrides whatever arrived. */
    private final String action;

    /**
     * Wraps one request around one derived action.
     *
     * @param request the request as it arrived
     * @param action  the action name this service derived from the path and the method
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
                ? Collections.enumeration(List.of(action))
                : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        final List<String> names = Collections.list(super.getHeaderNames());
        if (names.stream().noneMatch(OperationsActionFilter.ACTION_HEADER::equalsIgnoreCase)) {
            names.add(OperationsActionFilter.ACTION_HEADER);
        }
        return Collections.enumeration(names);
    }
}
