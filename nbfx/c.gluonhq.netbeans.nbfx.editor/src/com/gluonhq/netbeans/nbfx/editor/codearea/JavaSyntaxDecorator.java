package com.gluonhq.netbeans.nbfx.editor.codearea;

import com.gluonhq.netbeans.nbfx.editor.decoration.LineDecoration;
import com.gluonhq.netbeans.nbfx.editor.decoration.MarkedDecoration;
import com.gluonhq.netbeans.nbfx.editor.decoration.TokenCategory;
import com.gluonhq.netbeans.nbfx.editor.processor.SourceUtils;
import com.gluonhq.netbeans.nbfx.editor.processor.lex.LexDecorationProcessor;
import com.gluonhq.netbeans.nbfx.editor.processor.semantics.JavaFileProcessor;
import com.gluonhq.netbeans.nbfx.editor.processor.semantics.MarkOccurrencesProcessor;
import com.gluonhq.netbeans.nbfx.editor.processor.semantics.SourceContext;
import com.gluonhq.netbeans.nbfx.editor.processor.semantics.TextPosResult;
import javafx.application.Platform;
import jfx.incubator.scene.control.richtext.Marker;
import jfx.incubator.scene.control.richtext.SyntaxDecorator;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.CodeTextModel;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import org.openide.filesystems.FileObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link SyntaxDecorator} extension that combines lex-based syntax highlighting
 * with Java semantic analysis (method name highlighting).
 * <p>This decorator produces decorated lines, one by one. Each line can have multiple decorations.
 * </p>
 * <p>Lex analysis tokenizes the full source and caches per-line results;
 * the cache is invalidated in {@link #handleChange} and lazily rebuilt on the next {@link #createRichParagraph} call.
 * </p>
 * <p>Java semantic analysis is scheduled in the background via {@link #analyzeInBackground}.
 * Java decorations are stored as {@link MarkedDecoration}s ({@link Marker}-based ranges)
 * that use auto-track across edits and don't require cache invalidation.
 * </p>
 * <p>When the caret moves, braces and mark-occurrences highlights are recomputed for the
 * new position, and stored as {@link MarkedDecoration}s.
 * </p>
 */
public class JavaSyntaxDecorator implements SyntaxDecorator {

    private static final Executor FX_EXECUTOR = Platform::runLater;

    /** Delay (ms) before re-running analysis without editing-line suppression. */
    private static final long DIAGNOSTIC_DELAY_MS = 1500;

    /** Threads debouncing the analyses of the open documents, whatever project they belong to. */
    private static final int SCHEDULER_THREADS =
            Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 4);

    /**
     * Debounces the analyses of every open document. Several projects can be open at once, each
     * with several open files, so a single thread would make one document's delayed kick-off wait
     * for another's: a small bounded pool keeps them independent while still bounding the load
     * (the analyses themselves run on the common pool, this only starts them).
     */
    private static final ScheduledExecutorService SCHEDULED_EXECUTOR =
            Executors.newScheduledThreadPool(SCHEDULER_THREADS, new ThreadFactory() {
                private final AtomicInteger index = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "diagnostic-delay-" + index.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    private final SourceContext context;
    private final LexDecorationProcessor lexProcessor;
    private final JavaFileProcessor javaProcessor;
    private final MarkOccurrencesProcessor occurrencesProcessor;

    private List<MarkedDecoration> javaDecorations = List.of();
    private List<MarkedDecoration> braceDecorations = List.of();
    private List<MarkedDecoration> occurrenceDecorations = List.of();

    /**
     * Currently running background Java analysis, canceled on each new change. Restarted both from
     * the FX thread and from the delayed re-analysis, hence volatile and only touched inside
     * {@link #restartJavaAnalysis}, which is synchronized so the cancel-then-restart is atomic.
     */
    private volatile CompletableFuture<?> javaAnalysisFuture;

    /**
     * Currently running background mark-occurrences analysis, canceled on each caret move.
     * Assigned by the scheduled kick-off and read on the FX thread, hence volatile.
     */
    private volatile CompletableFuture<?> occurrencesFuture;

    /** Pending debounced kick-off of the mark-occurrences analysis. */
    private ScheduledFuture<?> pendingOccurrences;

    /** Delay between a caret move and the start of the mark-occurrences analysis. */
    private static final long OCCURRENCES_DELAY_MS = 250;

    /**
     * Line currently being edited, or {@code -1} when no suppression is needed. Cleared by the
     * delayed re-analysis and read on the FX thread, hence volatile.
     */
    private volatile int lastEditLine = -1;
    
    /** Pending delayed re-analysis without suppression, reset on each keystroke. */
    private ScheduledFuture<?> delayedAnalysis;

    public JavaSyntaxDecorator(FileObject fo) {
        this.context = new SourceContext(fo);
        this.lexProcessor = new LexDecorationProcessor(context);
        this.javaProcessor = new JavaFileProcessor(context);
        this.occurrencesProcessor = new MarkOccurrencesProcessor();
    }

    @Override
    public RichParagraph createRichParagraph(CodeTextModel model, int index) {
        String text = Objects.requireNonNull(model).getPlainText(index);
        if (text == null || text.isEmpty()) {
            return RichParagraph.builder().build();
        }
        ensureSource(model);

        List<LineDecoration> lexDecorations = lexProcessor.getLineDecorations(index);

        // Brace decorations for this line (overlay)
        List<LineDecoration> braceDecs = braceDecorations.stream()
                .filter(md -> md.touchesLine(index))
                .map(md -> md.toLineLocal(index, text.length()))
                .toList();

        // Mark-occurrence decorations for this line (overlay)
        List<LineDecoration> occurrenceDecs = occurrenceDecorations.stream()
                .filter(md -> md.touchesLine(index))
                .map(md -> md.toLineLocal(index, text.length()))
                .toList();

        // Java decorations for this line (Markers already have up-to-date positions)
        List<LineDecoration> allJavaDecs = javaDecorations.stream()
                .filter(md -> md.touchesLine(index))
                .map(md -> md.toLineLocal(index, text.length()))
                .sorted(Comparator.comparingInt(LineDecoration::start))
                .toList();

        // Separate squiggly overlays (errors/warnings) from text-style decorations
        // so squiggly decorations don't cut into keyword/identifier coloring.
        List<LineDecoration> javaTextDecs = new ArrayList<>();
        List<LineDecoration> squigglyDecs = new ArrayList<>();
        for (LineDecoration dec : allJavaDecs) {
            if (dec.style() != null && dec.style().startsWith(TokenCategory.SQUIGGLY_PREFIX)) {
                squigglyDecs.add(dec);
            } else {
                javaTextDecs.add(dec);
            }
        }

        // Merge lex + Java text decorations (java takes precedence on overlap)
        List<LineDecoration> merged = LineDecoration.mergeDecorations(javaTextDecs, lexDecorations);

        // Clip overlapping squiggly decorations so no two underlines overlap
        squigglyDecs = clipOverlappingSquigglies(squigglyDecs);

        // Append brace, occurrence and squiggly overlays
        List<LineDecoration> results = new ArrayList<>(merged.size() +
                braceDecs.size() + occurrenceDecs.size() + squigglyDecs.size());
        results.addAll(merged);
        results.addAll(braceDecs);
        results.addAll(occurrenceDecs);
        results.addAll(squigglyDecs);

        return LineDecoration.getRichParagraph(text, results);
    }

    @Override
    public void handleChange(CodeTextModel m, TextPos start, TextPos end, int charsTop, int linesAdded, int charsBottom) {
        // Invalidate lex cache and source context when the model changes
        lexProcessor.invalidate();
        context.invalidate();

        // invalidate braces and mark-occurrences, which are re-computed when the caret position is updated
        braceDecorations = List.of();
        occurrenceDecorations = List.of();
        cancelPendingOccurrences();

        // Record the line being edited so diagnostics on it can be suppressed
        lastEditLine = start.index();
    }

    /**
     * Recomputes brace-match decorations for the caret position and fires a
     * targeted style change only for the affected paragraphs.
     * <p>Safe to call on every caret move; the underlying token hierarchy
     * is cached by the lex processor and reused until the document changes.
     * </p>
     *
     * @param model the code text model
     * @param caret the current caret position
     */
    public void updateBraceMatch(CodeTextModel model, TextPos caret) {
        if (model == null || caret == null) {
            return;
        }
        ensureSource(model);
        String source = context.source();
        if (source == null || source.isEmpty()) {
            clearBraceMatch(model);
            return;
        }

        List<TextPosResult> results = lexProcessor.findBraceMatchResults(caret);
        List<MarkedDecoration> newDecs = TextPosResult.toMarkedDecorations(results, model);

        if (MarkedDecoration.sameDecorations(newDecs, braceDecorations)) {
            return;
        }

        int[] range = MarkedDecoration.lineRange(braceDecorations, newDecs);
        braceDecorations = newDecs;
        fireCaretHighlightChange(model, range);
    }

    /**
     * Clears any active brace-match highlight and refreshes the previously
     * affected paragraphs.
     *
     * @param model the code text model
     */
    public void clearBraceMatch(CodeTextModel model) {
        if (braceDecorations.isEmpty()) {
            return;
        }
        int[] range = MarkedDecoration.lineRange(braceDecorations);
        braceDecorations = List.of();
        fireCaretHighlightChange(model, range);
    }

    /**
     * Schedules a mark-occurrences analysis for the current caret position.
     */
    public void updateOccurrencesInBackground(CodeTextModel model, TextPos caret) {
        if (model == null || caret == null) {
            return;
        }
        // cancel any pending or running request.
        cancelPendingOccurrences();

        ensureSource(model);
        SourceContext.Snapshot snapshot = context.snapshot();
        if (snapshot == null) {
            clearOccurrences(model);
            return;
        }

        int[] lineStarts = snapshot.lineStarts();
        int caretIndex = caret.index();
        int lineStart = caretIndex < lineStarts.length ? lineStarts[caretIndex] : snapshot.source().length();
        final int caretOffset = lineStart + caret.offset();
        final int expectedLineCount = model.size();
        final String capturedSource = snapshot.source();

        pendingOccurrences = SCHEDULED_EXECUTOR.schedule(() -> {
            CompletableFuture<List<TextPosResult>> f = occurrencesProcessor.process(snapshot, caretOffset);
            occurrencesFuture = f.thenAcceptAsync(list -> {
                // Discard invalid results if the model changed in the meantime.
                if (model.size() != expectedLineCount || !capturedSource.equals(context.source())) {
                    return;
                }
                List<MarkedDecoration> newDecs = TextPosResult.toMarkedDecorations(list, model);
                if (MarkedDecoration.sameDecorations(newDecs, occurrenceDecorations)) {
                    return;
                }
                int[] range = MarkedDecoration.lineRange(occurrenceDecorations, newDecs);
                occurrenceDecorations = newDecs;
                fireCaretHighlightChange(model, range);
            }, FX_EXECUTOR);
        }, OCCURRENCES_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Clears any active mark-occurrences highlights and refreshes the previously
     * affected paragraphs.
     */
    public void clearOccurrences(CodeTextModel model) {
        cancelPendingOccurrences();
        if (occurrenceDecorations.isEmpty()) {
            return;
        }
        int[] range = MarkedDecoration.lineRange(occurrenceDecorations);
        occurrenceDecorations = List.of();
        fireCaretHighlightChange(model, range);
    }

    private void cancelPendingOccurrences() {
        ScheduledFuture<?> pending = pendingOccurrences;
        if (pending != null) {
            pending.cancel(false);
            pendingOccurrences = null;
        }
        CompletableFuture<?> running = occurrencesFuture;
        if (running != null && !running.isDone()) {
            running.cancel(true);
        }
    }

    /**
     * Triggers a background Java semantic analysis of the full source.
     * Call this after the model content has been set, and after successive edits.
     */
    public void analyzeInBackground(CodeTextModel model) {
        int editLine = lastEditLine;
        restartJavaAnalysis(model, editLine);

        // After a typing pause, re-run analysis without suppression so that
        // real errors on the edited line become visible.
        if (editLine >= 0) {
            ScheduledFuture<?> prev = delayedAnalysis;
            if (prev != null) {
                prev.cancel(false);
            }
            delayedAnalysis = SCHEDULED_EXECUTOR.schedule(() -> {
                lastEditLine = -1;
                restartJavaAnalysis(model, -1);
            }, DIAGNOSTIC_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void ensureSource(CodeTextModel model) {
        if (context.source() == null) {
            context.setSource(SourceUtils.getFullText(model));
        }
    }

    private synchronized void restartJavaAnalysis(CodeTextModel model, int editLine) {
        ensureSource(model);
        SourceContext.Snapshot snapshot = context.snapshot();
        if (snapshot == null) {
            return;
        }
        if (javaAnalysisFuture != null && !javaAnalysisFuture.isDone()) {
            javaAnalysisFuture.cancel(true);
        }
        final int expectedLineCount = model.size();

        javaAnalysisFuture = javaProcessor.process(snapshot, editLine)
                .thenAcceptAsync(analysisResults -> {
                    // Discard stale results if the model changed since analysis started
                    if (model.size() != expectedLineCount) {
                        return;
                    }
                    List<MarkedDecoration> newDecorations = TextPosResult.toMarkedDecorations(analysisResults, model);
                    if (MarkedDecoration.sameDecorations(newDecorations, javaDecorations)) {
                        return; // nothing changed, skip re-render
                    }
                    javaDecorations = newDecorations;
                    model.fireStyleChangeEvent(TextPos.ZERO, model.getDocumentEnd());
                }, FX_EXECUTOR);
    }

    /**
     * Clips overlapping squiggly decorations so that no two wavy underlines
     * cover the same character range.  When decorations overlap, errors
     * (higher severity) take priority over warnings.
     */
    private static List<LineDecoration> clipOverlappingSquigglies(List<LineDecoration> sorted) {
        if (sorted.size() <= 1) {
            return sorted;
        }
        // Sort by start, then errors before warnings at the same start position
        String errorStyle = TokenCategory.ERROR.style();
        List<LineDecoration> work = new ArrayList<>(sorted);
        work.sort(Comparator.comparingInt(LineDecoration::start)
                .thenComparing(d -> errorStyle.equals(d.style()) ? 0 : 1));

        List<LineDecoration> result = new ArrayList<>();
        result.add(work.getFirst());
        for (int i = 1; i < work.size(); i++) {
            LineDecoration prev = result.getLast();
            LineDecoration curr = work.get(i);
            if (curr.start() < prev.end()) {
                // Overlap: clip current to start after previous
                if (curr.end() > prev.end()) {
                    result.add(new LineDecoration(prev.end(), curr.end(), curr.style()));
                }
                // else fully contained – skip
            } else {
                result.add(curr);
            }
        }
        return result;
    }

    /**
     * Returns all squiggly (error/warning) decorations that touch the given line.
     */
    private List<MarkedDecoration> squiggliesOnLine(int lineIndex) {
        return javaDecorations.stream()
                .filter(md -> md.style() != null
                        && md.style().startsWith(TokenCategory.SQUIGGLY_PREFIX)
                        && md.touchesLine(lineIndex))
                .toList();
    }

    private void fireCaretHighlightChange(CodeTextModel model, int[] range) {
        if (model == null || range == null) {
            return;
        }
        int minLine = range[0];
        int maxLine = Math.clamp(model.size() - 1, 0, range[1]);
        String last = model.getPlainText(maxLine);
        model.fireStyleChangeEvent(
                TextPos.ofLeading(minLine, 0),
                TextPos.ofLeading(maxLine, last == null ? 0 : last.length()));
    }

    /**
     * Returns the error/warning message for the squiggly decoration at the given
     * paragraph index and character offset, or {@code null} if no diagnostic covers
     * that position.
     *
     * @param lineIndex  the 0-based paragraph index
     * @param charOffset the character offset within the paragraph
     * @return the diagnostic message, or null
     */
    public String getErrorMessageAt(int lineIndex, int charOffset) {
        for (MarkedDecoration md : squiggliesOnLine(lineIndex)) {
            if (md.message() == null) {
                continue;
            }
            int localStart = md.start().getIndex() == lineIndex ? md.start().getOffset() : 0;
            int localEnd = md.end().getIndex() == lineIndex ? md.end().getOffset() : Integer.MAX_VALUE;

            if (charOffset >= localStart && charOffset < localEnd) {
                return md.message();
            }
        }
        return null;
    }

    /**
     * Returns a combined error/warning message for all squiggly decorations on the
     * given paragraph, or {@code null} if no diagnostics touch that line.
     *
     * @param lineIndex the 0-based paragraph index
     * @return the diagnostic message(s), or null
     */
    public String getErrorMessagesForLine(int lineIndex) {
        List<String> messages = squiggliesOnLine(lineIndex).stream()
                .map(MarkedDecoration::message)
                .filter(Objects::nonNull)
                .toList();
        return messages.isEmpty() ? null : String.join("\n\n", messages);
    }

    /**
     * Returns whether the diagnostic on the given line is an error (red) or a warning (orange).
     *
     * @param lineIndex the 0-based paragraph index
     * @return {@code "error"}, {@code "warning"}, or {@code null}
     */
    public String getErrorSeverityOnLine(int lineIndex) {
        for (MarkedDecoration md : squiggliesOnLine(lineIndex)) {
            if (TokenCategory.ERROR.style().equals(md.style())) {
                return "error";
            }
            return "warning";
        }
        return null;
    }
}
