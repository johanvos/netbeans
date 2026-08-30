package com.gluonhq.netbeans.nbfx.launcher;

import com.gluonhq.netbeans.nbfx.api.OpenProject;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The projects whose trees are currently being loaded, in the order the loads were started.
 * <p>
 * Several projects can be opened at once, each loading on its own background thread, so loading is
 * tracked per project rather than as a single application-wide flag: the status bar reports the
 * latest load and its cancel button only cancels that one, and closing a project only has to cancel
 * its own load.
 * <p>
 * Not thread-safe: it is only touched from the JavaFX Application Thread.
 */
final class ProjectLoads {

    /** Project path (as in {@link OpenProject#getPath()}) to project directory. */
    private final Map<String, File> loading = new LinkedHashMap<>();

    /** Records that {@code dir}, whose project path is {@code path}, has started loading. */
    void begin(String path, File dir) {
        if (path != null) {
            loading.put(path, dir);
        }
    }

    /** Records that the project at {@code path} is no longer loading. Unknown paths are ignored. */
    void end(String path) {
        loading.remove(path);
    }

    /** Forgets every ongoing load. */
    void clear() {
        loading.clear();
    }

    boolean isLoading(String path) {
        return path != null && loading.containsKey(path);
    }

    /** The directory of the project loading at {@code path}, or {@code null} if it is not loading. */
    File dirOf(String path) {
        return path == null ? null : loading.get(path);
    }

    boolean isEmpty() {
        return loading.isEmpty();
    }

    /** How many projects are loading right now. */
    int size() {
        return loading.size();
    }

    /** The paths of the projects still loading, in the order their loads were started. */
    List<String> paths() {
        return List.copyOf(loading.keySet());
    }

    /**
     * The directory of the most recently started load that is still running, or {@code null} when
     * nothing is loading. That is the one the status bar reports on.
     */
    File current() {
        File last = null;
        for (File dir : loading.values()) {
            last = dir;
        }
        return last;
    }

    /** The path of the most recently started load that is still running, or {@code null}. */
    String currentPath() {
        String last = null;
        for (String path : loading.keySet()) {
            last = path;
        }
        return last;
    }
}
