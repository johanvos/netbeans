package com.gluonhq.netbeans.nbfx.navigator;

import com.gluonhq.netbeans.nbfx.navigator.utils.NavigatorIcons;
import com.gluonhq.netbeans.nbfx.navigator.utils.PackageScanner;
import com.gluonhq.netbeans.nbfx.navigator.utils.TreeNav;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;
import org.openide.filesystems.FileObject;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author johan
 */
public class FileTreeItem extends TreeItem<FileObject> {

    private final boolean isFolder;
    private boolean mappedChildren = false;

    public FileTreeItem(FileObject fileObject) {
        super(fileObject);
        this.isFolder = fileObject.isFolder();
        if (isFolder) {
            expandedProperty().subscribe(expanded -> {
                String iconName = expanded ? NavigatorIcons.FOLDER_OPEN_ICON : NavigatorIcons.FOLDER_CLOSE_ICON;
                setGraphic(NavigatorIcons.createIconView(fileObject, iconName + ".png"));
            });
        } else {
            String iconName = NavigatorIcons.getFileIconName(fileObject);
            setGraphic(NavigatorIcons.createIconView(fileObject, iconName));
        }
    }

    @Override
    public ObservableList<TreeItem<FileObject>> getChildren() {
        if (!mappedChildren) {
            mappedChildren = true;
            ObservableList<FileTreeItem> children = buildChildren(this);
            children.sort(treeItemComparator);
            super.getChildren().setAll(children);
        }
        return super.getChildren();
    }

    @Override
    public boolean isLeaf() {
        return !isFolder;
    }

    /**
     * Rebuilds this folder's children from the filesystem, reusing existing child items (so expanded
     * sub-folders keep their state) and dropping items whose files no longer exist. Does nothing if
     * the children have not been built yet (they will be read fresh on first expansion).
     */
    public void refreshChildren() {
        if (!mappedChildren) {
            return;
        }
        FileObject parent = getValue();
        if (parent == null || !parent.isFolder()) {
            return;
        }
        parent.refresh();
        FileObject[] children = parent.getChildren();
        Map<FileObject, TreeItem<FileObject>> existing = new HashMap<>();
        for (TreeItem<FileObject> child : super.getChildren()) {
            existing.put(child.getValue(), child);
        }
        ObservableList<TreeItem<FileObject>> result = FXCollections.observableArrayList();
        if (children != null) {
            for (FileObject child : children) {
                if (PackageScanner.isIgnored(child)) {
                    continue;
                }
                TreeItem<FileObject> item = existing.get(child);
                result.add(item != null ? item : new FileTreeItem(child));
            }
        }
        result.sort(treeItemComparator);
        super.getChildren().setAll(result);
    }

    /**
     * Rebuilds this folder and every already-built folder below it. Used to catch up with changes
     * that happened before this project's filesystem listener was watching, which produced no event.
     */
    public void refreshBuiltSubtree() {
        if (!mappedChildren) {
            return;
        }
        refreshChildren();
        for (TreeItem<FileObject> child : super.getChildren()) {
            if (child instanceof FileTreeItem fileItem) {
                fileItem.refreshBuiltSubtree();
            }
        }
    }

    /**
     * Returns the already-built descendant item backing {@code fileObject}, or {@code null} when no
     * such item exists yet. Unlike {@link #getChildren()} this never forces children to be read, so
     * it only walks the part of the tree that is currently materialised.
     */
    public FileTreeItem findBuilt(FileObject fileObject) {
        if (fileObject == null) {
            return null;
        }
        if (fileObject.equals(getValue())) {
            return this;
        }
        if (!mappedChildren) {
            return null;
        }
        for (TreeItem<FileObject> child : super.getChildren()) {
            if (child instanceof FileTreeItem fileItem) {
                FileTreeItem found = fileItem.findBuilt(fileObject);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private ObservableList<FileTreeItem> buildChildren(FileTreeItem parentItem) {
        FileObject parent = parentItem.getValue();
        if (parent != null && parent.isFolder()) {
            FileObject[] children = parent.getChildren();
            if (children != null) {
                ObservableList<FileTreeItem> answer = FXCollections.observableArrayList();
                for (FileObject child : children) {
                    if (PackageScanner.isIgnored(child)) {
                        continue;
                    }
                    answer.add(new FileTreeItem(child));
                }
                return answer;
            }
        }
        return FXCollections.emptyObservableList();
    }

    private static final Comparator<TreeItem<FileObject>> treeItemComparator =
            TreeNav.comparator(item -> item.isLeaf() ? 1 : 0, item -> item.getValue().getNameExt());
}
