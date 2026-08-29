package com.pxe.grounding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxe.timeline.Timeline;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Section 7, step 11. One fact set, three renderings.
 *
 * <p>The deterministic paths render from per-cause templates in {@code data/cause-templates.json}
 * and never reach a model, which is why a payment explained by a response code or a rule costs
 * nothing. The templates obey the same rule the model does: they write a slot, never a number, and
 * the number is substituted here from the record.
 *
 * <p>They differ in what the reader must do, not in tone. The support rendering carries the
 * instruction the other two do not — check for a duplicate payment, give the reversal window, do
 * not promise a refund — because audience rendering is about action.
 */
@Component
public class AudienceRenderer {

    private static final Logger log = LoggerFactory.getLogger(AudienceRenderer.class);
    private static final Pattern SLOT = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    /** The three renderings of one cause, before any slot is filled. */
    public record Template(String merchant, String support, String engineer) {
    }

    /** The three renderings of one payment, after every slot is filled. */
    public record Rendering(String merchant, String support, String engineer) {
    }

    private final Map<String, Template> templates;

    public AudienceRenderer(ObjectMapper mapper,
                            @Value("${pxe.cause-templates-resource}") Resource resource)
            throws IOException {
        try (InputStream in = resource.getInputStream()) {
            this.templates = Map.copyOf(mapper.readValue(in, new TypeReference<>() {
            }));
        }
        log.info("loaded cause templates from {}: {} causes", resource.getDescription(),
                templates.size());
    }

    public boolean canRender(String rootCause) {
        return rootCause != null && templates.containsKey(rootCause);
    }

    /**
     * Renders a cause against a payment. Empty when there is no template, or when the record
     * cannot supply a slot the template asks for: a half-filled sentence with a stray brace in it
     * is worse than no sentence.
     */
    public Optional<Rendering> render(String rootCause, Timeline timeline) {
        Template template = templates.get(rootCause);
        if (template == null) {
            return Optional.empty();
        }
        Map<String, String> slots = Slots.of(timeline);

        Optional<String> merchant = fill(template.merchant(), slots);
        Optional<String> support = fill(template.support(), slots);
        Optional<String> engineer = fill(template.engineer(), slots);

        if (merchant.isEmpty() || support.isEmpty() || engineer.isEmpty()) {
            log.warn("cause {} has a template the record cannot fill; rendering nothing", rootCause);
            return Optional.empty();
        }
        return Optional.of(new Rendering(merchant.get(), support.get(), engineer.get()));
    }

    private Optional<String> fill(String template, Map<String, String> slots) {
        String rendered = template;
        var matcher = SLOT.matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = slots.get(name);
            if (value == null) {
                return Optional.empty();
            }
            rendered = rendered.replace("{" + name + "}", value);
        }
        return Optional.of(rendered);
    }
}
