package com.gluonhq.netbeans.nbfx.launcher;

import com.gluonhq.netbeans.nbfx.api.EditorDocument;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import org.openide.util.NbBundle;

/**
 * Prompts the user before closing editors that have unsaved changes, offering to Save, Discard
 * or Cancel. Used both when closing a single tab and when closing a window with one or more
 * modified files. Saving never fails silently: a save error is surfaced and aborts the close so
 * the user cannot lose data unknowingly.
 */
final class CloseConfirmation {

    private static final ButtonType SAVE =
            new ButtonType(message("CloseConfirmation.button.save"), ButtonBar.ButtonData.YES);
    private static final ButtonType DISCARD =
            new ButtonType(message("CloseConfirmation.button.discard"), ButtonBar.ButtonData.NO);
    private static final ButtonType CANCEL =
            new ButtonType(message("CloseConfirmation.button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);

    private CloseConfirmation() {
    }

    /**
     * Confirms closing the given documents.
     *
     * @param documents the documents being closed (unmodified ones are ignored)
     * @return {@code true} if the caller may proceed to close, {@code false} to abort
     */
    static boolean confirmClose(List<EditorDocument> documents) {
        List<EditorDocument> unsaved = documents.stream()
                .filter(EditorDocument::isModified)
                .toList();
        if (unsaved.isEmpty()) {
            return true;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(message("CloseConfirmation.title"));
        alert.setHeaderText(headerText(unsaved));
        alert.setContentText(message("CloseConfirmation.content"));
        alert.getButtonTypes().setAll(SAVE, DISCARD, CANCEL);

        ButtonType choice = alert.showAndWait().orElse(CANCEL);
        if (choice == DISCARD) {
            return true;
        }
        if (choice == SAVE) {
            return saveAll(unsaved);
        }
        return false;
    }

    private static String headerText(List<EditorDocument> unsaved) {
        if (unsaved.size() == 1) {
            return message("CloseConfirmation.header.single", unsaved.get(0).getTitle());
        }
        StringBuilder sb = new StringBuilder(message("CloseConfirmation.header.multiple")).append('\n');
        unsaved.forEach(d -> sb.append(message("CloseConfirmation.header.item", d.getTitle())).append('\n'));
        return sb.toString();
    }

    private static boolean saveAll(List<EditorDocument> unsaved) {
        List<String> failures = new ArrayList<>();
        for (EditorDocument document : unsaved) {
            try {
                document.save();
            } catch (IOException ex) {
                failures.add(message("CloseConfirmation.error.item", document.getTitle(), messageOf(ex)));
            }
        }
        if (failures.isEmpty()) {
            return true;
        }
        Alert error = new Alert(Alert.AlertType.ERROR);
        error.setTitle(message("CloseConfirmation.error.title"));
        error.setHeaderText(message("CloseConfirmation.error.header"));
        error.setContentText(String.join("\n", failures));
        error.showAndWait();
        return false;
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return (message == null || message.isBlank()) ? error.toString() : message;
    }

    private static String message(String key, Object... args) {
        return NbBundle.getMessage(CloseConfirmation.class, key, args);
    }
}
