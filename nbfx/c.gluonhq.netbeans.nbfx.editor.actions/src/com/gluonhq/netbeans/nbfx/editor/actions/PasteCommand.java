package com.gluonhq.netbeans.nbfx.editor.actions;

import com.gluonhq.netbeans.nbfx.api.ActionIds;
import com.gluonhq.netbeans.nbfx.api.EditorDocument;

import javafx.beans.value.ObservableValue;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.openide.util.NbBundle;

/**
 * Pastes the clipboard content into the active editor document. Enabled whenever the active
 * document is editable.
 */
class PasteCommand extends ActiveDocumentCommand {

    PasteCommand(ObservableValue<EditorDocument> activeDocument) {
        super(ActionIds.PASTE, NbBundle.getMessage(PasteCommand.class, "CTL_PasteCommand"),
                new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN),
                activeDocument, EditorDocument::editableProperty, EditorDocument::paste);
    }
}
