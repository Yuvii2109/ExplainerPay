package com.pxe.explain;

import com.pxe.deviation.DeviationType;
import com.pxe.model.PaymentHop;
import com.pxe.model.PaymentReference;
import com.pxe.timeline.Timeline;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The only thing in pxe-api that talks to pxe-ai.
 *
 * <p>What crosses the boundary is a fact set and nothing else: no credentials, no ground truth, no
 * database handle. The model reasons about the record it is given and cannot reach anything it was
 * not given, which is the property the container boundary exists to make checkable.
 */
@Component
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    /** Raised rather than swallowed: an unavailable model leaves the debt open, unexplained. */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The model answered and the answer was thrown away for violating the contract.
     *
     * <p>Distinct from Unavailable on purpose. A rejection means tokens were spent and a rule
     * fired, both of which have to be recorded; an outage means neither happened.
     */
    public static class Rejected extends RuntimeException {
        private final String detail;

        public Rejected(String detail) {
            super(detail);
            this.detail = detail;
        }

        public String detail() {
            return detail;
        }
    }

    private final RestClient http;

    public AiClient(RestClient.Builder builder, @Value("${pxe.ai-url}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    public HypothesisResponse hypothesis(Timeline timeline, String factSetHash,
                                         Set<DeviationType> deviations, List<String> knownRules) {
        return post("/jobs/hypothesis", factSet(timeline, factSetHash, deviations, knownRules),
                HypothesisResponse.class);
    }

    public NarrativeResponse narrative(Timeline timeline, String factSetHash,
                                       Set<DeviationType> deviations, List<String> knownRules,
                                       String rootCause, List<Claim> claims, boolean determinable) {
        return post("/jobs/synthesis",
                new NarrativeRequest(factSet(timeline, factSetHash, deviations, knownRules),
                        rootCause, claims, determinable),
                NarrativeResponse.class);
    }

    private <T> T post(String path, Object body, Class<T> type) {
        try {
            return http.post().uri(path).body(body).retrieve().body(type);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 502) {
                log.warn("pxe-ai {} rejected the generation: {}", path, e.getResponseBodyAsString());
                throw new Rejected(e.getResponseBodyAsString());
            }
            log.warn("pxe-ai {} failed: {}", path, e.getMessage());
            throw new Unavailable("pxe-ai " + path + " failed", e);
        } catch (RestClientException e) {
            log.warn("pxe-ai {} failed: {}", path, e.getMessage());
            throw new Unavailable("pxe-ai " + path + " failed", e);
        }
    }

    private FactSet factSet(Timeline t, String factSetHash, Set<DeviationType> deviations,
                            List<String> knownRules) {
        return new FactSet(
                t.payment().getId(),
                factSetHash,
                t.payment().getInstrument().name(),
                t.payment().getRail(),
                t.payment().getTag() == null ? null : t.payment().getTag().name(),
                t.payment().getResponseCode(),
                t.hops().stream().map(AiClient::hop).toList(),
                t.references().stream().map(AiClient::reference).toList(),
                new TreeSet<>(deviations).stream().map(Enum::name).toList(),
                knownRules);
    }

    private static Hop hop(PaymentHop h) {
        return new Hop(h.getSeq(), h.getStage(), h.getActor(), h.getStatus(), h.getCode(),
                h.getLatencyMs(), h.getOccurredAt() != null, h.getAmountMinor(), h.getBatch(),
                h.getNote(), h.getAttrs());
    }

    private static Reference reference(PaymentReference r) {
        return new Reference(r.getHopSeq(), r.getKind().name(), r.getSupersededBy() != null);
    }

    // ---- the wire, mirroring ai/app/schemas.py ----

    public record Hop(int seq, String stage, String actor, String status, String code,
                      Long latencyMs, boolean occurred, Long amountMinor, String batch, String note,
                      Map<String, Object> attrs) {
    }

    public record Reference(int hopSeq, String kind, boolean superseded) {
    }

    public record FactSet(String paymentId, String factSetHash, String instrument, String rail,
                          String tag, String responseCode, List<Hop> hops,
                          List<Reference> references, List<String> deviations,
                          List<String> knownRules) {
    }

    public record Claim(String id, String kind, String text, List<String> citations,
                        Map<String, String> placeholders) {
    }

    public record Candidate(String cause, String evidence) {
    }

    public record HypothesisResult(boolean determinable, String rootCause, Double confidence,
                                   List<Claim> claims, List<Candidate> candidatesConsidered) {
    }

    public record NarrativeResult(String merchant, String support, String engineer,
                                  Map<String, String> placeholders) {
    }

    public record Usage(int promptTokens, int completionTokens, int latencyMs, String model,
                        String promptVersion) {
    }

    public record HypothesisResponse(HypothesisResult result, Usage usage) {
    }

    public record NarrativeRequest(FactSet facts, String rootCause, List<Claim> claims,
                                   boolean determinable) {
    }

    public record NarrativeResponse(NarrativeResult result, Usage usage) {
    }
}
