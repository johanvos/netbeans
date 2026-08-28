package com.gluonhq.netbeans.nbfx.editor.actions;

import com.gluonhq.netbeans.nbfx.api.ActionIds;
import com.gluonhq.netbeans.nbfx.api.EditorDocument;

import javafx.beans.value.ObservableValue;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.openide.util.NbBundle;

/**
 * Undoes the last edit in the active editor document. Enabled only when the active document has
 * an edit that can be undone.
 */
class UndoCommand extends ActiveDocumentCommand {

    UndoCommand(ObservableValue<EditorDocument> activeDocument) {
        super(ActionIds.UNDO, NbBundle.getMessage(UndoCommand.class, "CTL_UndoCommand"),
                new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN),
                activeDocument, EditorDocument::canUndoProperty, EditorDocument::undo);
    }
}
