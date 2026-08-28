package com.gluonhq.netbeans.nbfx.editor.actions;

import com.gluonhq.netbeans.nbfx.api.ActionIds;
import com.gluonhq.netbeans.nbfx.api.EditorDocument;

import javafx.beans.value.ObservableValue;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.openide.util.NbBundle;

/**
 * Copies the current selection of the active editor document to the clipboard. Enabled only
 * when the active document has a non-empty selection.
 */
class CopyCommand extends ActiveDocumentCommand {

    CopyCommand(ObservableValue<EditorDocument> activeDocument) {
        super(ActionIds.COPY, NbBundle.getMessage(CopyCommand.class, "CTL_CopyCommand"),
                new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN),
                activeDocument, EditorDocument::hasSelectionProperty, EditorDocument::copy);
    }
}
