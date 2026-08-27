package com.gluonhq.netbeans.nbfx.api;

import java.io.File;
import java.util.Objects;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * A project that is currently open in the application: its root folder plus the metadata the UI
 * needs to display it.
 * <p>
 * Instances are identified by {@link #getPath() the absolute path of their root folder}, which is
 * also the stable key used to persist per-project state.
 */
public final class OpenProject {

    private final FileObject root;
    private final String path;
    private final String displayName;

    /**
     * Creates a project handle for {@code root}.
     *
     * @param root the project's root folder; never {@code null}
     */
    public OpenProject(FileObject root) {
        this.root = Objects.requireNonNull(root, "root");
        this.path = pathOf(root);
        this.displayName = root.getNameExt();
    }

    /** The project's root folder. */
    public FileObject getRoot() {
        return root;
    }

    /** The absolute path of {@link #getRoot()}; the project's identity and persistence key. */
    public String getPath() {
        return path;
    }

    /** The name shown to the user; the root folder name. */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * The absolute path of {@code fileObject} on disk, or its {@link FileObject#getPath() filesystem
     * path} when it is an in-memory filesystem not backed by a file.
     *
     * @param fileObject the file to resolve; may be {@code null}
     * @return the path, or {@code null} when {@code fileObject} is {@code null}
     */
    public static String pathOf(FileObject fileObject) {
        if (fileObject == null) {
            return null;
        }
        File file = FileUtil.toFile(fileObject);
        return file != null ? file.getAbsolutePath() : fileObject.getPath();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof OpenProject other && Objects.equals(path, other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(path);
    }

    @Override
    public String toString() {
        return "OpenProject[" + path + "]";
    }
}
