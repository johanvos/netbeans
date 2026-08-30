package com.gluonhq.netbeans.nbfx.launcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.scene.Node;
import javafx.scene.control.ToolBar;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

/**
 * Hosts the application tool bars in an explicit list of rows and lets the user reorder them by
 * dragging their leading drag handle. Tool bars are always packed to the left within a row and the
 * rows are packed to the top.
 * <p>A drag gesture can reorder tool bars inside a row, move one to another row, and create a brand-new row
 * (including above the first row and below the last).</p>
 * <p>While the gesture is in progress, the dragged tool bar is shown at its prospective drop slot as a
 * placeholder with a thin red dashed border, while its snapshot follows the cursor, and the other tool bars
 * move aside to preview the result.</p>
 * <p>The model is only changed when the drop actually completes, so a canceled drop leaves
 * everything untouched.</p>
 */
final class ToolBarContainer extends Pane {

    private static final DataFormat TOOLBAR = new DataFormat("application/x-nbfx-toolbar");

    private static final String DRAG_HANDLE_STYLE_CLASS = "toolbar-drag-handle";
    private static final String PLACEHOLDER_STYLE_CLASS = "toolbar-drag-placeholder";
    private static final String OUTLINE_STYLE_CLASS = "toolbar-drop-outline";

    private static final double HGAP = 0;
    private static final double VGAP = 2;
    private static final double NEW_ROW_EDGE = 8;

    private final List<ToolBar> toolBars = new ArrayList<>();
    /** The toolbars in their original order, used to restore the default arrangement on reset. */
    private final List<ToolBar> defaultOrder;
    private List<List<ToolBar>> rows = new ArrayList<>();
    private List<List<ToolBar>> dragOthers;
    private final Region dropOutline = new Region();
    private Runnable onArrangementChanged;
    private Runnable onVisibilityChanged;

    private boolean dragging;
    private ToolBar dragged;
    private Target dragTarget;
    private double dragPinnedHeight, draggedWidth, draggedHeight;

    ToolBarContainer(ToolBar... toolBars) {
        getStyleClass().add("toolbar-container");
        // Make the whole bounding box a drop target, including any empty area kept while dragging.
        setPickOnBounds(true);
        List<ToolBar> firstRow = new ArrayList<>();
        for (ToolBar toolBar : toolBars) {
            getChildren().add(toolBar);
            firstRow.add(toolBar);
            this.toolBars.add(toolBar);
            installDragSource(toolBar);
            installVisibilityTracking(toolBar);
        }
        rows.add(firstRow);
        defaultOrder = List.copyOf(this.toolBars);

        dropOutline.getStyleClass().add(OUTLINE_STYLE_CLASS);
        dropOutline.setManaged(false);
        dropOutline.setMouseTransparent(true);
        dropOutline.setVisible(false);
        getChildren().add(dropOutline);

        addEventFilter(DragEvent.DRAG_OVER, this::onDragOver);
        addEventFilter(DragEvent.DRAG_DROPPED, this::onDragDropped);
        addEventHandler(DragEvent.DRAG_EXITED, this::onDragExited);
    }

    // --- arrangement persistence ---------------------------------------------

    /** Runs the given action whenever the user completes a drag that changes the arrangement. */
    void setOnArrangementChanged(Runnable action) {
        this.onArrangementChanged = action;
    }

    /** Runs the given action whenever a toolbar is shown or hidden. */
    void setOnVisibilityChanged(Runnable action) {
        this.onVisibilityChanged = action;
    }

    // --- toolbar visibility --------------------------------------------------

    /** Returns the toolbars in their original order (whatever their current arrangement or visibility). */
    List<ToolBar> getToolBars() {
        return List.copyOf(toolBars);
    }

    /**
     * Restores the default arrangement: all toolbars visible, in their original order, on a single
     * row. Notifies both the arrangement and visibility listeners so the new state is persisted.
     */
    void resetArrangement() {
        List<List<ToolBar>> model = new ArrayList<>();
        model.add(new ArrayList<>(defaultOrder));
        rows = model;
        for (ToolBar bar : toolBars) {
            bar.setVisible(true);
        }
        requestLayout();
        if (onArrangementChanged != null) {
            onArrangementChanged.run();
        }
    }

