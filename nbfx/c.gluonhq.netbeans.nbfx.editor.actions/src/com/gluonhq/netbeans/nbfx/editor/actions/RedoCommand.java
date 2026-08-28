package com.gluonhq.netbeans.nbfx.editor.actions;

import com.gluonhq.netbeans.nbfx.api.ActionIds;
import com.gluonhq.netbeans.nbfx.api.EditorDocument;

import javafx.beans.value.ObservableValue;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.openide.util.NbBundle;

/**
 * Redoes the last undone edit in the active editor document. Enabled only when the active
 * document has an undone edit that can be redone.
 */
class RedoCommand extends ActiveDocumentCommand {

    RedoCommand(ObservableValue<EditorDocument> activeDocument) {
        super(ActionIds.REDO, NbBundle.getMessage(RedoCommand.class, "CTL_RedoCommand"),
                new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                activeDocument, EditorDocument::canRedoProperty, EditorDocument::redo);
    }
}
