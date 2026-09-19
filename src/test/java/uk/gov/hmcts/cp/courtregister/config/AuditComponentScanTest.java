package uk.gov.hmcts.cp.courtregister.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * The audit starter's Spring stereotypes stay out of this application's component scan.
 *
 * <p>{@code cp-audit-filter-springboot}'s package root is {@code uk.gov.hmcts.cp.filter.audit} and
 * this service's {@code @SpringBootApplication} sits at {@code uk.gov.hmcts.cp}, so the library's
 * own classes are inside the scanned tree. Five of them still carry a stereotype despite the
 * library's README saying there are none, and one of those - {@code OpenApiSpecificationParser}, a
 * {@code @Component} whose only constructor takes a {@code String} nothing can supply - fails the
 * refresh outright. It fails it <strong>even with auditing switched off</strong>, because a
 * component scan answers to neither {@code cp.audit.enabled} nor {@code audit.http.enabled}: those
 * two gate the {@code @AutoConfiguration} class, and a scanned stereotype never reaches it.
 *
 * <p>So the exclusion is not a tidiness measure, and what it prevents is not a stray bean: it is
 * the pod that does not start at all, reporting a bean-definition error naming a class nobody in
 * this repository has heard of. The regex filter on {@link uk.gov.hmcts.cp.Application} is what
 * closes it, and {@code @AutoConfiguration} classes are not subject to {@code @ComponentScan}
 * filters, so the starter's auto-configuration still runs in full where it is switched on.
 *
 * <p><strong>Why the assertion is by class name rather than by type.</strong> This suite is
 * deliberately written before the dependency is added, so that the failure is observed rather than
 * predicted: it passes today with nothing on the classpath to find, fails the moment the dependency
 * arrives without the filter, and passes again once the filter is there. A {@code .class} literal
 * would not compile in the first of those three states, which is the one that makes the other two
 * mean anything.
 *
 * <p>The switches are off here because that is the configuration the trap hides in. A context with
 * auditing on would build the library's beans legitimately, and an assertion that found none would
 * only be saying the auto-configuration had not run.
 */
@SpringBootTest(properties = {
    "courtregister.operations.enabled=false",
    "cp.audit.enabled=false",
    "audit.http.enabled=false",
    "authz.http.enabled=false"})
@ActiveProfiles("test") // context-load only: no broker, no database, no Docker (application-test.yaml)
@DisplayName("the audit starter's component-scan clash")
class AuditComponentScanTest {

    /** The audit starter's package root, which is inside this application's scanned tree. */
    private static final String AUDIT_PACKAGE = "uk.gov.hmcts.cp.filter.audit.";

    /**
     * The one stereotype that fails the refresh: a {@code @Component} whose only constructor takes
     * a {@code String} the container cannot supply.
     */
    private static final String THE_UNSATISFIABLE_ONE =
            AUDIT_PACKAGE + "parser.OpenApiSpecificationParser";

    private final ConfigurableApplicationContext context;

    @Autowired
    AuditComponentScanTest(final ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Test
    @DisplayName("leaves the context able to start at all")
    void the_application_context_should_refresh_with_the_audit_switches_off() {
        assertThat(context.isActive())
                .as("a scanned OpenApiSpecificationParser fails the refresh before any assertion "
                        + "below can be reached, so this is the case that actually reports the "
                        + "clash: the exclusion is what lets a pod start with auditing switched off")
                .isTrue();
    }

    @Test
    @DisplayName("creates no bean of the audit starter's, by scanning or otherwise")
    void no_audit_starter_bean_should_be_on_a_context_with_auditing_switched_off() {
        final List<String> fromTheAuditStarter = auditStarterBeans();

        assertThat(fromTheAuditStarter)
                .as("with cp.audit.enabled=false the starter's auto-configuration is off, so every "
                        + "bean of its is one the component scan made - and the scan is exactly "
                        + "what bypasses both switches")
                .isEmpty();
    }

    @Test
    @DisplayName("creates no OpenApiSpecificationParser, which is the bean that fails the refresh")
    void the_unsatisfiable_parser_should_not_be_component_scanned() {
        assertThat(auditStarterBeans())
                .as("named on its own because it is the one that stops the pod rather than merely "
                        + "adding to it")
                .doesNotContain(THE_UNSATISFIABLE_ONE);
    }

    /**
     * The class names of every bean on this context that belongs to the audit starter.
     *
     * <p>Types are resolved without initialising a {@code FactoryBean}, so asking the question
     * cannot itself create the bean the question is about.
     *
     * @return the class names, in bean-definition order, and empty when the exclusion holds
     */
    private List<String> auditStarterBeans() {
        return Arrays.stream(context.getBeanDefinitionNames())
                .map(name -> context.getBeanFactory().getType(name, false))
                .filter(type -> type != null && type.getName().startsWith(AUDIT_PACKAGE))
                .map(Class::getName)
                .toList();
    }
}
