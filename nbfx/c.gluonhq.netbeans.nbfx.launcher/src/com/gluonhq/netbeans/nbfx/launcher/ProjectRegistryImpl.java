package com.gluonhq.netbeans.nbfx.launcher;

import com.gluonhq.netbeans.nbfx.api.OpenProject;
import com.gluonhq.netbeans.nbfx.api.ProjectRegistry;
import java.io.File;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.openide.filesystems.FileObject;
import org.openide.util.lookup.ServiceProvider;

/**
 * Default {@link ProjectRegistry} implementation, holding the open projects and the selected one as
 * observable JavaFX state.
 */
@ServiceProvider(service = ProjectRegistry.class)
public class ProjectRegistryImpl implements ProjectRegistry {

    private static final Logger LOG = Logger.getLogger(ProjectRegistryImpl.class.getName());

    private final ObservableList<OpenProject> projects = FXCollections.observableArrayList();
    private final ObservableList<OpenProject> unmodifiableProjects =
            FXCollections.unmodifiableObservableList(projects);
    private final ObjectProperty<OpenProject> selected =
            new SimpleObjectProperty<>(this, "selectedProject");

    @Override
    public ObservableList<OpenProject> getOpenProjects() {
        return unmodifiableProjects;
    }

    @Override
    public ReadOnlyObjectProperty<OpenProject> selectedProjectProperty() {
        return selected;
    }

    @Override
    public OpenProject getSelected() {
        return selected.get();
    }

    @Override
    public void select(OpenProject project) {
        if (project != null && !projects.contains(project)) {
            throw new IllegalArgumentException("Not an open project: " + project);
        }
        selected.set(project);
    }

    @Override
    public OpenProject find(String path) {
        if (path == null) {
            return null;
        }
        return projects.stream()
                .filter(project -> path.equals(project.getPath()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public OpenProject ownerOf(FileObject file) {
        if (file == null || projects.isEmpty()) {
            return null;
        }
        // The file's own project can be a sub-module of an open project. Resolve its project directory
        // and keep the candidate with the deepest open root: the owner is the nearest project.
        OpenProject byOwner = projectOwnerOf(file)
                .map(p -> containing(OpenProject.pathOf(p.getProjectDirectory())))
                .orElse(null);
        return deeper(containing(OpenProject.pathOf(file)), byOwner);
    }

    @Override
    public OpenProject open(FileObject root) {
        Objects.requireNonNull(root, "root");
        OpenProject existing = find(OpenProject.pathOf(root));
        if (existing != null) {
            select(existing);
            return existing;
        }
        OpenProject project = new OpenProject(root);
        projects.add(project);
        select(project);
        return project;
    }

    @Override
    public void close(OpenProject project) {
        if (project == null || !projects.contains(project)) {
            return;
        }
        // Move the selection off the project before it leaves the list, so no listener ever sees a
        // selected project that is not open.
        if (project.equals(getSelected())) {
            selected.set(mostRecentOther(project));
        }
        projects.remove(project);
    }

    @Override
    public void closeAll() {
        selected.set(null);
        projects.clear();
    }

    /** The most recently opened project other than {@code project}, or {@code null} if there is none. */
    private OpenProject mostRecentOther(OpenProject project) {
        for (int i = projects.size() - 1; i >= 0; i--) {
            OpenProject candidate = projects.get(i);
            if (!candidate.equals(project)) {
                return candidate;
            }
        }
        return null;
    }

    /** The candidate with the deepest root folder; {@code null} candidates lose. */
    private static OpenProject deeper(OpenProject first, OpenProject second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return second.getPath().length() > first.getPath().length() ? second : first;
    }

    /**
     * The open project whose root folder is {@code path} or contains it, preferring the deepest
     * root when projects are nested.
     */
    private OpenProject containing(String path) {
        if (path == null) {
            return null;
        }
        OpenProject match = null;
        for (OpenProject project : projects) {
            String root = project.getPath();
            if (root == null || !isWithin(path, root)) {
                continue;
            }
            if (match == null || root.length() > match.getPath().length()) {
                match = project;
            }
        }
        return match;
    }

    private static boolean isWithin(String path, String root) {
        return path.equals(root) || path.startsWith(root.endsWith(File.separator)
                ? root : root + File.separator);
    }

    private static Optional<Project> projectOwnerOf(FileObject file) {
        try {
            return Optional.ofNullable(FileOwnerQuery.getOwner(file));
        } catch (RuntimeException ex) {
            // No project system available (unit tests) or the file is gone: fall back to paths.
            LOG.log(Level.FINE, "Could not resolve the owning project of " + file, ex);
        }
        return Optional.empty();
    }
}
