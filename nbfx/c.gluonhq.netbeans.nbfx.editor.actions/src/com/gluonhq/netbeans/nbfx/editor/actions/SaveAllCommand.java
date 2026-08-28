package com.gluonhq.netbeans.nbfx.editor.actions;

import com.gluonhq.netbeans.nbfx.api.ActionIds;
import com.gluonhq.netbeans.nbfx.api.EditorContext;
import com.gluonhq.netbeans.nbfx.api.EditorDocument;

import java.util.List;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.openide.util.NbBundle;

/**
 * Saves every modified editor document, of every open project. Enabled whenever any open document
 * has unsaved changes.
 */
class SaveAllCommand extends SaveDocumentsCommand {

    SaveAllCommand(EditorContext context) {
        super(ActionIds.SAVE_ALL, NbBundle.getMessage(SaveAllCommand.class, "CTL_SaveAllCommand"),
                new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                context);
        updateDisabled();
    }

    @Override
    protected List<EditorDocument> scope() {
        return context().getDocuments();
    }
}
