package uk.gov.hmcts.cp.courtregister.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Whether this pod needs the framework file service at all.
 *
 * <p>Two halves write into it and neither owns it. The nightly run puts a render payload there
 * before it asks systemdocgenerator for a document, and the morning report puts its CSV there before
 * it asks notificationnotify to attach one - so "the file service is needed" is an
 * <em>either</em> and not the generation half's switch, which is what it was read as until review
 * gate 7.
 *
 * <p>A seam for now: the answer below is the one the pod already gives, so nothing moves with this
 * commit and the cases that ask for the second half are red on the answer rather than on a missing
 * class.
 */
public class FileServiceNeeded implements Condition {

    @Override
    public boolean matches(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
        return false;
    }
}
