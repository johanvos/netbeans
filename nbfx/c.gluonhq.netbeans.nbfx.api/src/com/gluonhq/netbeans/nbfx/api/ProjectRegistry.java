package com.gluonhq.netbeans.nbfx.api;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.collections.ObservableList;
import org.openide.filesystems.FileObject;

/**
 * The central registry that lists which projects are open and which one is selected.
 * <p>
 * The registry is observable, so the UI can bind to it.
 */
public interface ProjectRegistry {

    /** The open projects, in the order they were opened; an unmodifiable observable view. */
    ObservableList<OpenProject> getOpenProjects();

    /**
     * The selected project: the one project-scoped actions apply to. It is {@code null} when no
     * project is open.
     */
    ReadOnlyObjectProperty<OpenProject> selectedProjectProperty();

    /** The selected project, or {@code null} when no project is open. */
    OpenProject getSelected();

    /**
     * Selects {@code project}.
     *
     * @param project an open project, or {@code null} to clear the selection
     * @throws IllegalArgumentException if {@code project} is not open
     */
    void select(OpenProject project);

    /**
     * The open project whose {@link OpenProject#getPath() path} is {@code path}, or {@code null} if
     * no such project is open.
     */
    OpenProject find(String path);

    /**
     * The open project that owns {@code file}, or {@code null} when the file belongs to no open
     * project. Files of a sub-module resolve to the open project that contains the sub-module.
     */
    OpenProject ownerOf(FileObject file);

    /**
     * Opens the project rooted at {@code root} and selects it. Opening an already open project is a
     * no-op beyond selecting it.
     *
     * @param root the project's root folder
     * @return the (new or existing) open project
     */
    OpenProject open(FileObject root);

    /**
     * Closes {@code project}. When it was the selected one, the most recently opened of the
     * remaining projects becomes selected. Unknown or {@code null} projects are ignored.
     */
    void close(OpenProject project);

    /** Closes every open project and clears the selection. */
    void closeAll();
}
