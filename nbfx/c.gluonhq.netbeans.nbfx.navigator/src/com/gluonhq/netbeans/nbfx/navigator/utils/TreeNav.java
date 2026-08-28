package com.gluonhq.netbeans.nbfx.navigator.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.logging.Logger;

import com.gluonhq.netbeans.nbfx.navigator.ProjectEntry;
import com.gluonhq.netbeans.nbfx.navigator.ProjectTreeItem;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import org.netbeans.api.java.source.SourceUtils;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Exceptions;
import org.openide.util.RequestProcessor;

/**
 * Navigator tree helpers: lazy-expansion listener wiring, node lookup by path, selection/focus/scroll
 * behaviour shared by the tree providers, and background marking of files that declare a main class.
 */
public final class TreeNav {

    private static final Logger LOG = Logger.getLogger(TreeNav.class.getName());
    private static final RequestProcessor MAIN_ICON_RP =
            new RequestProcessor("ProjectViewUtils-main", 2, true, true);

    private TreeNav() {}

    /**
     * A comparator for tree siblings that orders first by an integer {@code rank} (the view's grouping
     * key) and then case-insensitively by {@code name}. Shared by both navigator tree items: the Files
     * view ranks folders before files, while the Project view ranks by node type. Parameterising the
     * two keys keeps a single ordering implementation for both.
     */
    public static <T> Comparator<TreeItem<T>> comparator(ToIntFunction<TreeItem<T>> rank,
            Function<TreeItem<T>, String> name) {
        return (a, b) -> {
            int compare = Integer.compare(rank.applyAsInt(a), rank.applyAsInt(b));
            if (compare != 0) {
                return compare;
            }
            return name.apply(a).toLowerCase(Locale.ROOT).compareTo(name.apply(b).toLowerCase(Locale.ROOT));
        };
    }

    public static void setExpandedInvalidationListener(ProjectTreeItem node, Runnable onInvalidate) {
        Objects.requireNonNull(node).expandedProperty().addListener(new InvalidationListener() {
            @Override
            public void invalidated(Observable observable) {
                if (node.isExpanded()) {
                    node.expandedProperty().removeListener(this);
                    Objects.requireNonNull(onInvalidate).run();
                }
            }
        });
    }

    public static void checkMainClasses(TreeItem<ProjectEntry> root) {
        MAIN_ICON_RP.post(() -> markMainClasses(root));
    }

    private static void markMainClasses(TreeItem<ProjectEntry> node) {
        if (node.isLeaf()) {
            if (node.getValue() != null && node.getValue().isFileObject() &&
                    node.getValue().getType() == ProjectEntry.Type.FILE &&
                    !NavigatorIcons.FILE_MAIN_CLASS_ICON.equals(node.getValue().getIconName())) {
                FileObject javaFile = node.getValue().getFileObject();
                boolean isMain = false;
                try {
                    isMain = !SourceUtils.getMainClasses(javaFile).isEmpty();
                } catch (IllegalArgumentException ex) {
                    // Ignore, don't show the main class icon
                }

                if (isMain) {
                    ProjectEntry oldEntry = node.getValue();
                    ProjectEntry newEntry = new ProjectEntry(oldEntry.getFileObject(), oldEntry.getName(),
                            oldEntry.getType(), ProjectEntry.BADGE.NO_BADGE, NavigatorIcons.FILE_MAIN_CLASS_ICON);
                    LOG.info("Marking main class: " + javaFile.getPath());
                    // The node is updated in place rather than replaced in its parent's children:
                    // replacing an item makes the TreeView's selection model drop the whole selection
                    // (verified), which would silently wipe what the user - or the restored session -
                    // had selected as source roots finish loading in the background.
                    Platform.runLater(() -> {
                        node.setValue(newEntry);
                        node.setGraphic(NavigatorIcons.createIconView(javaFile,
                                NavigatorIcons.FILE_MAIN_CLASS_ICON));
                    });
                }
            }
        } else {
            for (TreeItem<ProjectEntry> child : new ArrayList<>(node.getChildren())) {
                markMainClasses(child);
            }
        }
    }

    /**
     * Recursively find the TreeItem that corresponds to the provided path
     * @param root start searching from this TreeItem
     * @param target the path
     * @return the TreeItem which path matches the provided path
     */
    public static TreeItem<ProjectEntry> findTreeItem(TreeItem<ProjectEntry> root, Path target) {
        ProjectEntry pEntry = root.getValue();
        if (pEntry.isFileObject() && pEntry.isFolder()) {
            FileObject candidate = pEntry.getFileObject();
            LOG.info("FOLDER: " + pEntry + " with fo = " + candidate);
            Path cp = FileUtil.toPath(candidate);
            LOG.info("CP = " + cp);
            Path path = FileUtil.toPath(pEntry.getFileObject());
            try {
                if (Files.isSameFile(path, target)) {
                    return root;
                }
            } catch (IOException ex) {
                Exceptions.printStackTrace(ex);
            }
        }
        for (TreeItem<ProjectEntry> entry : root.getChildren()) {
            TreeItem<ProjectEntry> answer = findTreeItem(entry, target);
            if (answer != null) {
                return answer;
            }
        }
        return null;
    }

    /**
     * Clears the current selection, selects {@code item}, and scrolls it into view. Shared by the
     * navigator providers so revealing a file behaves consistently regardless of the tree's item type.
     */
    public static <T> void selectAndScroll(TreeView<T> tree, TreeItem<T> item) {
        tree.getSelectionModel().clearSelection();
        tree.getSelectionModel().select(item);
        int row = tree.getRow(item);
        if (row >= 0) {
            tree.scrollTo(row);
        }
    }

    /**
     * Pins the tree's focus indicator on the currently selected item's <em>live</em> row. The row is
     * recomputed from the selected item (not the selection model's index, which can go stale when rows
     * are inserted/removed) and is re-applied after layout settles so it survives asynchronous rebuilds.
     *
     * <p>Only applies while a single row is selected. With a multiple-row selection the focus is left
     * to JavaFX, so the focus indicator tracks the row the user is actually acting on rather than
     * lagging one behind as the selection grows.</p>
     */
    public static <T> void syncFocusToSelection(TreeView<T> tree) {
        if (tree.getSelectionModel().getSelectedItems().size() > 1) {
            return;
        }
        TreeItem<T> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            return;
        }
        Runnable focusRow = () -> {
            if (tree.getSelectionModel().getSelectedItems().size() > 1
                    || tree.getSelectionModel().getSelectedItem() != item) {
                return;
            }
            int row = tree.getRow(item);
            if (row >= 0 && tree.getFocusModel().getFocusedIndex() != row) {
                tree.getFocusModel().focus(row);
            }
        };
        focusRow.run();
        Platform.runLater(focusRow);
    }
}
