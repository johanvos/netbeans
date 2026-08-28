package com.gluonhq.netbeans.nbfx.editor.processor.semantics.detector;

import com.gluonhq.netbeans.nbfx.editor.decoration.TokenCategory;
import com.gluonhq.netbeans.nbfx.editor.processor.semantics.TextPosResult;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import org.netbeans.api.java.lexer.JavaTokenId;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.api.lexer.TokenSequence;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Entry point for Mark-Occurrences analysis.
 *
 * <p>Given a caret position inside a resolved {@link CompilationInfo}, finds the
 * list of {@link TextPosResult} spans to highlight.</p>
 *
 * <p>The analysis is taken care by a set of specialized detectors:</p>
 * <ul>
 *   <li>{@link MethodReturnDetector} — method return type / throws / catch parameter type.</li>
 *   <li>{@link OverridesDetector} — caret on a supertype in {@code extends} / {@code implements},
 *       or on those keywords themselves.</li>
 *   <li>{@link BreakContinueDetector} — {@code break} / {@code continue} statement or labeled-statement's label.</li>
 *   <li>{@link MethodVariableDetector} — default case: (classes, methods, fields, variables, …).</li>
 * </ul>
 *
 * <p>These detectors are based on
 * {@code org.netbeans.modules.java.editor.base.semantic.MarkOccurrencesHighlighterBase} and related classes.
 * </p>
 */
public final class OccurrencesDetectorDispatcher {

    private OccurrencesDetectorDispatcher() {}

    /**
     * Computes mark-occurrences highlights for the caret position in the given compilation.
     *
     * @param info a resolved {@link CompilationInfo}
     * @param caretOffset the caret offset
     * @param lineStarts per-line start offsets
     * @return the collected highlights as a list of {@link TextPosResult}s
     */
    public static List<TextPosResult> findOccurrences(CompilationInfo info, int caretOffset, int[] lineStarts) {
        List<TextPosResult> results = new ArrayList<>();

        TokenSequence<JavaTokenId> sequence = info.getTokenHierarchy().tokenSequence(JavaTokenId.language());
        if (sequence != null) {
            sequence.move(caretOffset);
            if (sequence.moveNext() && sequence.token().id() == JavaTokenId.IDENTIFIER && sequence.offset() == caretOffset) {
                caretOffset += 1;
            }
        }

        TreePath treePath = info.getTreeUtilities().pathFor(caretOffset);
        if (treePath == null) {
            return results;
        }
        if (treePath.getParentPath() != null && treePath.getParentPath().getLeaf().getKind() == Tree.Kind.ANNOTATED_TYPE) {
            treePath = treePath.getParentPath();
        }

        TreePath typePath = findTypePath(treePath);

        // 1. MethodReturnDetector — caret on return type or throws/catch.
        if (MethodReturnDetector.isPotentialMethodReturnSite(typePath)) {
            List<HighlightSpan> spans = new MethodReturnDetector(info).process(typePath, caretOffset);
            if (spans != null && !spans.isEmpty()) {
                addAllOccurrences(spans, results, lineStarts);
                return results;
            }
        }

        // 2. OverridesDetector — caret on an extends/implements type, or on those keywords.
        if (OverridesDetector.isPotentialOverrideSite(treePath, typePath)) {
            List<HighlightSpan> overrideSpans = OverridesDetector.process(info, treePath, typePath, caretOffset);
            if (overrideSpans != null && !overrideSpans.isEmpty()) {
                addAllOccurrences(overrideSpans, results, lineStarts);
                return results;
            }
        }

        // 3.  break / continue and labeled statements

        Tree.Kind leafKind = treePath.getLeaf().getKind();
        if (leafKind == Tree.Kind.BREAK || leafKind == Tree.Kind.CONTINUE) {
            List<HighlightSpan> spans = BreakContinueDetector.detectBreakOrContinueTarget(info, treePath, caretOffset);
            if (spans != null && !spans.isEmpty()) {
                addAllOccurrences(spans, results, lineStarts);
                return results;
            }
        } else if (leafKind == Tree.Kind.LABELED_STATEMENT) {
            List<HighlightSpan> spans = BreakContinueDetector.detectLabelTarget(info, treePath, caretOffset);
            if (spans != null && !spans.isEmpty()) {
                addAllOccurrences(spans, results, lineStarts);
                return results;
            }
        }

        // 4. element-based usages

        addAllOccurrences(MethodVariableDetector.process(info, treePath), results, lineStarts);
        return results;
    }

    private static final Set<Tree.Kind> TYPE_PATH_ELEMENTS = EnumSet.of(
            Tree.Kind.IDENTIFIER, Tree.Kind.PRIMITIVE_TYPE, Tree.Kind.PARAMETERIZED_TYPE,
            Tree.Kind.MEMBER_SELECT, Tree.Kind.ARRAY_TYPE);

    /**
     * Walks up the tree while the current leaf is a type-path element
     * (identifier / member-select / parameterized / array / primitive), to find
     * the type reference the caret is in
     */
    private static TreePath findTypePath(TreePath tp) {
        if (!TYPE_PATH_ELEMENTS.contains(tp.getLeaf().getKind())) {
            return null;
        }
        while (tp.getParentPath() != null && TYPE_PATH_ELEMENTS.contains(tp.getParentPath().getLeaf().getKind())) {
            tp = tp.getParentPath();
        }
        return tp;
    }

    private static void addAllOccurrences(List<HighlightSpan> spans, List<TextPosResult> results, int[] lineStarts) {
        if (spans == null) {
            return;
        }
        String style = TokenCategory.OCCURRENCE.style();
        results.addAll(spans.stream()
                .filter(s -> s != null && s.length() > 0)
                .map(s -> TextPosResult.from(s.start(), s.end(), lineStarts, style))
                .toList());
    }
}
