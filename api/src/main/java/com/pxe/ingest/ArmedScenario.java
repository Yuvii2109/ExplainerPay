package com.pxe.ingest;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * What the rails return for the next scan.
 *
 * <p>Held on the server so the QR never changes. One QR on screen for the whole demo, and the
 * merchant decides behind it which failure the next customer meets; the customer decides the
 * amount. That is the real division: nobody paying a QR chooses whether their bank times out.
 *
 * <p>In memory on purpose. It is a demo control, not a fact about a payment, and a restart landing
 * back on the clean success is the right default.
 */
@Component
public class ArmedScenario {

    private static final String DEFAULT = "PXE-001";

    private final AtomicReference<String> armed = new AtomicReference<>(DEFAULT);

    public String get() {
        return armed.get();
    }

    public void arm(String scenarioId) {
        armed.set(scenarioId);
    }
}
