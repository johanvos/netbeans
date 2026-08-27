package com.gluonhq.netbeans.nbfx.api;

import javafx.scene.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.openide.filesystems.FileObject;
import org.openide.util.Lookup;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;

/**
 * A view of the open projects, such as the logical Project view or the physical Files view.
 * <p>
 * A provider shows zero or more projects at the same time: projects are added with
 * {@link #addProject(Lookup)} and removed with {@link #removeProject(FileObject)} or
 * {@link #removeAllProjects()}, and every piece of per-project state (expanded nodes, selected node)
 * is addressed by the project's root {@link FileObject}.
 */
public interface NavigatorProvider {

    /** Return the title of the tab holding this provider */
    String getTitle();

    /** Return the content of the tab holding this provider (e.g. a treeview with Files) */
    Node getView();

    /**
     * Adds the project rooted at the {@link FileObject} found in {@code context} to this view,
     * keeping any project already shown. Adding a project that is already shown is a no-op.
     * @param context a lookup holding the project's root {@link FileObject}
     */
    void addProject(Lookup context);

    /**
     * Replaces every project shown by this view with the one found in {@code context}.
     * @param context a lookup holding the project's root {@link FileObject}
     */
    default void setContext(Lookup context) {
        removeAllProjects();
        addProject(context);
    }

    /**
     * Removes the project rooted at {@code root} from this view, releasing the resources (e.g. file
     * listeners) associated with it. Unknown roots are ignored.
     * @param root the root directory of the project to remove
     */
    default void removeProject(FileObject root) {
    }

    /**
     * Removes the project rooted at {@code path} from this view, identifying it by the path it was
     * added with. Unlike {@link #removeProject(FileObject)} this survives the root folder being
     * renamed on disk, as a {@link FileObject} follows its file and would answer with the new path.
     * @param path the absolute path the project's root was added with
     */
    default void removeProjectAt(String path) {
    }

    /**
     * Removes every project shown by this view, releasing all the resources associated with them.
     */
    default void removeAllProjects() {
    }

    /**
     * The roots of the projects currently shown by this view, in the order they were added.
     * @return the project roots, never {@code null}
     */
    default List<FileObject> getProjectRoots() {
        return List.of();
    }

    /**
     * Returns the name of the (menu-sized) icon resource for the currently loaded project root,
     * matching its tree root node, or {@code null}.
     * @param projectRoot the project root directory
     * @return the icon resource name, or {@code null}
     */
    default String getProjectIconName(FileObject projectRoot) {
        return null;
    }

    /**
     * Returns an icon node for the given project root using a previously resolved icon name.
     * @param projectRoot the project root directory
     * @param iconName the icon resource name previously resolved for this project
     * @return an icon node, or {@code null}
     */
    default Node getProjectIcon(FileObject projectRoot, String iconName) {
        return null;
    }

    /**
     * Registers a callback invoked on the JavaFX Application Thread with a project's root once that
     * project's view has finished loading.
     * @param onProjectLoaded the callback to run when a project has loaded
     */
    default void setOnProjectLoaded(Consumer<FileObject> onProjectLoaded) {
    }

    /**
     * Registers a callback invoked on the JavaFX Application Thread with a project's root when that
     * project could not be loaded (it is not a project, or reading it failed). Together with
     * {@link #setOnProjectLoaded(Consumer)} it guarantees that every started load is reported back,
     * so callers never wait for a project that will never appear. Cancelled loads are not reported.
     * @param onProjectLoadFailed the callback to run when a project failed to load
     */
    default void setOnProjectLoadFailed(Consumer<FileObject> onProjectLoadFailed) {
    }

    /**
     * Requests that any in-progress project loading started by {@link #addProject(Lookup)} be
     * cancelled, for every project still loading.
     */
    default void cancelLoading() {
    }

    /**
     * Requests that the in-progress loading of the project rooted at {@code root} be cancelled,
     * leaving any other project still loading untouched. Unknown roots are ignored.
     * @param root the root directory of the project whose loading should be cancelled
     */
    default void cancelLoading(FileObject root) {
    }

    /**
     * Returns identifiers for the currently expanded nodes of every project in this provider's tree.
     * @return the expanded-node identifiers
     */
    default List<String> getExpandedPaths() {
        List<String> all = new ArrayList<>();
        for (FileObject root : getProjectRoots()) {
            all.addAll(getExpandedPaths(root));
        }
        return all;
    }

    /**
     * Returns identifiers for the currently expanded nodes of the project rooted at {@code root}.
     * @param root the root directory of the project
     * @return the expanded-node identifiers
     */
    default List<String> getExpandedPaths(FileObject root) {
        return List.of();
    }

    /**
     * Requests that the given nodes of the project rooted at {@code root} be expanded once its tree
     * has lazily built. May be called before the project is added.
     * @param root the root directory of the project
     * @param paths the identifiers of nodes to expand
     */
    default void restoreExpandedPaths(FileObject root, List<String> paths) {
    }

    /**
     * Returns the identifier of the currently selected node of this provider's tree, or
     * {@code null} when nothing is selected.
     * @return the selected-node identifier, or {@code null}
     */
    default String getSelectedPath() {
        for (FileObject root : getProjectRoots()) {
            String selected = getSelectedPath(root);
            if (selected != null) {
                return selected;
            }
        }
        return null;
    }

    /**
     * Returns the identifier of the currently selected node within the project rooted at
     * {@code root}, or {@code null} when the selection is elsewhere or empty.
     * @param root the root directory of the project
     * @return the selected-node identifier, or {@code null}
     */
    default String getSelectedPath(FileObject root) {
        return null;
    }

    /**
     * Requests that the given node of the project rooted at {@code root} be selected once its tree
     * has lazily built. The selection is restored without moving keyboard focus into the tree, so a
     * restored editor keeps its caret. May be called before the project is added.
     * @param root the root directory of the project
     * @param path the identifier of the node to select
     */
    default void restoreSelectedPath(FileObject root, String path) {
    }

    /**
     * The currently selected file or folder in this provider's view, as an observable so that
     * context-dependent actions (e.g. file clipboard operations) can follow the selection.
     * @return the selected {@link FileObject}, or {@code null} when nothing is selected
     */
    default ObservableValue<FileObject> selectedFile() {
        return new SimpleObjectProperty<>(null);
    }

    /**
     * The currently selected files or folders in this provider's view, as an observable so that
     * context-dependent batch actions (e.g. file clipboard operations) can follow the selection.
     * The list is empty when nothing is selected.
     * @return the selected {@link FileObject}s, never {@code null}
     */
    default ObservableValue<List<FileObject>> selectedFiles() {
        return new SimpleObjectProperty<>(List.of());
    }

    /**
     * A short description of this provider's view, used for tooltips. Defaults to {@link #getTitle()}.
     * @return the view description
     */
    default String getDescription() {
        return getTitle();
    }

    /**
     * Reveals {@code file} in this provider's view: expands the tree to it and selects it, bringing it into view.
     * @param file the file to reveal and select
     */
    default void revealFile(FileObject file) {
    }
}
