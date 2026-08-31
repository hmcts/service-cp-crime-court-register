package uk.gov.hmcts.cp.courtregister.support;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mapper the comparator's own suites read JSON with.
 *
 * <p>It has to carry {@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS}, which is what makes
 * {@code 1.0} and {@code 1.00} arrive as two distinct {@code BigDecimal} scales in the first place.
 * Parse the comparator vectors with a default mapper instead and both collapse to the same
 * {@code double}, every numeric-scale vector passes trivially, and the suites stop testing the thing
 * they exist to test.
 *
 * <p><strong>This is a stand-in with a date on it.</strong> The service's own contract mapper is
 * {@code config/JacksonConfig}, which lands with the inbound contract (T012) and is where the
 * feature belongs: it configures the mapper the running service uses as well as the standalone one,
 * and {@code SharedObjectMapperTest} pins the two together. Until it exists there is nothing to
 * delegate to, and the comparator is deliberately domain-independent — it is vendored before the
 * domain it will compare. When {@code JacksonConfig} arrives, this factory delegates to
 * {@code JacksonConfig.contractObjectMapper()} and the duplication ends; the two must not be allowed
 * to drift in the meantime.
 */
public final class ContractJson {

    private ContractJson() {
        // Static fixture holder.
    }

    /**
     * A mapper carrying this service's JSON contract.
     */
    public static ObjectMapper mapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
    }

    /**
     * Parses JSON text with {@link #mapper()}.
     *
     * @param json the text
     * @return the tree
     */
    public static JsonNode tree(final String json) {
        return mapper().readTree(json);
    }
}