    /** Comma-separated ids of the currently hidden toolbars, in original order (empty if none are hidden). */
    String getHiddenToolBarIds() {
        StringBuilder sb = new StringBuilder();
        for (ToolBar bar : toolBars) {
            if (!bar.isVisible()) {
                String id = bar.getId();
                if (id == null || id.isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(id);
            }
        }
        return sb.toString();
    }

    /**
     * Applies a hidden-toolbar set previously produced by {@link #getHiddenToolBarIds()}: toolbars
     * whose id is listed are hidden, all others are shown. A {@code null} or blank value shows all.
     */
    void applyHiddenToolBarIds(String hidden) {
        Set<String> hiddenIds = new HashSet<>();
        if (hidden != null && !hidden.isBlank()) {
            for (String token : hidden.split(",")) {
                String id = token.trim();
                if (!id.isBlank()) {
                    hiddenIds.add(id);
                }
            }
        }
        for (ToolBar bar : toolBars) {
            bar.setVisible(!hiddenIds.contains(bar.getId()));
        }
    }

    private void installVisibilityTracking(ToolBar toolBar) {
        toolBar.setManaged(toolBar.isVisible());
        toolBar.visibleProperty().addListener((obs, was, now) -> {
            toolBar.setManaged(now);
            requestLayout();
            if (onVisibilityChanged != null) {
                onVisibilityChanged.run();
            }
        });
    }

    /**
     * Serializes the current arrangement as rows of tool-bar ids, tool bars within a row separated
     * by {@code ,} and rows separated by {@code ;}, e.g. {@code "file,clipboard;edit"}. Returns an
     * empty string if any tool bar lacks a usable id (arrangement can't be persisted reliably).
     */
    String getArrangement() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) {
                sb.append(';');
            }
            List<ToolBar> row = rows.get(r);
            for (int j = 0; j < row.size(); j++) {
                String id = row.get(j).getId();
                if (id == null || id.isBlank()) {
                    return "";
                }
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(id);
            }
        }
        return sb.toString();
    }

    /**
     * Restores an arrangement previously produced by {@link #getArrangement()}. The value must
     * reference every tool bar exactly once (by id); otherwise it is rejected and the current
     * arrangement is kept. This tolerates changes to the number/order of tool bars: any stored
     * value that no longer matches the current set is simply ignored.
     *
     * @return {@code true} if the arrangement was valid and applied
     */
    boolean applyArrangement(String arrangement) {
        if (dragging || arrangement == null || arrangement.isBlank()) {
            return false;
        }
        Map<String, ToolBar> byId = new HashMap<>();
        for (ToolBar bar : toolBars) {
            String id = bar.getId();
            if (id == null || id.isBlank() || byId.put(id, bar) != null) {
                return false; // missing or duplicate ids: can't restore reliably
            }
        }
        List<List<ToolBar>> model = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String group : arrangement.split(";")) {
            if (group.isBlank()) {
                continue;
            }
            List<ToolBar> row = new ArrayList<>();
            for (String token : group.split(",")) {
                String id = token.trim();
                ToolBar bar = byId.get(id);
                if (bar == null || !seen.add(id)) {
                    return false;
                }
                row.add(bar);
            }
            if (!row.isEmpty()) {
                model.add(row);
            }
        }
        if (seen.size() != toolBars.size() || model.isEmpty()) {
            return false;
        }
        rows = model;
        requestLayout();
        return true;
    }

    // --- drag-and-drop -------------------------------------------------------

    private void installDragSource(ToolBar toolBar) {
        Node handle = findDragHandle(toolBar);
        if (handle == null) {
            return;
        }
        handle.setOnDragDetected(e -> {
            Dragboard db = handle.startDragAndDrop(TransferMode.MOVE);
            db.setDragView(toolBar.snapshot(null, null));
            db.setDragViewOffsetX(e.getX());
            db.setDragViewOffsetY(e.getY());
            ClipboardContent content = new ClipboardContent();
            content.put(TOOLBAR, Boolean.TRUE);
            db.setContent(content);
            beginDrag(toolBar);
            e.consume();
        });
        handle.setOnDragDone(e -> {
            endDrag();
            e.consume();
        });
    }

    private void beginDrag(ToolBar toolBar) {
        dragging = true;
        dragged = toolBar;
        dragTarget = null;
        dragPinnedHeight = rowsHeight(rows);
        draggedWidth = toolBar.prefWidth(-1);
        draggedHeight = toolBar.prefHeight(-1);
        dragOthers = rowsWithoutDragged();
        dragged.getStyleClass().add(PLACEHOLDER_STYLE_CLASS);
        requestLayout();
    }

    private void endDrag() {
        dragging = false;
        if (dragged != null) {
            dragged.getStyleClass().remove(PLACEHOLDER_STYLE_CLASS);
            dragged.setVisible(true);
        }
        dragged = null;
        dragOthers = null;
        dragTarget = null;
        dragPinnedHeight = 0;
        dropOutline.setVisible(false);
        requestLayout();
    }

    private void onDragOver(DragEvent e) {
        if (dragging && e.getDragboard().hasContent(TOOLBAR)) {
            e.acceptTransferModes(TransferMode.MOVE);
            Target target = computeTarget(dragOthers, e.getX(), e.getY());
            if (!target.equals(dragTarget)) {
                dragTarget = target;
                requestLayout();
            }
        }
        e.consume();
    }

    private void onDragDropped(DragEvent e) {
        if (dragging && dragTarget != null) {
            List<List<ToolBar>> model = copyRows(dragOthers);
            if (dragTarget.newRow) {
                List<ToolBar> row = new ArrayList<>();
                row.add(dragged);
                model.add(dragTarget.rowIndex, row);
            } else {
                model.get(dragTarget.rowIndex).add(dragTarget.pos, dragged);
            }
            rows = model;
            e.setDropCompleted(true);
            if (onArrangementChanged != null) {
                onArrangementChanged.run();
            }
        } else {
            e.setDropCompleted(false);
        }
        e.consume();
    }

    private void onDragExited(DragEvent e) {
        if (dragging && dragTarget != null) {
            dragTarget = null;
            requestLayout();
        }
        e.consume();
    }

    private List<List<ToolBar>> rowsWithoutDragged() {
        List<List<ToolBar>> result = new ArrayList<>();
        for (List<ToolBar> row : rows) {
            List<ToolBar> copy = new ArrayList<>();
            for (ToolBar bar : row) {
                if (bar != dragged) {
                    copy.add(bar);
                }
            }
            if (!copy.isEmpty()) {
                result.add(copy);
            }
        }
        return result;
    }

    /**
     * Computes where the dragged tool bar should be inserted among {@code others} for a drop at the
     * given container-local point.
     */
    private Target computeTarget(List<List<ToolBar>> others, double x, double y) {
        double left = getInsets().getLeft();
        double top = getInsets().getTop();
        if (others.isEmpty()) {
            return new Target(0, 0, true);
        }
        double rowTop = top;
        for (int i = 0; i < others.size(); i++) {
            List<ToolBar> row = others.get(i);
            double rowHeight = rowHeight(row);
            double rowBottom = rowTop + rowHeight;
            if (y < rowTop) {
                // Above this row (or in the gap before it): start a new row here.
                return new Target(i, 0, true);
            }
            if (y <= rowBottom) {
                if (y < rowTop + NEW_ROW_EDGE) {
                    return new Target(i, 0, true);
                }
                if (y > rowBottom - NEW_ROW_EDGE) {
                    return new Target(i + 1, 0, true);
                }
                return new Target(i, horizontalPos(row, x, left), false);
            }
            rowTop = rowBottom + VGAP;
        }
        // Below the last row: append as a new row.
        return new Target(others.size(), 0, true);
    }

    /** Insertion position within a row for horizontal coordinate {@code x} (by tool bar midpoints). */
    private int horizontalPos(List<ToolBar> row, double x, double left) {
        double cx = left;
        for (int j = 0; j < row.size(); j++) {
            double w = barWidth(row.get(j));
            if (x < cx + w / 2) {
                return j;
            }
            cx += w + HGAP;
        }
        return row.size();
    }

    private static Node findDragHandle(ToolBar toolBar) {
        for (Node item : toolBar.getItems()) {
            if (item.getStyleClass().contains(DRAG_HANDLE_STYLE_CLASS)) {
                return item;
            }
        }
        return null;
    }

    // --- layout --------------------------------------------------------------

    @Override
    protected void layoutChildren() {
        if (dragging) {
            layoutDragging();
        } else {
            layoutRows(rows);
        }
    }

    private void layoutRows(List<List<ToolBar>> model) {
        double left = getInsets().getLeft();
        double y = getInsets().getTop();
        for (List<ToolBar> row : model) {
            double rowHeight = rowHeight(row);
            double x = left;
            for (ToolBar bar : row) {
                double w = barWidth(bar);
                bar.resizeRelocate(x, y, w, rowHeight);
                x += w + HGAP;
            }
            y += rowHeight + VGAP;
        }
    }

    /**
     * Lays out the resting tool bars and places the dragged tool bar at {@link #dragTarget} as a
     * placeholder, so its neighbors move aside to preview the result. A mouse-transparent overlay
     * draws the red dashed outline around the slot.
     */
    private void layoutDragging() {
        if (dragTarget == null) {
            // Outside the container: no preview — show only the resting tool bars.
            dragged.setVisible(false);
            dropOutline.setVisible(false);
            layoutRows(dragOthers);
            return;
        }
        dragged.setVisible(true);
        double left = getInsets().getLeft();
        double y = getInsets().getTop();
        for (int r = 0; r <= dragOthers.size(); r++) {
            if (dragTarget.newRow && dragTarget.rowIndex == r) {
                placeDragged(left, y, draggedWidth, draggedHeight);
                y += draggedHeight + VGAP;
            }
            if (r == dragOthers.size()) {
                break;
            }
            List<ToolBar> row = dragOthers.get(r);
            double rowHeight = rowHeight(row);
            double x = left;
            for (int j = 0; j < row.size(); j++) {
                if (!dragTarget.newRow && dragTarget.rowIndex == r && dragTarget.pos == j) {
                    placeDragged(x, y, draggedWidth, rowHeight);
                    x += draggedWidth + HGAP;
                }
                ToolBar bar = row.get(j);
                double w = barWidth(bar);
                bar.resizeRelocate(x, y, w, rowHeight);
                x += w + HGAP;
            }
            if (!dragTarget.newRow && dragTarget.rowIndex == r && dragTarget.pos == row.size()) {
                placeDragged(x, y, draggedWidth, rowHeight);
            }
            y += rowHeight + VGAP;
        }
    }

    private void placeDragged(double x, double y, double w, double h) {
        dragged.resizeRelocate(x, y, w, h);
        dropOutline.resizeRelocate(x, y, w, h);
        dropOutline.setVisible(true);
        dropOutline.toFront();
    }

    @Override
    protected double computePrefWidth(double height) {
        double max = 0;
        for (List<ToolBar> row : rows) {
            double width = HGAP * Math.max(0, row.size() - 1);
            for (ToolBar bar : row) {
                width += barWidth(bar);
            }
            max = Math.max(max, width);
        }
        return getInsets().getLeft() + max + getInsets().getRight();
    }

    @Override
    protected double computePrefHeight(double width) {
        double content;
        if (dragging) {
            double base = rowsHeight(dragOthers);
            if (dragTarget != null && dragTarget.newRow) {
                base += draggedHeight + VGAP;
            }
            content = Math.max(dragPinnedHeight, base);
        } else {
            content = rowsHeight(rows);
        }
        return getInsets().getTop() + content + getInsets().getBottom();
    }

    private static double rowsHeight(List<List<ToolBar>> model) {
        double height = 0;
        for (int i = 0; i < model.size(); i++) {
            height += rowHeight(model.get(i));
            if (i > 0) {
                height += VGAP;
            }
        }
        return height;
    }

    private static double rowHeight(List<ToolBar> row) {
        double height = 0;
        for (ToolBar bar : row) {
            height = Math.max(height, barHeight(bar));
        }
        return height;
    }

    /** Effective width of a toolbar for layout: zero when hidden, so hidden bars take no space. */
    private static double barWidth(ToolBar bar) {
        return bar.isVisible() ? bar.prefWidth(-1) : 0;
    }

    /** Effective height of a toolbar for layout: zero when hidden, so hidden bars take no space. */
    private static double barHeight(ToolBar bar) {
        return bar.isVisible() ? bar.prefHeight(-1) : 0;
    }

    private static List<List<ToolBar>> copyRows(List<List<ToolBar>> source) {
        List<List<ToolBar>> copy = new ArrayList<>();
        for (List<ToolBar> row : source) {
            copy.add(new ArrayList<>(row));
        }
        return copy;
    }

    /** An insertion target: either a new row at {@code rowIndex}, or position {@code pos} in row {@code rowIndex}. */
    private record Target(int rowIndex, int pos, boolean newRow) {
    }
}
