package com.gluonhq.netbeans.nbfx.editor.processor.lex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.gluonhq.netbeans.nbfx.editor.decoration.LineDecoration;
import com.gluonhq.netbeans.nbfx.editor.decoration.TokenCategory;
import com.gluonhq.netbeans.nbfx.editor.processor.semantics.SourceContext;
import com.gluonhq.netbeans.nbfx.editor.processor.semantics.TextPosResult;
import jfx.incubator.scene.control.richtext.TextPos;
import org.netbeans.api.java.lexer.JavaTokenId;
import org.netbeans.api.lexer.Token;
import org.netbeans.api.lexer.TokenSequence;

/**
 * LexDecoration Processor is a lex parser, that finds keywords in a given text source,
 * and returns a list of {@link LineDecoration}s for those.
 */
public class LexDecorationProcessor {

    private static final Logger LOG = Logger.getLogger(LexDecorationProcessor.class.getName());

    /**
     * Java <em>contextual</em> keywords — they're lexed as {@link JavaTokenId#IDENTIFIER}
     * by the NetBeans Java lexer (no dedicated {@code JavaTokenId} entry) but should be
     * highlighted as keywords when they appear in a class/switch/method declaration.
     */
    private static final Set<String> CONTEXTUAL_KEYWORDS = Set.of(
            "sealed", "permits", "record", "yield");

    private final SourceContext context;
    private List<List<LineDecoration>> cachedLines;

    public LexDecorationProcessor(SourceContext context) {
        this.context = context;
    }

    /** Invalidates the cached per-line decorations. Called when the document content changes. */
    public void invalidate() {
        cachedLines = null;
    }

    /**
     * Returns the cached {@link LineDecoration}s for the given paragraph index.
     * If the cache is empty, the full source is re-tokenized first using
     * the context's source text.
     *
     * @param lineIndex  the paragraph index
     * @return decorations for the line, or an empty list if out of range
     */
    public List<LineDecoration> getLineDecorations(int lineIndex) {
        if (cachedLines == null) {
            cachedLines = analyze();
        }
        if (lineIndex < 0 || lineIndex >= cachedLines.size()) {
            return List.of();
        }
        return cachedLines.get(lineIndex);
    }

    /**
     * Finds the matching brace pair for the brace token adjacent to the caret,
     * and returns the single-character highlight ranges for the origin brace and,
     * when found, its partner.
     *
     * @param caret the current caret position in document coordinates
     * @return 0, 1 (unmatched origin) or 2 (origin + match) single-character
     *         {@link TextPosResult}s styled with the brace-match / brace-mismatch style
     */
    public List<TextPosResult> findBraceMatchResults(TextPos caret) {
        String source = context.source();
        if (source == null || source.isEmpty() || caret == null) {
            return List.of();
        }
        int[] lineStarts = context.lineStarts();
        int caretIndex = caret.index();
        int lineStart = caretIndex < lineStarts.length ? lineStarts[caretIndex] : source.length();
        int caretOffset = lineStart + caret.offset();

        BraceMatcher.BraceMatch bm = BraceMatcher.findMatch(context.hierarchy(), caretOffset);
        if (bm == null) {
            return List.of();
        }
        String style = bm.style();
        TextPosResult origin = TextPosResult.from(bm.origin(), bm.origin() + 1, lineStarts, style);
        if (!bm.matched()) {
            return List.of(origin);
        }
        TextPosResult match = TextPosResult.from(bm.match(), bm.match() + 1, lineStarts, style);
        return List.of(origin, match);
    }

    private List<List<LineDecoration>> analyze() {
        if (context.source() == null) {
            return new ArrayList<>();
        }
        int[] lineStarts = context.lineStarts();
        int[] lineLengths = context.lineLengths();
        int lineCount = lineStarts.length;

        // Initialize per-line lists
        List<List<LineDecoration>> results = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            results.add(new ArrayList<>());
        }

        TokenSequence<JavaTokenId> tokenSequence = context.hierarchy().tokenSequence(JavaTokenId.language());
        if (tokenSequence != null) {
            tokenSequence.moveStart();
            while (tokenSequence.moveNext()) {
                try {
                    Token<JavaTokenId> token = tokenSequence.token();
                    JavaTokenId id = token.id();
                    String category = id.primaryCategory();
                    TokenCategory tc = TokenCategory.fromCategory(category);
                    if (id == JavaTokenId.IDENTIFIER && CONTEXTUAL_KEYWORDS.contains(token.text().toString())) {
                        tc = TokenCategory.KEYWORD;
                    }
                    if (tc == null || tc.style() == null) {
                        continue;
                    }
                    int globalStart = tokenSequence.offset();
                    int globalEnd = globalStart + token.length();
                    Map<Integer, List<LineDecoration>> map = TextPosResult.toLineDecorationMap(
                            globalStart, globalEnd, lineStarts, lineLengths, tc.style());
                    map.forEach((line, list) -> results.get(line).addAll(list));
                } catch (Exception ex) {
                    LOG.log(Level.WARNING, "Error tokenizing source", ex);
                }
            }
        }
        return results;
    }

}
