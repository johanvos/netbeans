package com.gluonhq.netbeans.nbfx.file.actions;

import com.gluonhq.netbeans.nbfx.api.OpenProject;
import com.gluonhq.netbeans.nbfx.api.ProjectRegistry;
import org.openide.filesystems.FileObject;
import org.openide.util.Lookup;

/**
 * Resolves which open project a file operation belongs to, so its undo history is recorded against
 * that project and not against whichever one happens to be selected. Null-safe when no
 * {@link ProjectRegistry} is registered (unit tests, or the application before it starts).
 */
final class ProjectScope {

    private ProjectScope() {
    }

    /**
     * The path of the open project owning {@code file}, or {@code null} when it belongs to none. For
     * a cross-project copy or paste this is the project of the <em>target</em> folder, which is the
     * project whose files actually changed.
     */
    static String pathOf(FileObject file) {
        ProjectRegistry registry = Lookup.getDefault().lookup(ProjectRegistry.class);
        OpenProject project = registry == null ? null : registry.ownerOf(file);
        return project == null ? null : project.getPath();
    }
}
