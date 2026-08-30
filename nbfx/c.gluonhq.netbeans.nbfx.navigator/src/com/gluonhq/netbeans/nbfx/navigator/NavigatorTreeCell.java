package com.gluonhq.netbeans.nbfx.navigator;

import com.gluonhq.netbeans.nbfx.navigator.utils.Projects;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import org.openide.filesystems.FileObject;

/**
 * Base class for navigator tree cells (the logical Project view and the physical Files view). It
 * installs the shared drag-and-drop move support ({@link TreeCellDnD}) and the shared
 * {@link NavigatorContextMenuFactory context menu}, driven by a view-specific
 * {@link NavigatorContextMenuFactory.Host}. It also marks the project root nodes, so that with
 * several projects open the tree shows which one the project-scoped actions apply to. Subclasses
 * only implement {@code updateItem} since their cell graphics differ.
 *
 * @param <T> the tree item value type
 */
abstract class NavigatorTreeCell<T> extends TreeCell<T> {

    /** Style class of the top-level node of every open project. */
    private static final String ROOT_STYLE_CLASS = "project-root";
    /** Style class of the top-level node of the selected project (the one actions apply to). */
    private static final String SELECTED_STYLE_CLASS = "project-root-selected";

    private final NavigatorContextMenuFactory.Host<T> host;

    /**
     * @param reselect selects the moved files at their new location once the tree has rebuilt
     * @param host     the view-specific hooks the shared drag-and-drop and context menu are built on
     */
    protected NavigatorTreeCell(Consumer<List<FileObject>> reselect,
            NavigatorContextMenuFactory.Host<T> host) {
        this.host = host;
        TreeCellDnD.install(this, host::fileOf, host::isDeletable, reselect);
        NavigatorContextMenuFactory<T> factory = new NavigatorContextMenuFactory<>(host);
        setOnContextMenuRequested(e -> {
            TreeItem<T> treeItem = getTreeItem();
            TreeView<T> tree = getTreeView();
            // Right-clicking outside the current multi-selection collapses it to the clicked row.
            if (tree != null && treeItem != null
                    && !tree.getSelectionModel().getSelectedItems().contains(treeItem)) {
                tree.getSelectionModel().clearSelection();
                tree.getSelectionModel().select(treeItem);
            }
            List<TreeItem<T>> selection = tree == null
                    ? List.of()
                    : new ArrayList<>(tree.getSelectionModel().getSelectedItems());
            ContextMenu contextMenu = factory.create(treeItem, selection);
            if (contextMenu != null) {
                contextMenu.show(this, e.getScreenX(), e.getScreenY());
            }
            e.consume();
        });
    }

    /**
     * Marks this cell when it holds a project's top-level node, and additionally when that project
     * is the selected one. Subclasses get this for free by calling {@code super.updateItem}.
     */
    @Override
    protected void updateItem(T item, boolean empty) {
        super.updateItem(item, empty);
        TreeItem<T> treeItem = empty ? null : getTreeItem();
        boolean root = treeItem != null && host.isRoot(treeItem);
        setStyleClass(ROOT_STYLE_CLASS, root);
        setStyleClass(SELECTED_STYLE_CLASS, root && Projects.isSelected(host.fileOf(treeItem)));
    }

    private void setStyleClass(String styleClass, boolean present) {
        if (present) {
            if (!getStyleClass().contains(styleClass)) {
                getStyleClass().add(styleClass);
            }
        } else {
            getStyleClass().remove(styleClass);
        }
    }
}
