package com.gluonhq.netbeans.nbfx.editor.actions;

import com.gluonhq.netbeans.nbfx.api.AbstractCommand;
import com.gluonhq.netbeans.nbfx.api.EditorContext;
import com.gluonhq.netbeans.nbfx.api.EditorDocument;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.scene.input.KeyCombination;

/**
 * Base for the commands that save a set of open documents at once. Subclasses only define which
 * documents are in {@link #scope()} - all of them, or those of one project - and this class keeps
 * the enablement in sync with the documents' modified state and runs the saves.
 *
 * <p>Saving continues across all documents even if some fail; every failure is reported so that no
 * save fails silently.</p>
 */
abstract class SaveDocumentsCommand extends AbstractCommand {

    private final EditorContext context;
    private final ChangeListener<Boolean> modifiedListener = (obs, was, now) -> updateDisabled();

    /**
     * Subclasses must call {@link #updateDisabled()} at the end of their own constructor: this one
     * cannot, since {@link #scope()} may depend on state the subclass has not initialized yet.
     */
    SaveDocumentsCommand(String id, String text, KeyCombination accelerator, EditorContext context) {
        super(id, text, accelerator);
        this.context = context;
        context.getDocuments().addListener((ListChangeListener<EditorDocument>) change -> {
            while (change.next()) {
                change.getRemoved().forEach(d -> d.modifiedProperty().removeListener(modifiedListener));
                change.getAddedSubList().forEach(d -> d.modifiedProperty().addListener(modifiedListener));
            }
            updateDisabled();
        });
        context.getDocuments().forEach(d -> d.modifiedProperty().addListener(modifiedListener));
    }

    protected final EditorContext context() {
        return context;
    }

    /** The documents this command saves. */
    protected abstract List<EditorDocument> scope();

    /** Enabled as soon as any document in scope has unsaved changes. */
    protected final void updateDisabled() {
        setDisabled(scope().stream().noneMatch(EditorDocument::isModified));
    }

    @Override
    public void run() {
        List<String> failures = new ArrayList<>();
        for (EditorDocument document : new ArrayList<>(scope())) {
            if (document.isModified()) {
                try {
                    document.save();
                } catch (IOException ex) {
                    failures.add(SaveErrorReporter.describeFailure(document.getTitle(), ex));
                }
            }
        }
        SaveErrorReporter.reportFailures(failures);
    }
}
