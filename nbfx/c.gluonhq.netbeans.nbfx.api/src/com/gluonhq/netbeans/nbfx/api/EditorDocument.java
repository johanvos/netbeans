package com.gluonhq.netbeans.nbfx.api;

import java.io.IOException;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.scene.Node;
import org.openide.filesystems.FileObject;
import org.openide.util.Lookup;

/**
 * A savable, dirty-aware view of a single open editor.
 * <p>
 * An {@code EditorDocument} wraps the editor UI ({@link #getNode()}) together with the
 * {@link FileObject} it edits, tracks whether it has unsaved changes
 * ({@link #modifiedProperty()}), and can persist those changes back to the file
 * ({@link #save()}).
 */
public interface EditorDocument {

    /**
     * Returns the file this document edits.
     *
     * @return the backing {@link FileObject}, never {@code null}
     */
    FileObject getFileObject();

    /**
     * The {@link OpenProject#getPath() path} of the open project this document's file belongs to,
     * or {@code null} when it belongs to no open project.
     *
     * @return the owning project's path, or {@code null}
     */
    default String getProjectPath() {
        ProjectRegistry registry = Lookup.getDefault().lookup(ProjectRegistry.class);
        OpenProject project = registry == null ? null : registry.ownerOf(getFileObject());
        return project == null ? null : project.getPath();
    }

    /**
     * Returns the root node of the editor UI, suitable for adding to a scene graph.
     *
     * @return the editor's root node
     */
    Node getNode();

    /**
     * Returns a short, human-readable title for this document (typically the file name).
     *
     * @return the document title
     */
    String getTitle();

    /**
     * Indicates whether the document has unsaved changes.
     *
     * @return {@code true} if there are edits that have not yet been persisted
     */
    boolean isModified();

    /**
     * The observable modified (dirty) state of this document. UI can bind to this property
     * to reflect the dirty state (for example, marking a tab).
     *
     * @return the read-only modified property
     */
    ReadOnlyBooleanProperty modifiedProperty();

    /**
     * Persists the current editor content back to the {@link FileObject} and clears the
     * modified flag.
     * <p>
     * Implementations must never fail silently: any write conflict or I/O failure is
     * reported by throwing an {@link IOException}.
     *
     * @throws IOException if the file was modified externally since it was opened, or if
     *                     writing the content fails
     */
    void save() throws IOException;

    /**
     * Undoes the most recent edit, if any..
     */
    void undo();

    /**
     * Redoes the most recently undone edit, if any.
     */
    void redo();

    /**
     * Copies the current selection to the system clipboard.
     */
    void copy();

    /**
     * Cuts the current selection to the system clipboard.
     */
    void cut();

    /**
     * Pastes the system clipboard content at the caret.
     */
    void paste();

    /**
     * Observable state indicating whether an {@link #undo()} is currently possible. UI can bind
     * to this property to enable or disable an Undo action.
     *
     * @return the read-only can-undo property
     */
    ReadOnlyBooleanProperty canUndoProperty();

    /**
     * Observable state indicating whether a {@link #redo()} is currently possible. UI can bind
     * to this property to enable or disable a Redo action.
     *
     * @return the read-only can-redo property
     */
    ReadOnlyBooleanProperty canRedoProperty();

    /**
     * Observable state indicating whether the document currently has a non-empty selection
     * (so that Copy / Cut apply). UI can bind to this property.
     *
     * @return the read-only has-selection property
     */
    ReadOnlyBooleanProperty hasSelectionProperty();

    /**
     * Observable state indicating whether the document currently accepts edits (so that Paste
     * applies). UI can bind to this property.
     *
     * @return the read-only editable property
     */
    ReadOnlyBooleanProperty editableProperty();

    /**
     * The paragraph (zero-based line) index of the caret, used to persist and restore the
     * editing position.
     *
     * @return the caret's paragraph index
     */
    default int getCaretParagraph() {
        return 0;
    }

    /**
     * The column (character offset within the paragraph) of the caret, used to persist and
     * restore the editing position.
     *
     * @return the caret's column offset
     */
    default int getCaretColumn() {
        return 0;
    }

    /**
     * The paragraph (zero-based line) index currently shown at the top of the viewport, used to
     * persist and restore the scroll position.
     *
     * @return the first visible paragraph index
     */
    default int getTopParagraph() {
        return 0;
    }

    /**
     * Restores the editor's scroll and caret state: scrolls so {@code topParagraph} is at the top of
     * the viewport and places the caret at {@code caretParagraph}/{@code caretColumn}.
     *
     * @param topParagraph   the paragraph to show at the top of the viewport
     * @param caretParagraph the zero-based paragraph (line) index of the caret
     * @param caretColumn    the character offset within the caret's paragraph
     */
    default void restoreView(int topParagraph, int caretParagraph, int caretColumn) {
    }

    /**
     * Requests keyboard focus for the editor so the caret is shown. The default implementation does
     * nothing.
     */
    default void requestFocus() {
    }

}
