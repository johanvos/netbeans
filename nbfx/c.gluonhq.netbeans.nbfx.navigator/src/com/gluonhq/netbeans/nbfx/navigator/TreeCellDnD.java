package com.gluonhq.netbeans.nbfx.navigator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.gluonhq.netbeans.nbfx.file.actions.FileClipboardActions;
import com.gluonhq.netbeans.nbfx.file.actions.FileDragAndDrop;

import javafx.animation.PauseTransition;
import javafx.css.PseudoClass;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.util.Duration;

import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Installs drag-and-drop support on a navigator {@link TreeCell}: one or more selected
 * files/folders can be dragged onto a folder (or between rows) to move them there, reusing
 * {@link FileClipboardActions#move} so the gesture is undoable and reports failures visibly. Files
 * dragged in from outside the application (Finder/Explorer) are accepted the same way, and copied
 * into the target folder ({@link FileClipboardActions#copyInto}), leaving the originals in place.
 *
 * <p>A drop indicator shows the target: a folder is highlighted for a drop <em>into</em> it, while a
 * blue line above/below a row indicates a drop into that row's parent folder. Hovering over a
 * collapsed folder for a moment expands it so the gesture can continue into its children.</p>
 *
 * <p>The dragged files are also published on the dragboard (see
 * {@link com.gluonhq.netbeans.nbfx.file.actions.FileDragAndDrop}), so that dropping them on an
 * editor tab pane opens them.</p>
 */
final class TreeCellDnD {

    private static final PseudoClass DROP_INTO = PseudoClass.getPseudoClass("drop-into");
    private static final PseudoClass DROP_ABOVE = PseudoClass.getPseudoClass("drop-above");
    private static final PseudoClass DROP_BELOW = PseudoClass.getPseudoClass("drop-below");
    private static final Duration AUTO_EXPAND_DELAY = Duration.millis(700);

    /** The files being dragged in the current gesture. */
    private static List<FileObject> dragged;

    private static PauseTransition autoExpand;

    private enum Mode { INTO, ABOVE, BELOW }
    private record Target(FileObject folder, Mode mode) {}

    private TreeCellDnD() {}

    /**
     * Wires the drag-and-drop handlers on {@code cell}.
     *
     * @param cell      the tree cell
     * @param toFile    maps a tree item to its backing {@link FileObject}, or {@code null}
     * @param draggable whether a given tree item may be dragged as a source
     * @param reselect  selects the given moved files at their new location once the tree has rebuilt
     * @param <T>       the tree item value type
     */
    static <T> void install(TreeCell<T> cell, Function<TreeItem<T>, FileObject> toFile,
            Predicate<TreeItem<T>> draggable, Consumer<List<FileObject>> reselect) {

        cell.setOnDragDetected(e -> {
            TreeView<T> tree = cell.getTreeView();
            TreeItem<T> item = cell.getTreeItem();
            if (tree == null || item == null || !draggable.test(item)) {
                return;
            }
            if (!tree.getSelectionModel().getSelectedItems().contains(item)) {
                tree.getSelectionModel().clearSelection();
                tree.getSelectionModel().select(item);
            }
            List<FileObject> files = new ArrayList<>();
            for (TreeItem<T> selected : tree.getSelectionModel().getSelectedItems()) {
                if (!draggable.test(selected)) {
                    continue;
                }
                FileObject fo = toFile.apply(selected);
                if (fo != null && FileUtil.toFile(fo) != null) {
                    files.add(fo);
                }
            }
            if (files.isEmpty()) {
                return;
            }
            dragged = files;
            Dragboard db = cell.startDragAndDrop(TransferMode.COPY_OR_MOVE);
            ClipboardContent content = new ClipboardContent();
            // Published so that other drop targets (the editor tab panes) can open the files; the
            // gesture stays a move between tree rows, where only TransferMode.MOVE is accepted.
            FileDragAndDrop.putFiles(content, files);
            db.setContent(content);
            e.consume();
        });

        cell.setOnDragOver(e -> {
            boolean external = FileDragAndDrop.isExternal(e.getDragboard());
            List<FileObject> sources = sourcesOf(e.getDragboard());
            Target target = sources.isEmpty() ? null : resolve(cell, toFile, e.getY(), sources, external);
            if (target == null) {
                clearVisuals(cell);
                cancelAutoExpand();
                return;
            }
            e.acceptTransferModes(external ? TransferMode.COPY : TransferMode.MOVE);
            applyVisuals(cell, target.mode());
            scheduleAutoExpand(cell, external);
            e.consume();
        });

        cell.setOnDragExited(e -> {
            clearVisuals(cell);
            cancelAutoExpand();
        });

        cell.setOnDragDropped(e -> {
            boolean handled = false;
            boolean external = FileDragAndDrop.isExternal(e.getDragboard());
            List<FileObject> sources = sourcesOf(e.getDragboard());
            if (!sources.isEmpty()) {
                Target target = resolve(cell, toFile, e.getY(), sources, external);
                if (target != null) {
                    // Files dragged in from outside the application are imported (copied), leaving
                    // the originals in place; files dragged within the tree are moved.
                    List<FileObject> destinations = external
                            ? FileClipboardActions.copyInto(sources, target.folder())
                            : FileClipboardActions.move(sources, target.folder());
                    TreeView<T> tree = cell.getTreeView();
                    // Clear selection and reselect the dropped files at their new location.
                    if (tree != null) {
                        tree.getSelectionModel().clearSelection();
                    }
                    if (!destinations.isEmpty()) {
                        reselect.accept(destinations);
                    }
                    handled = true;
                }
            }
            clearVisuals(cell);
            cancelAutoExpand();
            e.setDropCompleted(handled);
            e.consume();
        });

        cell.setOnDragDone(e -> {
            dragged = null;
            clearVisuals(cell);
            cancelAutoExpand();
        });
    }

    /**
     * The files of the current gesture: the ones dragged from the tree, or those of a drag coming
     * from outside the application; an empty list if the drag carries no file.
     */
    private static List<FileObject> sourcesOf(Dragboard dragboard) {
        if (FileDragAndDrop.isExternal(dragboard)) {
            return FileDragAndDrop.filesFrom(dragboard);
        }
        return dragged == null ? List.of() : dragged;
    }

    /** Resolves the drop target folder and indicator mode for the cursor position, or {@code null}. */
    private static <T> Target resolve(TreeCell<T> cell, Function<TreeItem<T>, FileObject> toFile, double y,
            List<FileObject> sources, boolean external) {
        TreeItem<T> item = cell.getTreeItem();
        if (item == null) {
            return null;
        }
        FileObject fo = toFile.apply(item);
        if (fo == null) {
            return null;
        }
        double h = cell.getHeight();
        if (fo.isFolder()) {
            // The top edge drops before the folder (into its parent)
            if (y < h * 0.25) {
                return validated(fo.getParent(), Mode.ABOVE, sources, external);
            }
            // Drop into the folder
            return validated(fo, Mode.INTO, sources, external);
        }
        // Drop into the file's parent folder.
        return validated(fo.getParent(), y < h * 0.5 ? Mode.ABOVE : Mode.BELOW, sources, external);
    }

    private static Target validated(FileObject folder, Mode mode, List<FileObject> sources, boolean external) {
        boolean valid = external
                ? FileClipboardActions.canCopyInto(folder, sources)
                : FileClipboardActions.canMoveInto(folder, sources);
        return valid ? new Target(folder, mode) : null;
    }

    private static <T> void scheduleAutoExpand(TreeCell<T> cell, boolean external) {
        TreeItem<T> item = cell.getTreeItem();
        if (item == null || item.isLeaf() || item.isExpanded()) {
            cancelAutoExpand();
            return;
        }
        if (autoExpand != null) {
            return;
        }
        PauseTransition pause = new PauseTransition(AUTO_EXPAND_DELAY);
        pause.setOnFinished(a -> {
            // A drag coming from outside the application has no files of ours being dragged; that
            // it is still hovering the same row (it is cancelled on exit) is all there is to check.
            if ((external || dragged != null) && cell.getTreeItem() == item) {
                item.setExpanded(true);
            }
            autoExpand = null;
        });
        autoExpand = pause;
        pause.playFromStart();
    }

    private static void cancelAutoExpand() {
        if (autoExpand != null) {
            autoExpand.stop();
            autoExpand = null;
        }
    }

    private static void applyVisuals(TreeCell<?> cell, Mode mode) {
        cell.pseudoClassStateChanged(DROP_INTO, mode == Mode.INTO);
        cell.pseudoClassStateChanged(DROP_ABOVE, mode == Mode.ABOVE);
        cell.pseudoClassStateChanged(DROP_BELOW, mode == Mode.BELOW);
    }

    private static void clearVisuals(TreeCell<?> cell) {
        cell.pseudoClassStateChanged(DROP_INTO, false);
        cell.pseudoClassStateChanged(DROP_ABOVE, false);
        cell.pseudoClassStateChanged(DROP_BELOW, false);
    }
}
