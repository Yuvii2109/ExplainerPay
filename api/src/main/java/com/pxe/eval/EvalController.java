package com.pxe.eval;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The harness, on demand. Beat 12 of the demo runs this live. */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalHarness harness;

    public EvalController(EvalHarness harness) {
        this.harness = harness;
    }

    @GetMapping
    public EvalReport run() {
        return harness.run();
    }
}
