package com.gluonhq.netbeans.nbfx.editor.actions;

import com.gluonhq.netbeans.nbfx.api.ActionIds;
import com.gluonhq.netbeans.nbfx.api.EditorDocument;

import javafx.beans.value.ObservableValue;

import java.io.IOException;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.openide.util.NbBundle;

/**
 * Saves the active editor document. Enabled only when there is an active document with unsaved
 * changes. The active document comes from the supplied observable, so the command works both
 * globally (the shared
 * {@link com.gluonhq.netbeans.nbfx.api.EditorContext#activeDocumentProperty() active document})
 * and scoped to a single window (that window's selected editor).
 */
class SaveCommand extends ActiveDocumentCommand {

    SaveCommand(ObservableValue<EditorDocument> activeDocument) {
        super(ActionIds.SAVE, NbBundle.getMessage(SaveCommand.class, "CTL_SaveCommand"),
                new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN),
                activeDocument, EditorDocument::modifiedProperty, SaveCommand::save);
    }

    private static void save(EditorDocument document) {
        try {
            document.save();
        } catch (IOException ex) {
            SaveErrorReporter.reportFailure(document.getTitle(), ex);
        }
    }
}
