package com.gluonhq.netbeans.nbfx.editor.processor.semantics;

import com.gluonhq.netbeans.nbfx.editor.processor.semantics.detector.OccurrencesDetectorDispatcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.netbeans.api.java.source.CancellableTask;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.JavaSource.Phase;

/**
 * Computes Mark-Occurrences highlights for a caret position in a Java source,
 * with the appropriate detectors, converting raw offset spans into {@link TextPosResult}s.
 */
public class MarkOccurrencesProcessor {

    private static final Logger LOG = Logger.getLogger(MarkOccurrencesProcessor.class.getName());

    public MarkOccurrencesProcessor() {
    }

    /**
     * Runs occurrence detection asynchronously against the given immutable
     * {@link SourceContext.Snapshot}.
     *
     * @param snapshot the snapshot captured on the caller thread
     * @param caretOffset the global caret offset
     * @return a future with a list of {@link TextPosResult} per occurrence
     */
    public CompletableFuture<List<TextPosResult>> process(SourceContext.Snapshot snapshot, int caretOffset) {
        if (snapshot == null || snapshot.javaSource() == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        if (caretOffset < 0 || caretOffset > snapshot.source().length()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> {
            long t0 = System.currentTimeMillis();
            List<TextPosResult> results = analyze(snapshot, caretOffset);
            LOG.fine(() -> "MarkOccurrencesProcessor @ caret=" + caretOffset
                    + " took " + (System.currentTimeMillis() - t0)
                    + " ms, " + results.size() + " occurrence(s)");
            return results;
        });
    }

    private static List<TextPosResult> analyze(SourceContext.Snapshot snapshot, int caretOffset) {
        List<TextPosResult> results = new ArrayList<>();

        CancellableTask<CompilationController> task = new CancellableTask<>() {
            @Override
            public void run(CompilationController controller) throws Exception {
                controller.toPhase(Phase.RESOLVED);
                results.addAll(OccurrencesDetectorDispatcher.findOccurrences(controller, caretOffset, snapshot.lineStarts()));
            }
            @Override public void cancel() {}
        };

        try {
            boolean executed = SourceContext.runSemanticTask(snapshot, task, true);
            if (!executed) {
                return results;
            }
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "MarkOccurrences analysis failed", ex);
        }
        return results;
    }

}
