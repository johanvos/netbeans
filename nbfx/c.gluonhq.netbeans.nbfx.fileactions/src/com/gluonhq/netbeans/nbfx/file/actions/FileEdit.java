package com.gluonhq.netbeans.nbfx.file.actions;

import java.io.IOException;

/**
 * A reversible filesystem change recorded by the {@link FileUndoManager}. Implementations must be
 * able to both {@link #undo()} their effect and {@link #redo()} it again.
 */
interface FileEdit {

    /** Reverts this edit. */
    void undo() throws IOException;

    /** Re-applies this edit after it was undone. */
    void redo() throws IOException;
}
