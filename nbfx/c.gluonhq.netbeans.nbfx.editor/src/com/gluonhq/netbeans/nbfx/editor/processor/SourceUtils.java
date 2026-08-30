package com.gluonhq.netbeans.nbfx.editor.processor;

import java.util.Objects;

import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.CodeTextModel;

/**
 * Shared utilities for source-text processing.
 */
public final class SourceUtils {

    private SourceUtils() {}

    /**
     * Precompute character offsets of each line start.
     * A line is delimited by {@code '\n'}.
     *
     * @param source the full source text
     * @return an array where {@code lineStarts[i]} is the character offset
     *         of the first character in line {@code i}
     */
    public static int[] computeLineStarts(String source) {
        int count = 1;
        for (int i = 0; i < Objects.requireNonNull(source).length(); i++) {
            if (source.charAt(i) == '\n') count++;
        }
        int[] starts = new int[count];
        int idx = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                starts[idx++] = i + 1;
            }
        }
        return starts;
    }

    /**
     * Binary search for the 0-based line index that contains the given
     * global character offset.
     *
     * @param globalOffset the character offset in the full source text
     * @param lineStarts   array produced by {@link #computeLineStarts(String)}
     * @return the 0-based line index
     */
    public static int findLine(int globalOffset, int[] lineStarts) {
        int low = 0, high = Objects.requireNonNull(lineStarts).length - 1;
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (lineStarts[mid] <= globalOffset) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    /**
     * Finds the first whole-word occurrence of {@code word} in {@code source}.
     * A "whole word" means the character before and after the match (if any)
     * is not a Java identifier part.
     *
     * @param source the text to search
     * @param word   the word to find
     * @return the start index, or {@code -1} if not found
     */
    public static int findWholeWord(String source, String word) {
        int index = source.indexOf(word);
        while (index >= 0) {
            boolean leftOk = index == 0
                    || !Character.isJavaIdentifierPart(source.charAt(index - 1));
            boolean rightOk = (index + word.length() >= source.length())
                    || !Character.isJavaIdentifierPart(source.charAt(index + word.length()));
            if (leftOk && rightOk) {
                return index;
            }
            index = source.indexOf(word, index + 1);
        }
        return -1;
    }

    /**
     * Builds the full source text from the model paragraphs,
     * joining them with {@code '\n'}.
     *
     * @param model the code text model
     * @return the concatenated source text, or an empty string if the model is empty
     */
    public static String getFullText(CodeTextModel model) {
        int lineCount = Objects.requireNonNull(model).size();
        if (lineCount == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lineCount; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(model.getPlainText(i));
        }
        return sb.toString();
    }

    /**
     * Converts a model {@link TextPos} to global source offset.
     *
     * @param model code model
     * @param position text position in model coordinates
     * @return 0-based global character offset
     */
    public static int toGlobalOffset(CodeTextModel model, TextPos position) {
        if (position == null) {
            return 0;
        }
        int lineCount = Math.max(1, Objects.requireNonNull(model).size());
        int safeLine = Math.clamp(position.index(), 0, lineCount - 1);
        int safeOffset = Math.max(0, position.offset());
        return lineStartOffset(model, safeLine) + Math.min(safeOffset, lineLength(model, safeLine));
    }

    /**
     * Converts a global source offset to model {@link TextPos} coordinates.
     *
     * @param model code model
     * @param globalOffset 0-based global character offset
     * @return leading text position clamped to model bounds
     */
    public static TextPos toTextPos(CodeTextModel model, int globalOffset) {
        int remaining = Math.max(0, globalOffset);
        int lineCount = Math.max(1, Objects.requireNonNull(model).size());
        for (int line = 0; line < lineCount; line++) {
            int len = lineLength(model, line);
            if (remaining <= len || line == lineCount - 1) {
                return TextPos.ofLeading(line, Math.min(remaining, len));
            }
            remaining -= len + 1;
        }
        return TextPos.ZERO;
    }

    /**
     * Finds identifier start in plain source text for a caret offset.
     */
    public static int findIdentifierAnchor(String source, int caretOffset) {
        int i = Math.clamp(caretOffset, 0, Objects.requireNonNull(source).length());
        while (i > 0 && Character.isJavaIdentifierPart(source.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /**
     * Finds identifier start in model coordinates and returns global offset.
     */
    public static int findIdentifierAnchor(CodeTextModel model, TextPos position) {
        if (position == null) {
            return 0;
        }
        int lineCount = Math.max(1, Objects.requireNonNull(model).size());
        int safeLine = Math.clamp(position.index(), 0, lineCount - 1);
        String lineText = model.getPlainText(safeLine);
        if (lineText == null) {
            return lineStartOffset(model, safeLine);
        }
        int i = findIdentifierAnchor(lineText, position.offset());
        return lineStartOffset(model, safeLine) + i;
    }

    /**
     * Returns the Java identifier starting at {@code anchorOffset}, or {@code ""}
     * when the position is out of range or doesn't begin with an identifier
     * character. Used by the popup to highlight the prefix the user is typing.
     */
    public static String identifierAtAnchor(String source, int anchorOffset) {
        if (source == null || source.isBlank() || anchorOffset < 0 || anchorOffset >= source.length()) {
            return "";
        }
        char startChar = source.charAt(anchorOffset);
        if (!Character.isJavaIdentifierStart(startChar)) {
            return "";
        }
        int i = anchorOffset;
        while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) {
            i++;
        }
        return source.substring(anchorOffset, i);
    }

    private static int lineStartOffset(CodeTextModel model, int line) {
        int lineCount = Math.max(1, model.size());
        int safeLine = Math.clamp(line, 0, lineCount - 1);
        int global = 0;
        for (int i = 0; i < safeLine; i++) {
            global += lineLength(model, i) + 1;
        }
        return global;
    }

    private static int lineLength(CodeTextModel model, int line) {
        String text = model.getPlainText(line);
        return text == null ? 0 : text.length();
    }
}
