package com.gluonhq.netbeans.nbfx.launcher;

import com.gluonhq.netbeans.nbfx.api.OpenProject;
import com.gluonhq.netbeans.nbfx.api.ProjectRegistry;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;

/**
 * Moves the selection from one open project to another: the Window menu's project list and the
 * Next/Previous Project shortcuts both go through here.
 * <p>
 * Switching is more than {@link ProjectRegistry#select(OpenProject) selecting} in the registry:
 * the caller also gets a chance to show the new project in the UI (reveal its root in the
 * navigator and take the focus there), which is what makes the shortcut feel like a switch rather
 * than a change of a title bar.
 */
final class ProjectSwitcher {

    private final ProjectRegistry registry;
    private final Consumer<OpenProject> onSwitched;

    /**
     * @param registry the registry holding the open projects and the selection
     * @param onSwitched called after a switch actually happened, with the newly selected project;
     *                   may be {@code null} when there is nothing to show
     */
    ProjectSwitcher(ProjectRegistry registry, Consumer<OpenProject> onSwitched) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.onSwitched = onSwitched;
    }

    /** Selects the project after the selected one, wrapping around at the end. */
    void next() {
        switchTo(neighbour(registry.getOpenProjects(), registry.getSelected(), 1));
    }

    /** Selects the project before the selected one, wrapping around at the start. */
    void previous() {
        switchTo(neighbour(registry.getOpenProjects(), registry.getSelected(), -1));
    }

    /**
     * Selects {@code project} and shows it. Projects that are not open, and re-selecting the
     * project that is already selected, are ignored - the latter so that using the menu on the
     * current project does not steal the focus from wherever the user is working.
     */
    void switchTo(OpenProject project) {
        if (project == null || registry.find(project.getPath()) == null
                || project.equals(registry.getSelected())) {
            return;
        }
        registry.select(project);
        if (onSwitched != null) {
            onSwitched.accept(project);
        }
    }

    /** True while switching is pointless: fewer than two projects are open. */
    ObservableValue<Boolean> disabled() {
        return Bindings.size(registry.getOpenProjects()).lessThan(2);
    }

    /**
     * The project {@code step} positions away from {@code selected} in {@code projects}, wrapping
     * around at both ends. When nothing is selected (or the selection is not in the list), walking
     * forwards starts at the first project and backwards at the last, so a single shortcut press
     * always lands somewhere.
     *
     * @return the neighbour, or {@code null} when {@code projects} is empty
     */
    static OpenProject neighbour(List<OpenProject> projects, OpenProject selected, int step) {
        int size = projects.size();
        if (size == 0) {
            return null;
        }
        int current = selected == null ? -1 : projects.indexOf(selected);
        if (current < 0) {
            return projects.get(step >= 0 ? 0 : size - 1);
        }
        return projects.get(Math.floorMod(current + step, size));
    }
}
