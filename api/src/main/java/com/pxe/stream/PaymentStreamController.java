package com.pxe.stream;

import com.pxe.explain.ModelCall;
import com.pxe.explain.ModelCallRepository;
import com.pxe.model.Payment;
import com.pxe.model.PaymentRepository;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Section 17.2. One frame per hop, append-only.
 *
 * <p>The client appends; it never refetches and never re-renders the list. The skeleton arrives
 * first so the layout is reserved before anything moves, and the explanation arrives with the
 * stream rather than on request, because on the deterministic paths it was computed the instant the
 * outcome landed.
 *
 * <p>Hops are paced rather than replayed at their recorded intervals: PXE-007 spans nine days. The
 * wait is still the content, it is just compressed to demo tempo.
 */
@RestController
@RequestMapping("/api")
public class PaymentStreamController {

    private static final Logger log = LoggerFactory.getLogger(PaymentStreamController.class);

    private final PaymentView view;
    private final PaymentRepository payments;
    private final ModelCallRepository modelCalls;
    private final com.pxe.explain.ExplanationRepository explanations;
    private final ExecutorService streams = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "pxe-stream");
        t.setDaemon(true);
        return t;
    });
    private final long hopDelayMs;

    public PaymentStreamController(PaymentView view, PaymentRepository payments,
                                   ModelCallRepository modelCalls,
                                   com.pxe.explain.ExplanationRepository explanations,
                                   @Value("${pxe.stream.hop-delay-ms}") long hopDelayMs) {
        this.view = view;
        this.payments = payments;
        this.modelCalls = modelCalls;
        this.explanations = explanations;
        this.hopDelayMs = hopDelayMs;
    }

    /** The whole payment at once, for a screen that is not watching it arrive. */
    @GetMapping("/payments/{id}")
    public ResponseEntity<PaymentView.Snapshot> snapshot(@PathVariable String id) {
        return view.of(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Every payment, for the scenario selector on /pay. */
    @GetMapping("/payments")
    public List<PaymentView.Header> all() {
        return payments.findAll().stream()
                .sorted(Comparator.comparing(Payment::getId))
                .map(view::header)
                .toList();
    }

    /**
     * The two widgets. They stay visible the entire time and are never hidden, because they are the
     * argument: a debt that trends to zero, and a token counter that does not move until a payment
     * has earned the call.
     */
    @GetMapping("/counters")
    public Counters counters() {
        List<Payment> open = payments.findByDebtOpenTrueOrderByAmountMinorDesc();
        long explained = explanations.count();
        long viaModel = explanations.findAll().stream()
                .filter(e -> "MODEL".equals(e.getPath()) || "ABSTAIN".equals(e.getPath()))
                .count();
        return new Counters(
                open.size(),
                open.stream().mapToLong(Payment::getAmountMinor).sum(),
                modelCalls.findAll().stream().mapToInt(ModelCall::tokens).sum(),
                explained,
                viaModel);
    }

    @GetMapping(value = "/payments/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id) {
        SseEmitter emitter = new SseEmitter(120_000L);
        PaymentView.Snapshot snapshot = view.of(id).orElse(null);
        if (snapshot == null) {
            emitter.completeWithError(new IllegalArgumentException("no payment " + id));
            return emitter;
        }

        streams.submit(() -> {
            try {
                emitter.send(SseEmitter.event().name("skeleton").data(
                        new Skeleton(snapshot.header().paymentId(), snapshot.header().rail(),
                                snapshot.header().amountMinor(), snapshot.header().currency(),
                                snapshot.skeleton())));

                for (PaymentView.Hop hop : snapshot.hops()) {
                    Thread.sleep(hopDelayMs);
                    emitter.send(SseEmitter.event().name("hop").data(hop));
                }

                emitter.send(SseEmitter.event().name("outcome").data(
                        new Outcome(snapshot.header().tag(), snapshot.header().responseCode(),
                                snapshot.deviations(), snapshot.header().debtOpen())));

                if (snapshot.explanation() != null) {
                    emitter.send(SseEmitter.event().name("explanation").data(snapshot.explanation()));
                }

                emitter.send(SseEmitter.event().name("done").data(
                        new Done(snapshot.tokensSpent())));
                emitter.complete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.complete();
            } catch (IOException | IllegalStateException e) {
                log.debug("stream for {} ended early: {}", id, e.toString());
                emitter.complete();
            }
        });
        return emitter;
    }

    public record Skeleton(String paymentId, String rail, long amountMinor, String currency,
                           List<String> stages) {
    }

    public record Outcome(String tag, String responseCode, List<String> deviations,
                          boolean debtOpen) {
    }

    public record Done(int tokensSpent) {
    }

    /**
     * {@code debtOpen} is what is still owed, not what has been produced. The two are easy to
     * confuse at a glance on a widget, which is why the console spells the difference out
     * underneath: a debt of one next to thirteen explanations is the system working, not the
     * system having explained one thing.
     */
    public record Counters(int debtOpen, long exposureMinor, int tokensSpent, long explained,
                           long viaModel) {
    }
}
