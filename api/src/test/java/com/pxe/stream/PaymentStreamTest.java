package com.pxe.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxe.expectation.RailSequences;
import com.pxe.support.Baseline;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 5: a payment streams hop by hop, the layout is reserved before anything moves, and the
 * deterministic explanation is already on the wire rather than fetched on request.
 *
 * <p>Requires the compose stack: {@code docker compose up -d}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PaymentStreamTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper mapper;
    @Autowired
    RailSequences rails;
    @Autowired
    Baseline baseline;

    @org.junit.jupiter.api.BeforeEach
    void deterministicBaseline() {
        baseline.deterministicOnly();
    }

    private record Frame(String event, JsonNode data) {
    }

    /** Reads a complete SSE response and splits it into ordered frames. */
    private List<Frame> stream(String paymentId) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/payments/" + paymentId + "/stream"))
                        .timeout(Duration.ofSeconds(30))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        List<Frame> frames = new ArrayList<>();
        String event = null;
        for (String line : response.body().split("\n")) {
            if (line.startsWith("event:")) {
                event = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                frames.add(new Frame(event, mapper.readTree(line.substring(5).trim())));
            }
        }
        return frames;
    }

    private List<String> events(List<Frame> frames) {
        return frames.stream().map(Frame::event).toList();
    }

    @Test
    void aPaymentStreamsHopByHop() throws Exception {
        List<Frame> frames = stream("PXE-006");
        List<String> events = events(frames);

        assertThat(events.getFirst())
                .as("the skeleton reserves the layout before anything can move")
                .isEqualTo("skeleton");
        assertThat(events.getLast()).isEqualTo("done");
        assertThat(events).filteredOn("hop"::equals).hasSize(9);

        // One frame per hop, in causal order, appended and never replaced.
        List<Integer> seqs = frames.stream()
                .filter(f -> "hop".equals(f.event()))
                .map(f -> f.data().get("seq").asInt())
                .toList();
        assertThat(seqs).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    void theSkeletonArrivesBeforeAnyHopAndHoldsEveryStageOfTheRail() throws Exception {
        List<Frame> frames = stream("PXE-001");
        Frame skeleton = frames.getFirst();

        assertThat(skeleton.event()).isEqualTo("skeleton");
        List<String> stages = new ArrayList<>();
        skeleton.data().get("stages").forEach(s -> stages.add(s.asText()));

        assertThat(stages).isEqualTo(rails.skeletonFor("UPI_QR"));
        assertThat(stages).hasSize(7);
        // Every row the timeline will ever draw for the happy path exists before hop one lands,
        // so a hop arriving changes a row's colour and never the number of rows.
        assertThat(events(frames).indexOf("hop")).isEqualTo(1);
    }

    @Test
    void theDeterministicExplanationIsAlreadyOnTheWire() throws Exception {
        for (String deterministic : List.of("PXE-003", "PXE-006", "PXE-007", "PXE-015")) {
            List<Frame> frames = stream(deterministic);
            Frame explanation = frames.stream()
                    .filter(f -> "explanation".equals(f.event()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no explanation frame for " + deterministic));

            assertThat(explanation.data().get("rootCause").asText()).isNotBlank();
            assertThat(explanation.data().get("path").asText()).isIn("CODE", "RULE");
            // It arrives with the stream, not on request. Reveal is not fast, it is free.
            assertThat(events(frames).indexOf("explanation"))
                    .isLessThan(events(frames).indexOf("done"));
        }
    }

    @Test
    void aCleanSuccessStreamsNoExplanationAtAll() throws Exception {
        assertThat(events(stream("PXE-001")))
                .as("a payment that worked owes nothing and costs nothing to say so")
                .doesNotContain("explanation");
        assertThat(events(stream("PXE-002"))).doesNotContain("explanation");
    }

    @Test
    void aPaymentStillOwingAnExplanationStreamsTheOutcomeWithoutOne() throws Exception {
        List<Frame> frames = stream("PXE-011");
        assertThat(events(frames)).doesNotContain("explanation");

        JsonNode outcome = frames.stream()
                .filter(f -> "outcome".equals(f.event()))
                .findFirst()
                .orElseThrow()
                .data();
        assertThat(outcome.get("tag").asText()).isEqualTo("FAILED");
        assertThat(outcome.get("debtOpen").asBoolean()).isTrue();
    }

    @Test
    void theAbsentNodeStreamsAsAHopThatDidNotHappen() throws Exception {
        JsonNode absent = stream("PXE-014").stream()
                .filter(f -> "hop".equals(f.event()))
                .map(Frame::data)
                .filter(h -> h.get("absent").asBoolean())
                .findFirst()
                .orElseThrow(() -> new AssertionError("PXE-014 must stream an absent hop"));

        assertThat(absent.get("stage").asText()).isEqualTo("PAYOUT_CREDITED");
        assertThat(absent.get("occurredAt").isNull()).isTrue();
        assertThat(absent.get("attrs").get("elapsedHours").asInt()).isEqualTo(71);
    }

    @Test
    void everyRailHasASkeletonAndEveryPaymentFitsIt() {
        assertThat(rails.all()).hasSize(5);
        assertThat(rails.all().keySet()).containsExactlyInAnyOrder(
                "UPI_QR", "UPI_COLLECT", "UPI_PAYOUT", "CARD_DOMESTIC", "NETBANKING");
        assertThat(rails.skeletonFor("UPI_PAYOUT"))
                .as("PXE-014's absent node is a skeleton row that never lights")
                .containsExactly("PAYOUT_INSTRUCTED", "PAYOUT_ACKNOWLEDGED", "PAYOUT_CREDITED");
    }

    @Test
    void askingWhyAnswersInTheShapeTheScreenRenders() throws Exception {
        // The explain route once answered with a narrower record that carried no audience text, so
        // an explanation reached the panel with prose the pipeline had just written stripped out of
        // it, and the screen said "no rendering at this level" until the page was reloaded. One
        // shape for one concept is the fix, and this is the guard on it.
        assertThat(com.pxe.explain.ExplanationController.class
                .getMethod("explain", String.class)
                .getGenericReturnType().getTypeName())
                .as("asking why must answer with the same payment shape the screen already renders")
                .isEqualTo(PaymentStreamController.class
                        .getMethod("snapshot", String.class)
                        .getGenericReturnType().getTypeName());
    }

    @Test
    void theCountersReportADebtAndNoTokens() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/counters"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode counters = mapper.readTree(response.body());

        assertThat(counters.get("debtOpen").asInt()).isEqualTo(3);
        assertThat(counters.get("tokensSpent").asInt())
                .as("the counter does not move until a payment has earned the call")
                .isZero();
    }
}
