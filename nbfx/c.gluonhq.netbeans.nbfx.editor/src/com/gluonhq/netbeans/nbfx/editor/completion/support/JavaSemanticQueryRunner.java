package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionCancellation;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionContext;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import com.gluonhq.netbeans.nbfx.editor.processor.semantics.SourceContext;
import org.netbeans.api.java.source.CancellableTask;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.JavaSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Centralizes the resolved-phase contract and the cancellation check that every semantic query needs.
 */
final class JavaSemanticQueryRunner {

    /** Callback invoked once the {@link CompilationController} has reached {@link JavaSource.Phase#RESOLVED}. */
    @FunctionalInterface
    interface SemanticAction {
        void run(CompilationController controller, List<CompletionItem> items) throws Exception;
    }

    private JavaSemanticQueryRunner() {
    }

    /**
     * Runs {@code action} against the document's own source and returns the accumulated
     * items. Returns an empty list when the underlying task fails to run (I/O error,
     * runtime exception, or the {@link org.netbeans.api.java.source.JavaSource} was not
     * available for the file).
     */
    static List<CompletionItem> runQuery(CompletionContext context, CompletionCancellation cancellation,
                                         SemanticAction action) {
        return runQueryWithSource(context, context.documentText(), cancellation, action);
    }

    /**
     * Runs {@code action} against a possibly-modified source (e.g. one produced by
     * {@link JavaSourceRepair#synthesizeBalancedInvocation}). Returns an empty list
     * on any error or when the task is cancelled before producing items.
     */
    static List<CompletionItem> runQueryWithSource(CompletionContext context,
                                                   String sourceOverride,
                                                   CompletionCancellation cancellation,
                                                   SemanticAction action) {
        try {
            SourceContext sourceContext = new SourceContext(context.fileObject());
            List<CompletionItem> items = new ArrayList<>();
            CancellableTask<CompilationController> task = new CancellableTask<>() {
                @Override
                public void run(CompilationController controller) throws Exception {
                    if (cancellation.isCancelled()) {
                        return;
                    }
                    controller.toPhase(JavaSource.Phase.RESOLVED);
                    action.run(controller, items);
                }
                @Override public void cancel() {}
            };
            return sourceContext.runSemanticTask(sourceOverride, task, true) ? items : List.of();
        } catch (IOException | RuntimeException ex) {
            // Exception already logged by runSemanticTask
            return List.of();
        }
    }
}
