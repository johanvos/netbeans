package com.gluonhq.netbeans.nbfx.editor.processor.semantics;

import com.gluonhq.netbeans.nbfx.editor.decoration.LineDecoration;
import com.gluonhq.netbeans.nbfx.editor.decoration.MarkedDecoration;
import com.gluonhq.netbeans.nbfx.editor.processor.SourceUtils;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.CodeTextModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A styled range in {@link TextPos} document coordinates
 */
public record TextPosResult(TextPos start, TextPos end, String style, String message) {

    /**
     * Converts a list of TextPosResult results into a list of {@link MarkedDecoration}s.
     *
     * @param results the results from a processor, containing TextPos ranges and styles
     * @param model           the model to create markers from
     * @return an immutable list of marker-tracked decorations
     */
    public static List<MarkedDecoration> toMarkedDecorations(List<TextPosResult> results, CodeTextModel model) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<MarkedDecoration> list = new ArrayList<>(results.size());
        for (TextPosResult pr : results) {
            if (pr.style() == null) {
                continue;
            }
            list.add(new MarkedDecoration(model.getMarker(pr.start()), model.getMarker(pr.end()), pr.style(), pr.message()));
        }
        return List.copyOf(list);
    }

    /**
     * Creates a map of line numbers to lists of {@link LineDecoration}s for the given global offset range.
     * @param start the global start coordinate
     * @param end the global end coordinate
     * @param lineStarts an array with the global offset of the start of each line
     * @param lineLengths an array with the length of each line
     * @param style the style of the decoration
     * @return a map of line numbers and a list of line decorations per line
     */
    public static Map<Integer, List<LineDecoration>> toLineDecorationMap(int start, int end, int[] lineStarts, int[] lineLengths, String style) {
        int startLine = SourceUtils.findLine(start, lineStarts);
        int endLine = SourceUtils.findLine(Math.max(end - 1, start), lineStarts);

        Map<Integer, List<LineDecoration>> map = new HashMap<>();
        for (int line = startLine; line <= endLine; line++) {
            int localStart = Math.max(0, start - lineStarts[line]);
            int localEnd = Math.min(lineLengths[line], end - lineStarts[line]);
            if (localEnd > localStart) {
                map.computeIfAbsent(line, _ -> new ArrayList<>()).add(new LineDecoration(localStart, localEnd, style));
            }
        }
        return Map.copyOf(map);
    }

    public static TextPosResult from(int start, int end, int[] lineStarts, String style) {
        return from(start, end, lineStarts, style, null);
    }

    public static TextPosResult from(int start, int end, int[] lineStarts, String style, String message) {
        return new TextPosResult(globalToTextPos(start, lineStarts), globalToTextPos(end, lineStarts), style, message);
    }

    private static TextPos globalToTextPos(int globalOffset, int[] lineStarts) {
        int line = SourceUtils.findLine(globalOffset, lineStarts);
        return TextPos.ofLeading(line, globalOffset - lineStarts[line]);
    }
}
