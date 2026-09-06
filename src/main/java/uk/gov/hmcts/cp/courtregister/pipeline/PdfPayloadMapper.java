package uk.gov.hmcts.cp.courtregister.pipeline;

import java.time.Clock;
import tools.jackson.databind.JsonNode;

/**
 * The render payload one batch is turned into, exactly as progression turned it.
 *
 * <p>A Java to Java port of {@code PROG CourtRegisterPdfPayloadGenerator} (364 lines), bug for bug:
 * the same helpers in the same order, the same DASH fallbacks, the same date formats, and C24's
 * {@code ####} sentinel substituted for a newline where progression substitutes it. What the port
 * changes is the JSON library and nothing else, {@code javax.json} for a Jackson tree, which is why
 * the signature is tree in and tree out rather than the typed model the rest of this package deals
 * in: the generator read the raw document, and a port that read a typed one would be a rewrite whose
 * output nobody could compare.
 *
 * <p>The comparison is the point. Every golden under
 * {@code src/test/resources/goldens/progression/pdf-payload/} was recorded by executing
 * progression's own class, and {@code PdfPayloadMapperTest} requires this one to be byte-identical
 * to all of them.
 *
 * <p><strong>The clock is a dependency because one field reads it.</strong> Progression's
 * {@code getAge} ({@code :323-329}) is
 * {@code Period.between(dateOfBirth, LocalDate.now()).getYears()}, and {@code age} is the only field
 * in the whole payload that is a function of when the mapping runs rather than of the document.
 * {@code LocalDate.now()} cannot be pinned from outside the JVM, so the goldens carry the date they
 * were recorded on ({@code INDEX.json} {@code ageClockDate}) and the port takes the clock it counts
 * against, which is what lets {@code PdfPayloadMapperTest} compare an age recorded on one day with
 * one computed on another.
 *
 * <p><strong>Seam only.</strong> The port lands with T042; until then this throws, so that
 * {@code PdfPayloadMapperTest} records a failing assertion rather than a compile error. The clock is
 * held from now because the test that holds the port to the goldens has to be able to pin it.
 */
public class PdfPayloadMapper {

    /** The clock {@code getAge} counts years against, read by the port T042 lands. */
    @SuppressWarnings("PMD.UnusedPrivateField")
    private final Clock clock;

    /**
     * Creates the mapper.
     *
     * @param clock the clock ages are counted against, which the goldens pin to the date they were
     *              recorded on
     */
    public PdfPayloadMapper(final Clock clock) {
        this.clock = clock;
    }

    /**
     * Maps one batch's documents to the payload systemdocgenerator renders.
     *
     * <p>The input is the shape progression's {@code CourtRegisterGenerated} event carried, which is
     * what its generator was written against: an object whose {@code courtRegisterDocumentRequests}
     * array holds the batch's recorded documents in the order they were assembled.
     *
     * @param courtRegisterGeneratedShapedInput the batch's documents under
     *                                          {@code courtRegisterDocumentRequests}
     * @return the payload, ready to be written to the file service
     */
    public JsonNode mapPayload(final JsonNode courtRegisterGeneratedShapedInput) {
        throw new UnsupportedOperationException("T042");
    }
}
