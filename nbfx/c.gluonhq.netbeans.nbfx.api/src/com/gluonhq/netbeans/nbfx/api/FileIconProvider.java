package com.gluonhq.netbeans.nbfx.api;

import javafx.scene.Node;
import org.openide.filesystems.FileObject;

/**
 * Resolves the icon shown for a file, so components that open files without having an icon at hand
 * (session restore, for instance) still get the same graphic the navigator views use.
 *
 * <p>Implementations are registered in the default {@link org.openide.util.Lookup}; the icon
 * knowledge itself lives in the navigator module, which owns the icon resources.
 */
public interface FileIconProvider {

    /**
     * A new icon node for {@code file}, or {@code null} when the file has no icon. The node is fresh
     * on every call: a scene graph node has a single parent, so it cannot be shared between tabs.
     * Must be called on the JavaFX application thread.
     *
     * @param file the file to create an icon for
     * @return a new icon node, or {@code null}
     */
    Node createIcon(FileObject file);
}
