package com.gluonhq.netbeans.nbfx.editor.completion.support;

/**
 * Token-aware textual scanner shared by completion helpers.
 * <p>Walks Java source skipping over line/block comments and string/char literals (including
 * escape sequences), invoking a visitor for every code character.</p>
 */
final class JavaSourceTextScanner {

    @FunctionalInterface
    interface CharVisitor {
        void visit(char ch, int index);
    }

    private JavaSourceTextScanner() {
    }

    /**
     * Returns the last non-whitespace offset at or before {@code caretOffset - 1}. The
     * resulting position is what semantic queries pass to {@link com.sun.source.util.Trees#getScope}
     * / {@code pathFor(…)} so the lookup lands on a real token instead of trailing whitespace.
     */
    static int resolveSemanticOffset(String source, int caretOffset) {
        if (source == null || source.isEmpty()) {
            return 0;
        }
        int index = Math.max(0, Math.min(caretOffset, source.length()) - 1);
        while (index > 0 && Character.isWhitespace(source.charAt(index))) {
            index--;
        }
        return index;
    }

    /**
     * Invokes {@code visitor} for each character in {@code source[start, end)} that is not
     * inside a comment or string/char literal. Out-of-range bounds are clamped silently.
     */
    static void forEachSourceChar(String source, int start, int end, CharVisitor visitor) {
        if (source == null || source.isEmpty()) {
            return;
        }
        int safeStart = Math.clamp(start, 0, source.length());
        int safeEnd = Math.clamp(end, safeStart, source.length());
        boolean inLine = false, inBlock = false, inString = false, inChar = false;
        for (int i = safeStart; i < safeEnd; i++) {
            char c = source.charAt(i);
            if (inLine) {
                if (c == '\n') inLine = false;
                continue;
            }
            if (inBlock) {
                if (c == '*' && i + 1 < safeEnd && source.charAt(i + 1) == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                if (c == '\\' && i + 1 < safeEnd) { i++; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (inChar) {
                if (c == '\\' && i + 1 < safeEnd) { i++; continue; }
                if (c == '\'') inChar = false;
                continue;
            }
            if (c == '/' && i + 1 < safeEnd) {
                char n = source.charAt(i + 1);
                if (n == '/') { inLine = true; i++; continue; }
                if (n == '*') { inBlock = true; i++; continue; }
            }
            if (c == '"') { inString = true; continue; }
            if (c == '\'') { inChar = true; continue; }
            visitor.visit(c, i);
        }
    }

    /**
     * Returns the brace depth at {@code endExclusive} — the number of unmatched
     * <code>{</code> in {@code source[0, endExclusive)}. Braces appearing inside comments,
     * string literals, or character literals are <em>not</em> counted, which is the whole
     * point of going through {@link #forEachSourceChar} rather than a plain character
     * loop. Used by the member-completion fallback to keep local-variable matches in the
     * same scope as the usage.
     */
    static int braceDepthAt(String source, int endExclusive) {
        int[] depth = {0};
        forEachSourceChar(source, 0, endExclusive, (c, i) -> {
            if (c == '{') depth[0]++;
            else if (c == '}' && depth[0] > 0) depth[0]--;
        });
        return depth[0];
    }

    /**
     * Counts opening parens in {@code source[0, end)} that have no matching {@code )} in
     * the same range, ignoring parens inside comments/literals.
     */
    static int countUnmatchedOpenParens(String source, int end) {
        int[] depth = {0};
        forEachSourceChar(source, 0, end, (c, i) -> {
            if (c == '(') depth[0]++;
            else if (c == ')' && depth[0] > 0) depth[0]--;
        });
        return depth[0];
    }

    /**
     * Returns the offset of the {@code )} that matches the {@code (} at
     * {@code openParenOffset}, or {@code -1} when no matching close paren exists in
     * {@code source}. Parens inside comments or string/char literals do not count.
     */
    static int findMatchingCloseParen(String source, int openParenOffset) {
        if (source == null || openParenOffset < 0 || openParenOffset >= source.length()
                || source.charAt(openParenOffset) != '(') {
            return -1;
        }
        int[] depth = {0};
        int[] result = {-1};
        forEachSourceChar(source, openParenOffset, source.length(), (c, i) -> {
            if (result[0] >= 0) {
                return;
            }
            if (c == '(') {
                depth[0]++;
            } else if (c == ')') {
                depth[0]--;
                if (depth[0] == 0) {
                    result[0] = i;
                }
            }
        });
        return result[0];
    }

    /**
     * Returns the zero-based argument index the caret sits in for the argument list that
     * starts at {@code argsStart}. Counts only top-level commas (i.e. at depth zero with
     * respect to parens/brackets/braces) between {@code argsStart} and {@code caretOffset}.
     */
    static int countCommaArgIndex(String source, int argsStart, int caretOffset) {
        int[] parens = {0}, brackets = {0}, braces = {0}, idx = {0};
        forEachSourceChar(source, argsStart, caretOffset, (c, i) -> {
            switch (c) {
                case '(' -> parens[0]++;
                case ')' -> { if (parens[0] > 0) parens[0]--; }
                case '[' -> brackets[0]++;
                case ']' -> { if (brackets[0] > 0) brackets[0]--; }
                case '{' -> braces[0]++;
                case '}' -> { if (braces[0] > 0) braces[0]--; }
                case ',' -> {
                    if (parens[0] == 0 && brackets[0] == 0 && braces[0] == 0) {
                        idx[0]++;
                    }
                }
                default -> { /* keep scanning */ }
            }
        });
        return idx[0];
    }

    /**
     * Returns the offset of the last non-whitespace character at or before {@code index},
     * or {@code -1} when only whitespace (or out-of-range positions) precedes {@code index}.
     * Whitespace is read raw — comments and string literals are <em>not</em> skipped, which
     * is what every caller wants (we walk backward over a single line of code looking for
     * the immediately preceding token).
     */
    static int skipWhitespaceBackward(String source, int index) {
        int i = index;
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) {
            i--;
        }
        return i;
    }
}
