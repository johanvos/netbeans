package com.gluonhq.netbeans.nbfx.editor.decoration;

import jfx.incubator.scene.control.richtext.model.RichParagraph;

import java.util.ArrayList;
import java.util.List;

/**
 * A decoration within a single paragraph: a region [start, end) with a CSS inline style.
 * Offsets are always line-local (relative to the paragraph's text).
 * @param start the start offset of the decoration, included
 * @param end the end offset of the decoration, excluded
 * @param style the CSS inline style to apply to the region
 */
public record LineDecoration(int start, int end, String style) {

    /**
     * Creates a {@link RichParagraph} out of a plain text line and a decoration result.
     *
     * @param text    The plain text line
     * @param results a decorationResult with a list of line decorations
     * @return a RichParagraph
     */
    public static RichParagraph getRichParagraph(String text, List<LineDecoration> results) {
        RichParagraph.Builder builder = RichParagraph.builder();
        if (results.isEmpty()) {
            builder.addSegment(text);
        } else {
            int len = text.length();
            int pos = 0;
            for (LineDecoration dec : results) {
                if (TokenCategory.isOverlayStyle(dec.style)) {
                    // Overlay decorations (squigglies, brace highlights) are rendered after text segments
                    continue;
                }

                int start = Math.max(dec.start, pos);
                int end = Math.min(dec.end, len);
                if (start >= len) {
                    break;
                }
                if (start > pos) {
                    builder.addSegment(text.substring(pos, start));
                }
                if (end > start) {
                    builder.addWithInlineStyle(text.substring(start, end), dec.style);
                }
                pos = end;
            }
            if (pos < len) {
                builder.addSegment(text.substring(pos));
            }

            // Add brace-match background highlights as overlays
            for (LineDecoration dec : results) {
                if (dec.style != null && dec.style.startsWith(TokenCategory.BRACE_PREFIX)) {
                    int s = Math.max(dec.start, 0);
                    int e = Math.min(dec.end, len);
                    if (s < len && e > s) {
                        builder.addHighlight(s, e - s, dec.style);
                    }
                }
            }

            // Add mark-occurrences background highlights as overlays
            for (LineDecoration dec : results) {
                if (dec.style != null && dec.style.startsWith(TokenCategory.OCCURRENCE_PREFIX)) {
                    int s = Math.max(dec.start, 0);
                    int e = Math.min(dec.end, len);
                    if (s < len && e > s) {
                        builder.addHighlight(s, e - s, dec.style);
                    }
                }
            }

            // Add squiggly underlines as overlays
            for (LineDecoration dec : results) {
                if (dec.style != null && dec.style.startsWith(TokenCategory.SQUIGGLY_PREFIX)) {
                    int s = Math.max(dec.start, 0);
                    int e = Math.min(dec.end, len);
                    if (s < len && e > s) {
                        builder.addWavyUnderline(s, e - s, dec.style);
                    }
                }
            }
        }
        return builder.build();
    }

    /**
     * Merge two lists of line decorations, where {@code primary} decorations take precedence
     * over {@code secondary} decorations in case of overlap.
     *
     * @param primary   the primary decorations (e.g. semantic methods)
     * @param secondary the secondary decorations (e.g. lex keywords, strings)
     * @return a merged list of decorations
     */
    public static List<LineDecoration> mergeDecorations(List<LineDecoration> primary, List<LineDecoration> secondary) {
        if (primary.isEmpty()) {
            return secondary;
        }
        if (secondary.isEmpty()) {
            return primary;
        }

        List<LineDecoration> secList = new ArrayList<>(secondary);
        List<LineDecoration> result = new ArrayList<>(primary.size() + secList.size());
        int priIndex = 0, secIndex = 0;

        while (priIndex < primary.size() && secIndex < secList.size()) {
            LineDecoration priResult = primary.get(priIndex);
            LineDecoration secResult = secList.get(secIndex);

            if (secResult.end <= priResult.start) {
                result.add(secResult);
                secIndex++;
            } else if (priResult.end <= secResult.start) {
                result.add(priResult);
                priIndex++;
            } else {
                // overlap, primary takes precedence
                if (secResult.start < priResult.start) {
                    result.add(new LineDecoration(secResult.start, priResult.start, secResult.style));
                }
                result.add(priResult);
                priIndex++;
                // skip secondary segments fully covered by this primary segment
                while (secIndex < secList.size() && secList.get(secIndex).end <= priResult.end) {
                    secIndex++;
                }
                // if the current secondary segment extends past the primary segment, trim it
                if (secIndex < secList.size() && secList.get(secIndex).start < priResult.end) {
                    LineDecoration trimmed = secList.get(secIndex);
                    secList.set(secIndex, new LineDecoration(priResult.end, trimmed.end, trimmed.style));
                }
            }
        }
        while (secIndex < secList.size()) {
            result.add(secList.get(secIndex++));
        }
        while (priIndex < primary.size()) {
            result.add(primary.get(priIndex++));
        }

        return result;
    }
}
