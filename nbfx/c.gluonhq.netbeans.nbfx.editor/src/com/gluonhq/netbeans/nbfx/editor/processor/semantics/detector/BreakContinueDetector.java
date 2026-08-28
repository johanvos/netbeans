package com.gluonhq.netbeans.nbfx.editor.processor.semantics.detector;

import com.sun.source.tree.BreakTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Name;
import org.netbeans.api.java.lexer.JavaTokenId;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.api.lexer.TokenSequence;

/**
 * Mark-occurrences for {@code break} / {@code continue} statements and for labeled statements.
 *
 * <p>Ported from NetBeans' {@code MarkOccurrencesHighlighterBase.detectBreakOrContinueTarget}
 * and {@code detectLabel}.</p>
 */
final class BreakContinueDetector {

    private BreakContinueDetector() {}

    /**
     * Highlights the target keyword + closing brace for the given break/continue,
     * and, if the caret is on the label identifier, the label occurrences.
     *
     * @return the list of {@code HighlightSpan} spans, or {@code null} if the target tree cannot be resolved
     */
    static List<HighlightSpan> detectBreakOrContinueTarget(CompilationInfo info, TreePath breakOrContinue, int caret) {
        Tree target = info.getTreeUtilities().getBreakContinueTargetTree(breakOrContinue);
        if (target == null) {
            return null;
        }
        List<HighlightSpan> result = new ArrayList<>();

        TokenSequence<JavaTokenId> sequence = info.getTokenHierarchy().tokenSequence(JavaTokenId.language());
        if (sequence == null) {
            return result;
        }

        // First token of the target (labeled-statement -> its label; loop/switch -> keyword; switch-expr -> "switch").
        int targetStart = (int) info.getTrees().getSourcePositions().getStartPosition(info.getCompilationUnit(), target);
        sequence.move(targetStart);
        if (sequence.moveNext()) {
            result.add(new HighlightSpan(sequence.offset(), sequence.offset() + sequence.token().length()));
        }

        // Unwrap a labeled statement to its underlying statement.
        StatementTree stmt = null;
        ExpressionTree expr = null;
        if (target instanceof StatementTree st) {
            stmt = target.getKind() == Kind.LABELED_STATEMENT ? ((LabeledStatementTree) target).getStatement() : st;
        } else if (target instanceof ExpressionTree et) {
            expr = et;
        }

        Tree block = null;
        if (stmt != null) {
            switch (stmt.getKind()) {
                case SWITCH -> block = stmt;
                case WHILE_LOOP -> {
                    if (((WhileLoopTree) stmt).getStatement().getKind() == Kind.BLOCK)
                        block = ((WhileLoopTree) stmt).getStatement();
                }
                case FOR_LOOP -> {
                    if (((ForLoopTree) stmt).getStatement().getKind() == Kind.BLOCK)
                        block = ((ForLoopTree) stmt).getStatement();
                }
                case ENHANCED_FOR_LOOP -> {
                    if (((EnhancedForLoopTree) stmt).getStatement().getKind() == Kind.BLOCK)
                        block = ((EnhancedForLoopTree) stmt).getStatement();
                }
                case DO_WHILE_LOOP -> {
                    if (((DoWhileLoopTree) stmt).getStatement().getKind() == Kind.BLOCK)
                        block = ((DoWhileLoopTree) stmt).getStatement();
                }
                default -> { /* no trailing brace to highlight */ }
            }
        } else if (expr != null) {
            // JDK-12+ switch expression.
            block = expr;
        }

        if (block != null) {
            int blockEnd = (int) info.getTrees().getSourcePositions().getEndPosition(info.getCompilationUnit(), block);
            sequence.move(blockEnd);
            if (sequence.movePrevious() && sequence.token().id() == JavaTokenId.RBRACE) {
                result.add(new HighlightSpan(sequence.offset(), sequence.offset() + sequence.token().length()));
            }
        }

        // If the caret is on the label name of `break/continue LABEL;`, also highlight label usages.
        if (target.getKind() == Kind.LABELED_STATEMENT) {
            HighlightSpan.ofIdentifier(info, breakOrContinue).ifPresent(span -> {
                if (span.contains(caret)) {
                    TreePath labelPath = info.getTrees().getPath(info.getCompilationUnit(), target);
                    if (labelPath != null) {
                        result.addAll(detectLabel(info, labelPath));
                    }
                }
            });
        }

        return result;
    }

    static List<HighlightSpan> detectLabelTarget(CompilationInfo info, TreePath labeledStatement, int caretOffset) {
        return HighlightSpan.ofIdentifier(info, labeledStatement)
                .filter(span -> span.contains(caretOffset))
                .map(span -> {
                    List<HighlightSpan> spans = new ArrayList<>(detectLabel(info, labeledStatement));
                    spans.add(span);
                    return spans;
                }).orElse(null);
    }

    /**
     * Highlights every {@code break}/{@code continue} label inside the given
     * labeled statement whose name matches the statement's label.
     */
    private static List<HighlightSpan> detectLabel(CompilationInfo info, TreePath labeledStatement) {
        List<HighlightSpan> result = new ArrayList<>();
        if (labeledStatement.getLeaf().getKind() != Kind.LABELED_STATEMENT) {
            return result;
        }
        Name label = ((LabeledStatementTree) labeledStatement.getLeaf()).getLabel();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitBreak(BreakTree node, Void p) {
                if (node.getLabel() != null && label.contentEquals(node.getLabel())) {
                    HighlightSpan.ofIdentifier(info, getCurrentPath()).ifPresent(result::add);
                }
                return super.visitBreak(node, p);
            }
            @Override
            public Void visitContinue(ContinueTree node, Void p) {
                if (node.getLabel() != null && label.contentEquals(node.getLabel())) {
                    HighlightSpan.ofIdentifier(info, getCurrentPath()).ifPresent(result::add);
                }
                return super.visitContinue(node, p);
            }
        }.scan(labeledStatement, null);
        return result;
    }
}

