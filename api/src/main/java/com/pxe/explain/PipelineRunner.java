package com.pxe.explain;

import com.pxe.deviation.DeviationDetection;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs L2 and L3 at startup, from outside the beans that do the work.
 *
 * <p>This class exists for one unglamorous reason: a bean calling its own {@code @Transactional}
 * method does not go through the proxy, so the annotation does nothing. When the pipeline invoked
 * itself, every repository call opened its own transaction and returned a fresh detached entity,
 * so the timeline held one Payment and the loop mutated another, and a template asking for the
 * resolved response code silently got nothing.
 *
 * <p>Calling from here goes through the proxy, the transaction spans the run, and the persistence
 * context hands back one instance per payment.
 */
@Component
@Order(20)
public class PipelineRunner implements ApplicationRunner {

    private final DeviationDetection detection;
    private final ExplanationPipeline pipeline;

    public PipelineRunner(DeviationDetection detection, ExplanationPipeline pipeline) {
        this.detection = detection;
        this.pipeline = pipeline;
    }

    @Override
    public void run(ApplicationArguments args) {
        detection.detectAll();
        pipeline.resolveAll();
    }
}
