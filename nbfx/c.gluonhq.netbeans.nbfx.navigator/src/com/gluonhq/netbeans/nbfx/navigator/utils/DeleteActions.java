package com.gluonhq.netbeans.nbfx.navigator.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.gluonhq.netbeans.nbfx.api.ContentManager;
import com.gluonhq.netbeans.nbfx.navigator.ProjectEntry;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TreeItem;
import org.openide.filesystems.FileObject;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;

/**
 * Confirms and performs deletion of navigator tree entries (files and package folders) via the
 * {@link ContentManager}, driving the JavaFX confirmation/error dialogs on the FX thread.
 */
public final class DeleteActions {

    private static final Logger LOG = Logger.getLogger(DeleteActions.class.getName());

    private DeleteActions() {}

    /**
     * Whether {@code entry} can be deleted from the tree: regular files and package folders. Project
     * roots, source-group roots and the modules grouping node are not deletable. The synthetic
     * "default package" node is excluded too, as it maps to the source-group root folder itself.
     */
    public static boolean isDeletable(ProjectEntry entry) {
        if (entry == null || !entry.isFileObject()) {
            return false;
        }
        ProjectEntry.Type type = entry.getType();
        if (type == ProjectEntry.Type.PACKAGE) {
            return !PackageScanner.getDefaultPackageName().equals(entry.getName());
        }
        return type == ProjectEntry.Type.FILE;
    }

    /**
     * Confirms and deletes {@code entry}'s file or folder (recursively) via {@link ContentManager},
     * removing {@code treeItem} from the tree on success and showing a visible error on failure.
     */
    public static void deleteEntry(TreeItem<ProjectEntry> treeItem, ProjectEntry entry) {
        deleteSingle(treeItem, entry == null ? null : entry.getFileObject());
    }

    /**
     * Confirms and deletes all deletable entries among {@code treeItems} (files and package
     * folders). Returns {@code true} when at least one deletable entry was present (i.e. the batch
     * was handled), {@code false} when there was nothing to delete.
     */
    public static boolean deleteEntries(List<TreeItem<ProjectEntry>> treeItems) {
        if (treeItems == null || treeItems.isEmpty()) {
            return false;
        }
        List<TreeItem<ProjectEntry>> deletable = new ArrayList<>();
        for (TreeItem<ProjectEntry> item : treeItems) {
            if (item != null && isDeletable(item.getValue())) {
                deletable.add(item);
            }
        }
        if (deletable.isEmpty()) {
            return false;
        }
        deleteItems(deletable, item -> item.getValue() == null ? null : item.getValue().getFileObject());
        return true;
    }

    /**
     * Confirms and deletes the files/folders backing {@code items} (recursively) via
     * {@link ContentManager}, removing each node from the tree on success. A single confirmation
     * covers the whole batch, and any failure is reported through a visible error dialog; nothing
     * fails silently.
     *
     * @param items  the tree items to delete
     * @param toFile maps a tree item to its backing {@link FileObject}
     * @param <T>    the tree item value type
     */
    public static <T> void deleteItems(List<TreeItem<T>> items, Function<TreeItem<T>, FileObject> toFile) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<TreeItem<T>> targets = new ArrayList<>();
        for (TreeItem<T> item : items) {
            if (item != null && toFile.apply(item) != null) {
                targets.add(item);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        if (targets.size() == 1) {
            deleteSingle(targets.get(0), toFile.apply(targets.get(0)));
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                NbBundle.getMessage(DeleteActions.class, "Delete.confirmMany", targets.size()),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        confirm.setTitle(NbBundle.getMessage(DeleteActions.class, "Delete.title"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        ContentManager contentManager = Lookup.getDefault().lookup(ContentManager.class);
        if (contentManager == null) {
            return;
        }
        List<String> failures = new ArrayList<>();
        for (TreeItem<T> item : targets) {
            FileObject fo = toFile.apply(item);
            try {
                if (fo.isFolder()) {
                    contentManager.deleteFolder(fo);
                } else {
                    contentManager.deleteFile(fo);
                }
                if (item.getParent() != null) {
                    item.getParent().getChildren().remove(item);
                }
            } catch (IOException ex) {
                LOG.log(Level.WARNING, "Failed to delete " + fo.getPath(), ex);
                failures.add(fo.getNameExt() + ": " + ex.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            Alert error = new Alert(Alert.AlertType.ERROR,
                    NbBundle.getMessage(DeleteActions.class, "Delete.errorMany", String.join("\n", failures)),
                    ButtonType.OK);
            error.setHeaderText(null);
            error.setTitle(NbBundle.getMessage(DeleteActions.class, "Delete.title"));
            error.showAndWait();
        }
    }

    private static <T> void deleteSingle(TreeItem<T> treeItem, FileObject fo) {
        if (fo == null) {
            return;
        }
        boolean folder = fo.isFolder();
        String confirmKey = folder ? "Delete.confirmFolder" : "Delete.confirm";
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                NbBundle.getMessage(DeleteActions.class, confirmKey, fo.getNameExt()),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        confirm.setTitle(NbBundle.getMessage(DeleteActions.class, "Delete.title"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        ContentManager contentManager = Lookup.getDefault().lookup(ContentManager.class);
        if (contentManager == null) {
            return;
        }
        try {
            if (folder) {
                contentManager.deleteFolder(fo);
            } else {
                contentManager.deleteFile(fo);
            }
            if (treeItem != null && treeItem.getParent() != null) {
                treeItem.getParent().getChildren().remove(treeItem);
            }
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Failed to delete " + fo.getPath(), ex);
            Alert error = new Alert(Alert.AlertType.ERROR,
                    NbBundle.getMessage(DeleteActions.class, "Delete.error", fo.getNameExt(), ex.getMessage()),
                    ButtonType.OK);
            error.setHeaderText(null);
            error.setTitle(NbBundle.getMessage(DeleteActions.class, "Delete.title"));
            error.showAndWait();
        }
    }
}
