package com.gluonhq.netbeans.nbfx.api;

import java.util.List;
import java.util.Objects;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.collections.ObservableList;

/**
 * Shared registry of the currently open {@link EditorDocument}s and the active one.
 * <p>
 * It is the single source of truth used to drive context-dependent action enablement
 * (for example, enabling {@code Save} only when the active document is modified, or
 * {@code Save All} when any open document is modified).
 * <p>
 * The registry is populated by the component that manages editor tabs; action providers
 * and UI only observe it. It is resolved through the global {@link org.openide.util.Lookup}.
 */
public interface EditorContext {

    /**
     * Returns an observable, unmodifiable view of the currently open documents.
     *
     * @return the open documents, in no particular order
     */
    ObservableList<EditorDocument> getDocuments();

    /**
     * The open documents belonging to the project rooted at {@code projectPath}, in the order of
     * {@link #getDocuments()}.
     *
     * @param projectPath the {@link OpenProject#getPath() path} of a project; {@code null} selects
     *                    the documents that belong to no open project
     * @return an immutable snapshot of the matching documents
     */
    default List<EditorDocument> documentsOf(String projectPath) {
        return getDocuments().stream()
                .filter(document -> Objects.equals(projectPath, document.getProjectPath()))
                .toList();
    }

    /**
     * The observable active-document property. UI and actions can bind to it to react to
     * changes of the active editor.
     *
     * @return the read-only active-document property
     */
    ReadOnlyObjectProperty<EditorDocument> activeDocumentProperty();

    /**
     * Returns the currently active (focused/selected) document, or {@code null} if none.
     *
     * @return the active document, or {@code null}
     */
    EditorDocument getActiveDocument();

    /**
     * Sets the active document.
     *
     * @param document the document that became active, or {@code null} if none
     */
    void setActiveDocument(EditorDocument document);

    /**
     * Registers a newly opened document.
     *
     * @param document the document to register
     */
    void register(EditorDocument document);

    /**
     * Unregisters a closed document. If it was the active document, the active document is
     * cleared.
     *
     * @param document the document to unregister
     */
    void unregister(EditorDocument document);

}
