package com.pxe.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Who is owed the money.
 *
 * <p>Every payment already carried a merchant id, because G2 requires a cited record to belong to
 * this payment or its merchant. It was one hardcoded value, which made the check vacuous and made
 * the debt queue a single undifferentiated list. Real merchants make the queue answer the question
 * an ops team actually asks first: whose money is unexplained.
 *
 * <p>The mapping lives beside the dataset rather than inside it, so Appendix A stays the record of
 * what happened to a payment rather than of who was selling.
 */
@Component
public class Merchants {

    private static final Logger log = LoggerFactory.getLogger(Merchants.class);

    public record Merchant(String id, String name, String category, List<String> scenarios) {
    }

    private final List<Merchant> merchants;
    private final Map<String, Merchant> byScenario = new LinkedHashMap<>();

    public Merchants(ObjectMapper mapper, @Value("${pxe.merchants-resource}") Resource resource)
            throws IOException {
        try (InputStream in = resource.getInputStream()) {
            this.merchants = List.of(mapper.readValue(in, Merchant[].class));
        }
        merchants.forEach(m -> m.scenarios().forEach(s -> byScenario.put(s, m)));
        log.info("loaded {} merchants from {}", merchants.size(), resource.getDescription());
    }

    public List<Merchant> all() {
        return merchants;
    }

    public Optional<Merchant> byId(String merchantId) {
        return merchants.stream().filter(m -> m.id().equals(merchantId)).findFirst();
    }

    public String name(String merchantId) {
        return byId(merchantId).map(Merchant::name).orElse(merchantId);
    }

    /** Whose payment this is. Falls back to the first merchant so a new scenario is never orphaned. */
    public String forScenario(String scenarioId) {
        Merchant merchant = byScenario.get(scenarioId);
        return merchant != null ? merchant.id() : merchants.getFirst().id();
    }
}
