package com.gluonhq.netbeans.nbfx.api;
import org.openide.filesystems.FileObject;

/**
 * Factory for editor documents. The resulting {@link EditorDocument} is handed to the
 * {@link ContentManager}.
 */
public interface EditorService {

    /** Creates a new editor document for {@code file}. */
    EditorDocument createDocument(FileObject file);

}
