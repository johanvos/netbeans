package com.gluonhq.netbeans.nbfx.editor.processor.semantics.detector;

import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import org.netbeans.api.java.lexer.JavaTokenId;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.api.lexer.Token;
import org.netbeans.api.lexer.TokenSequence;

import java.util.Optional;

/**
 * A range {@code [start, end)} in the source file, used by the detectors as their unit of highlight output.
 *
 * @param start the start offset, inclusive
 * @param end the end offset, exclusive
 */
record HighlightSpan(int start, int end) {

    HighlightSpan {
        if (end < start) {
            throw new IllegalArgumentException("end (" + end + ") < start (" + start + ")");
        }
    }

    /** {@code true} if {@code offset} lies within {@code [start, end]} (end inclusive). */
    boolean contains(int offset) {
        return start <= offset && offset <= end;
    }

    int length() {
        return end - start;
    }

    /** Returns an {@code Optional} wrapping the resulting span, or empty if invalid. */
    static Optional<HighlightSpan> of(int start, int end) {
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        return Optional.of(new HighlightSpan(start, end));
    }

    /**
     * Gets the start and end offsets of the {@link Tree} and returns an optional of
     * the resulting span, or empty if invalid
     * */
    static Optional<HighlightSpan> ofTree(Tree tree, CompilationInfo info) {
        SourcePositions positions = info.getTrees().getSourcePositions();
        int start = (int) positions.getStartPosition(info.getCompilationUnit(), tree);
        int end = (int) positions.getEndPosition(info.getCompilationUnit(), tree);
        return of(start, end);
    }

    /**
     * Finds the first identifier token span inside the given tree, or empty if none is found.
     */
    static Optional<HighlightSpan> ofIdentifier(CompilationInfo info, TreePath path) {
        TokenSequence<JavaTokenId> sequence = info.getTokenHierarchy().tokenSequence(JavaTokenId.language());
        if (sequence == null) {
            return Optional.empty();
        }
        int start = (int) info.getTrees().getSourcePositions()
                .getStartPosition(info.getCompilationUnit(), path.getLeaf());
        int end = (int) info.getTrees().getSourcePositions()
                .getEndPosition(info.getCompilationUnit(), path.getLeaf());
        sequence.move(start);
        while (sequence.moveNext() && sequence.offset() < end) {
            if (sequence.token().id() == JavaTokenId.IDENTIFIER) {
                return of(sequence.offset(), sequence.offset() + sequence.token().length());
            }
            if (sequence.token().id() == JavaTokenId.SEMICOLON) {
                break;
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the identifier-token span of {@code name} searched forward from {@code start}, or empty if not found.
     */
    static Optional<HighlightSpan> ofDeclarationName(CompilationInfo info, String name, int start) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        TokenSequence<JavaTokenId> sequence = info.getTokenHierarchy().tokenSequence(JavaTokenId.language());
        if (sequence == null) {
            return Optional.empty();
        }
        sequence.move(start);
        while (sequence.moveNext()) {
            Token<JavaTokenId> token = sequence.token();
            if (token.id() == JavaTokenId.IDENTIFIER && name.contentEquals(token.text())) {
                return of(sequence.offset(), sequence.offset() + token.length());
            }
            if (token.id() == JavaTokenId.LBRACE || token.id() == JavaTokenId.SEMICOLON) {
                break;
            }
        }
        return Optional.empty();
    }
}

