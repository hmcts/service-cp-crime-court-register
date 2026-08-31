package uk.gov.hmcts.cp.courtregister.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared JSON mapper configuration.
 *
 * <p>Compile-safe seam for T007: it hands back a mapper so the contract suites can build a parser,
 * but the contract itself — big decimals for floating-point values, applied to the running service's
 * mapper as well as to this standalone one — arrives with T012.
 */
public class JacksonConfig {

    /**
     * A standalone mapper carrying this service's JSON contract.
     *
     * @return the mapper code constructed outside a Spring context reads JSON with
     */
    public static ObjectMapper contractObjectMapper() {
        return JsonMapper.builder().build();
    }
}
