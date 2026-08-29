package com.pxe.grounding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * G9. The causes this system can name, held on the side that enforces rather than the side that
 * generates.
 *
 * <p>pxe-ai constrains the model to this list through the response schema. pxe-api checks it again
 * on arrival, because a contract enforced only by the party it constrains is not a contract.
 */
@Component
public class CauseTaxonomy {

    private static final Logger log = LoggerFactory.getLogger(CauseTaxonomy.class);

    private final Set<String> causes;

    public CauseTaxonomy(ObjectMapper mapper, @Value("${pxe.causes-resource}") Resource resource)
            throws IOException {
        try (InputStream in = resource.getInputStream()) {
            this.causes = Set.copyOf(mapper.readValue(in, new TypeReference<List<String>>() {
            }));
        }
        log.info("loaded cause taxonomy from {}: {} causes", resource.getDescription(), causes.size());
    }

    public Set<String> causes() {
        return causes;
    }

    public boolean contains(String cause) {
        return cause != null && causes.contains(cause);
    }
}
