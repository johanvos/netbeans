package com.gluonhq.netbeans.nbfx.launcher;

import com.gluonhq.netbeans.nbfx.api.OpenProject;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.openide.filesystems.FileAttributeEvent;
import org.openide.filesystems.FileChangeListener;
import org.openide.filesystems.FileEvent;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileRenameEvent;

/**
 * Watches the root folder of every open project so a project that stops existing on disk does not
 * stay open with a tree, tabs and actions pointing at nothing.
 * <p>
 * A project is reported as gone when its root folder is deleted <em>or renamed</em>: a project is
 * identified by the absolute path of its root, so a renamed folder is no longer that project - its
 * persisted state, its recent-projects entry and the paths its documents resolve against would all
 * refer to a folder that is no longer there.
 * <p>
 * The listeners are attached to the root {@link FileObject} itself, which also reports what happens
 * to its children, so only events about the root are acted upon. Filesystem events can arrive on
 * any thread: the callback is invoked on the thread that delivered the event and it is up to the
 * caller to hop to the FX thread.
 */
final class ProjectRootWatcher {

    private static final Logger LOG = Logger.getLogger(ProjectRootWatcher.class.getName());

    /** By project path, so watching is idempotent and a project is unwatched by its identity. */
    private final Map<String, Watch> watches = new LinkedHashMap<>();

    private final Consumer<OpenProject> onRootGone;

    ProjectRootWatcher(Consumer<OpenProject> onRootGone) {
        this.onRootGone = Objects.requireNonNull(onRootGone, "onRootGone");
    }

    /** Starts watching {@code project}'s root folder. Watching an already watched project is a no-op. */
    synchronized void watch(OpenProject project) {
        if (project == null || watches.containsKey(project.getPath())) {
            return;
        }
        FileObject root = project.getRoot();
        if (root == null) {
            return;
        }
        Watch watch = new Watch(project);
        watches.put(project.getPath(), watch);
        root.addFileChangeListener(watch);
    }

    /** Stops watching the project at {@code path}. Unknown paths are ignored. */
    synchronized void unwatch(String path) {
        Watch watch = watches.remove(path);
        if (watch != null) {
            watch.project.getRoot().removeFileChangeListener(watch);
        }
    }

    /** Stops watching every project. */
    synchronized void unwatchAll() {
        for (String path : List.copyOf(watches.keySet())) {
            unwatch(path);
        }
    }

    /**
     * Reports every watched project whose root folder is no longer a folder on disk.
     * <p>
     * Changes made outside the IDE only reach the {@linkplain FileChangeListener listeners} once the
     * filesystem is refreshed, and refreshing a folder that has just been deleted floods the
     * platform with the deletion of everything that was cached under it - work that ends in the
     * project system reloading a project that is going away. Asking the disk directly is both
     * cheaper and quieter: the project is closed and its tree is dropped without ever walking it.
     * <p>
     * Called from the refresh thread, so it must not touch the FX thread itself; the callback hops.
     */
    void checkRoots() {
        for (Watch watch : watches()) {
            if (!new File(watch.project.getPath()).isDirectory()) {
                rootGone(watch.project, "gone from disk");
            }
        }
    }

    private synchronized List<Watch> watches() {
        return List.copyOf(watches.values());
    }

    /** How many projects are being watched; for tests. */
    synchronized int watchedCount() {
        return watches.size();
    }

    /**
     * Reports {@code project} as gone, once: the watch is dropped first, so the listener cannot
     * fire again for a project that is already being closed - and so the closing that follows,
     * which unwatches the project in turn, finds nothing left to do.
     */
    private void rootGone(OpenProject project, String reason) {
        synchronized (this) {
            Watch watch = watches.get(project.getPath());
            if (watch == null) {
                return;
            }
            // A renamed root outlives the project it used to be, so its listener must go with it.
            unwatch(project.getPath());
        }
        LOG.warning("The folder of project " + project.getPath() + " was " + reason
                + "; closing the project");
        onRootGone.accept(project);
    }

    /** The listener of one project's root folder. */
    private final class Watch implements FileChangeListener {

        private final OpenProject project;

        Watch(OpenProject project) {
            this.project = project;
        }

        /**
         * Whether {@code file} is the watched root itself rather than one of its children. After a
         * rename the root keeps its {@link FileObject} identity but reports its new path, so both
         * are accepted.
         */
        private boolean isRoot(FileObject file) {
            return file != null
                    && (file == project.getRoot() || project.getPath().equals(OpenProject.pathOf(file)));
        }

        @Override
        public void fileDeleted(FileEvent fe) {
            if (isRoot(fe.getFile())) {
                rootGone(project, "deleted");
            }
        }

        @Override
        public void fileRenamed(FileRenameEvent fre) {
            if (isRoot(fre.getFile())) {
                rootGone(project, "renamed");
            }
        }

        @Override public void fileFolderCreated(FileEvent fe) { }
        @Override public void fileDataCreated(FileEvent fe) { }
        @Override public void fileChanged(FileEvent fe) { }
        @Override public void fileAttributeChanged(FileAttributeEvent fae) { }
    }
}
