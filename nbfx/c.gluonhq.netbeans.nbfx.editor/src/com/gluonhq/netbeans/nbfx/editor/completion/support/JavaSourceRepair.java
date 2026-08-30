package com.gluonhq.netbeans.nbfx.editor.completion.support;

import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaSourceTextScanner.countUnmatchedOpenParens;

/**
 * Source-buffer repair helpers used by semantic completion queries to coax the Java
 * parser into producing a usable tree when the user's buffer is mid-edit (unterminated
 * statement, unmatched braces, unclosed argument list, …).
 *
 * <p>All methods preserve offsets at and before the inserted point so that probes
 * computed by callers (anchor, caret, prefix-end) stay valid.</p>
 */
public final class JavaSourceRepair {

    private JavaSourceRepair() {
    }

    /**
     * Returns {@code source} with a {@code ;} inserted right after the identifier
     * being typed, plus any closing braces needed to balance the buffer, so the
     * parser sees a complete compilation unit instead of an erroneous trailing
     * token chain. No-op when a {@code ;} already follows the identifier (after
     * any whitespace) and the buffer has no unmatched <code>{</code>.
     *
     * <p>When the caret sits inside an unmatched {@code (} (e.g. inside a parameter
     * list, an annotation argument list, or an unterminated invocation), the
     * {@code ;} insertion is skipped: {@code start(Sta;)} is not valid Java and
     * throws javac into an unpredictable error-recovery mode. The terminator hack
     * is only meaningful at statement level.</p>
     */
    static String synthesizeIdentifierTerminator(String source, int anchor, String prefix) {
        if (source == null || source.isEmpty() || prefix == null || prefix.isEmpty()) {
            return source;
        }
        int n = source.length();
        if (anchor < 0 || anchor > n) {
            return source;
        }
        // Walk past any identifier chars that already follow the typed prefix in the
        // buffer (the user may have moved the caret back into the middle of `nexa`).
        int end = Math.min(anchor + prefix.length(), n);
        while (end < n && Character.isJavaIdentifierPart(source.charAt(end))) {
            end++;
        }
        int after = end;
        while (after < n && Character.isWhitespace(source.charAt(after))) {
            after++;
        }
        boolean insideParens = JavaSourceTextScanner.countUnmatchedOpenParens(source, end) > 0;
        boolean needsSemicolon = !insideParens && !(after < n && source.charAt(after) == ';');
        int unmatchedBraces = JavaSourceTextScanner.braceDepthAt(source, n);
        if (!needsSemicolon && unmatchedBraces == 0) {
            return source;
        }
        StringBuilder sb = new StringBuilder(n + 1 + unmatchedBraces);
        sb.append(source, 0, end);
        if (needsSemicolon) {
            sb.append(';');
        }
        sb.append(source, end, n);
        sb.repeat('}', unmatchedBraces);
        return sb.toString();
    }

    /**
     * Returns {@code source} with {@code ");"} (and any additional {@code ")"} needed to
     * balance unmatched opening parens in the prefix) inserted at the caret, so that an
     * unterminated invocation like {@code add(} can still be parsed into a usable
     * {@link com.sun.source.tree.MethodInvocationTree}.
     * <p>Offsets before the caret are not perturbed because text is only inserted from
     * the caret onward.</p>
     */
    public static String synthesizeBalancedInvocation(String source, int caretOffset) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        int caret = Math.clamp(caretOffset, 0, source.length());
        int parens = countUnmatchedOpenParens(source, caret);
        if (parens <= 0) {
            return source;
        }
        StringBuilder sb = new StringBuilder(source.length() + parens + 1);
        sb.append(source, 0, caret);
        sb.repeat(')', parens);
        sb.append(';');
        sb.append(source, caret, source.length());
        return sb.toString();
    }
}
