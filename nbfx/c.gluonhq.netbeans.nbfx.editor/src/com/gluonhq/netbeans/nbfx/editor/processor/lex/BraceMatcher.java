package com.gluonhq.netbeans.nbfx.editor.processor.lex;

import com.gluonhq.netbeans.nbfx.editor.decoration.TokenCategory;
import org.netbeans.api.java.lexer.JavaTokenId;
import org.netbeans.api.lexer.Token;
import org.netbeans.api.lexer.TokenHierarchy;
import org.netbeans.api.lexer.TokenSequence;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Finds matching brace/bracket/parenthesis pairs in a Java {@link TokenHierarchy}.
 * <p>
 * It picks the brace token immediately before or after the caret and walks the {@link TokenSequence}
 * forward or backward counting nesting depth until the matching partner is found.</p>
 */
public final class BraceMatcher {

    /**
     * Result of a brace-matching pair.
     *
     * @param origin global position of the brace character at the caret
     * @param match global position of the matching partner, or {@code -1} when unmatched
     */
    public record BraceMatch(int origin, int match) {

        /** {@code true} if a matching partner was found */
        public boolean matched() {
            return match != -1;
        }

        public String style() {
            return matched() ? TokenCategory.BRACE_MATCH.style() : TokenCategory.BRACE_MISMATCH.style();
        }
    }

    private static final Set<JavaTokenId> LEFT_TOKENS =
            EnumSet.of(JavaTokenId.LBRACE, JavaTokenId.LBRACKET, JavaTokenId.LPAREN);

    /** Map of brace tokens to their partners, in both directions. */
    private static final Map<JavaTokenId, JavaTokenId> PAIRS_MAP = new EnumMap<>(Map.of(
        JavaTokenId.LBRACE,   JavaTokenId.RBRACE, JavaTokenId.RBRACE,   JavaTokenId.LBRACE,
        JavaTokenId.LBRACKET, JavaTokenId.RBRACKET, JavaTokenId.RBRACKET, JavaTokenId.LBRACKET,
        JavaTokenId.LPAREN,   JavaTokenId.RPAREN, JavaTokenId.RPAREN,   JavaTokenId.LPAREN));

    private BraceMatcher() {}

    /**
     * Finds the brace pair for the token adjacent to the given caret offset.
     *
     * @param hierarchy the Java token hierarchy for the current source
     * @param caretOffset the global caret offset
     * @return a {@link BraceMatch} if a brace token touches the caret, or {@code null} otherwise
     */
    public static BraceMatch findMatch(TokenHierarchy<?> hierarchy, int caretOffset) {
        if (hierarchy == null || caretOffset < 0) {
            return null;
        }
        TokenSequence<JavaTokenId> sequence = hierarchy.tokenSequence(JavaTokenId.language());
        if (sequence == null) {
            return null;
        }

        // Token ending exactly at the caret (behind the cursor).
        sequence.move(caretOffset);
        if (sequence.movePrevious()) {
            Token<JavaTokenId> token = sequence.token();
            if (sequence.offset() + token.length() == caretOffset && PAIRS_MAP.containsKey(token.id())) {
                return matchFrom(sequence, token.id(), sequence.offset());
            }
        }

        // Token containing / starting at the caret (in front of the cursor).
        sequence.move(caretOffset);
        if (sequence.moveNext()) {
            Token<JavaTokenId> token = sequence.token();
            if (sequence.offset() <= caretOffset && caretOffset < sequence.offset() + token.length() && PAIRS_MAP.containsKey(token.id())) {
                return matchFrom(sequence, token.id(), sequence.offset());
            }
        }

        return null;
    }

    private static BraceMatch matchFrom(TokenSequence<JavaTokenId> sequence, JavaTokenId braceId, int braceOffset) {
        // Position the sequence at the brace token itself.
        sequence.move(braceOffset);
        if (!sequence.moveNext()) {
            // can't move to the next token, as there are no more tokens: no match
            return new BraceMatch(braceOffset, -1);
        }

        int nestLevel = 1;
        boolean forward = LEFT_TOKENS.contains(braceId);
        JavaTokenId partner = PAIRS_MAP.get(braceId);
        while (forward ? sequence.moveNext() : sequence.movePrevious()) {
            JavaTokenId id = sequence.token().id();
            if (id == braceId) {
                nestLevel++; // found another brace of same type, level in
            } else if (id == partner) {
                nestLevel--; // found a possible partner, level out
                if (nestLevel == 0) {
                    // if the partner is found at the same nesting level: there is a match
                    return new BraceMatch(braceOffset, sequence.offset());
                }
            }
        }
        // no match found
        return new BraceMatch(braceOffset, -1);
    }
}

