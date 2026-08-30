package com.gluonhq.netbeans.nbfx.launcher;

import com.gluonhq.netbeans.nbfx.api.EditorContext;
import com.gluonhq.netbeans.nbfx.api.EditorDocument;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.openide.util.lookup.ServiceProvider;

/**
 * Default {@link EditorContext} implementation, holding the set of open documents and the
 * active one as observable JavaFX state. It is updated by the tab management code
 * ({@link ContentManagerImpl} and {@link NbfxTabPane}) and observed by action providers.
 */
@ServiceProvider(service = EditorContext.class)
public class EditorContextImpl implements EditorContext {

    private final ObservableList<EditorDocument> documents = FXCollections.observableArrayList();
    private final ObservableList<EditorDocument> unmodifiableDocuments =
            FXCollections.unmodifiableObservableList(documents);
    private final ObjectProperty<EditorDocument> activeDocument =
            new SimpleObjectProperty<>(this, "activeDocument");

    @Override
    public ObservableList<EditorDocument> getDocuments() {
        return unmodifiableDocuments;
    }

    @Override
    public EditorDocument getActiveDocument() {
        return activeDocument.get();
    }

    @Override
    public ReadOnlyObjectProperty<EditorDocument> activeDocumentProperty() {
        return activeDocument;
    }

    @Override
    public void register(EditorDocument document) {
        if (document != null && !documents.contains(document)) {
            documents.add(document);
        }
    }

    @Override
    public void unregister(EditorDocument document) {
        documents.remove(document);
        if (activeDocument.get() == document) {
            activeDocument.set(null);
        }
    }

    @Override
    public void setActiveDocument(EditorDocument document) {
        activeDocument.set(document);
    }
}
