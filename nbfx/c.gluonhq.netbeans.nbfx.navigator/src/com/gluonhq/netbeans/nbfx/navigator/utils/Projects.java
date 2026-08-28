package com.gluonhq.netbeans.nbfx.navigator.utils;

import com.gluonhq.netbeans.nbfx.api.OpenProject;
import com.gluonhq.netbeans.nbfx.api.ProjectRegistry;

import java.util.List;

import javafx.beans.value.ObservableValue;

import org.openide.filesystems.FileObject;
import org.openide.util.Lookup;

/**
 * The navigator's view of the {@link ProjectRegistry}: which projects are open and which one is
 * selected. Every method degrades gracefully when no registry is available (unit tests, or the
 * navigator running outside the application), so callers never have to null-check the lookup.
 */
public final class Projects {

    private Projects() {
    }

    /** The registry, or {@code null} when none is registered in the global lookup. */
    public static ProjectRegistry registry() {
        return Lookup.getDefault().lookup(ProjectRegistry.class);
    }

    /** The open projects, empty when there is no registry. */
    public static List<OpenProject> openProjects() {
        ProjectRegistry registry = registry();
        return registry == null ? List.of() : List.copyOf(registry.getOpenProjects());
    }

    /** The selected project, observable so the views can restyle when it changes; may be {@code null}. */
    public static ObservableValue<OpenProject> selectedProject() {
        ProjectRegistry registry = registry();
        return registry == null ? null : registry.selectedProjectProperty();
    }

    /** Whether {@code root} is the root of the selected project. */
    public static boolean isSelected(FileObject root) {
        ProjectRegistry registry = registry();
        if (registry == null || root == null) {
            return false;
        }
        OpenProject selected = registry.getSelected();
        return selected != null && selected.getPath().equals(OpenProject.pathOf(root));
    }

    /** Selects the project rooted at {@code root}, if it is open. */
    public static void select(FileObject root) {
        ProjectRegistry registry = registry();
        if (registry == null || root == null) {
            return;
        }
        select(registry, registry.find(OpenProject.pathOf(root)));
    }

    /**
     * Selects the open project that owns {@code file}, so that project-scoped actions follow the
     * navigator's selection. Files outside every open project leave the selection unchanged.
     */
    public static void selectOwnerOf(FileObject file) {
        ProjectRegistry registry = registry();
        if (registry == null || file == null) {
            return;
        }
        select(registry, registry.ownerOf(file));
    }

    private static void select(ProjectRegistry registry, OpenProject project) {
        if (project != null && project != registry.getSelected()) {
            registry.select(project);
        }
    }
}
