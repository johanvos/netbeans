package com.gluonhq.netbeans.nbfx.editor.decoration;

import jfx.incubator.scene.control.richtext.Marker;

import java.util.List;
import java.util.Objects;

/**
 * A decoration tracked by {@link Marker}s that auto-update across edits, using markers global coordinates, defining
 * a region from the start marker to the end marker, with a CSS inline style and an optional message (e.g. for errors).
 * @param start the start marker of the decoration
 * @param end the end marker of the decoration
 * @param style the CSS inline style to apply to the region
 * @param message an optional message to show on hover
 */
public record MarkedDecoration(Marker start, Marker end, String style, String message) {

    /**
     * Returns a line-local {@link LineDecoration} for the given paragraph.
     * */
    public LineDecoration toLineLocal(int lineIndex, int lineLength) {
        int localStart = start.getIndex() == lineIndex ? start.getOffset() : 0;
        int localEnd = end.getIndex() == lineIndex ? end.getOffset() : lineLength;
        return new LineDecoration(localStart, localEnd, style);
    }

    /** True if this decoration overlaps the given paragraph index. */
    public boolean touchesLine(int lineIndex) {
        return start.getIndex() <= lineIndex && end.getIndex() >= lineIndex;
    }

    /**
     * Computes the inclusive {@code [minLine, maxLine]} paragraph range touched
     * by the given decoration lists.
     *
     * @param lists one or more decoration lists (may be empty)
     * @return a two-element {@code int[]} <code>{ minLine, maxLine }</code>, or
     *         {@code null} when every input list is empty
     */
    @SafeVarargs
    public static int[] lineRange(List<MarkedDecoration>... lists) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (List<MarkedDecoration> list : lists) {
            for (MarkedDecoration d : list) {
                int s = d.start.getIndex();
                int e = d.end.getIndex();
                if (s < min) min = s;
                if (e > max) max = e;
            }
        }
        return min <= max ? new int[] { min, max } : null;
    }

    /**
     * Compares two {@link MarkedDecoration} lists by their current
     * {@link jfx.incubator.scene.control.richtext.TextPos} values and styles.
     *
     * @return {@code true} if both lists represent the same decorations
     */
    public static boolean sameDecorations(List<MarkedDecoration> a, List<MarkedDecoration> b) {
        if (Objects.requireNonNull(a).size() != Objects.requireNonNull(b).size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            MarkedDecoration ma = a.get(i);
            MarkedDecoration mb = b.get(i);
            if (!ma.start.getTextPos().equals(mb.start.getTextPos())) {
                return false;
            }
            if (!ma.end.getTextPos().equals(mb.end.getTextPos())) {
                return false;
            }
            if (!ma.style.equals(mb.style)) {
                return false;
            }
            if (!Objects.equals(ma.message, mb.message)) {
                return false;
            }
        }
        return true;
    }

}
