package com.gluonhq.netbeans.nbfx.file.actions;

import java.util.ArrayList;
import java.util.List;

import com.gluonhq.netbeans.nbfx.api.ActionIds;
import com.gluonhq.netbeans.nbfx.api.Command;
import com.gluonhq.netbeans.nbfx.api.RunnableCommand;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.NbBundle;

/**
 * Builds file-scoped {@link Command}s (Cut / Copy / Paste / Undo / Redo) driven by the current
 * navigator selection, the system clipboard and the shared {@link FileUndoManager}.
 *
 * <p>The system clipboard is not observable, so {@link #refreshClipboardState()} must be called
 * when the paste enablement should be re-evaluated. Copy/cut/paste refresh it automatically.</p>
 */
public final class FileActions {

    private final ObservableValue<List<FileObject>> selection;
    private final SimpleBooleanProperty clipboardHasFiles =
            new SimpleBooleanProperty(this, "clipboardHasFiles");

    private final Command copyCommand;
    private final Command cutCommand;
    private final Command pasteCommand;
    private final Command undoCommand;
    private final Command redoCommand;

    public FileActions(ObservableValue<List<FileObject>> selection) {
        this.selection = selection;

        BooleanBinding canCopyCut = Bindings.createBooleanBinding(
                () -> !realFiles().isEmpty(), selection);
        BooleanBinding canPaste = Bindings.createBooleanBinding(
                () -> clipboardHasFiles.get() && targetFolder() != null,
                clipboardHasFiles, selection);

        FileUndoManager undo = FileUndoManager.getDefault();

        copyCommand = RunnableCommand.enabledWhen(ActionIds.COPY, message("CTL_FileCopy"),
                shortcut(KeyCode.C), canCopyCut, this::copy);
        cutCommand = RunnableCommand.enabledWhen(ActionIds.CUT, message("CTL_FileCut"),
                shortcut(KeyCode.X), canCopyCut, this::cut);
        pasteCommand = RunnableCommand.enabledWhen(ActionIds.PASTE, message("CTL_FilePaste"),
                shortcut(KeyCode.V), canPaste, this::paste);
        undoCommand = RunnableCommand.enabledWhen(ActionIds.UNDO, message("CTL_FileUndo"),
                new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN),
                undo.canUndoProperty(), undo::undo);
        redoCommand = RunnableCommand.enabledWhen(ActionIds.REDO, message("CTL_FileRedo"),
                new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                undo.canRedoProperty(), undo::redo);

        selection.addListener((obs, old, now) -> refreshClipboardState());
        refreshClipboardState();
    }

    public Command copyCommand() {
        return copyCommand;
    }

    public Command cutCommand() {
        return cutCommand;
    }

    public Command pasteCommand() {
        return pasteCommand;
    }

    public Command undoCommand() {
        return undoCommand;
    }

    public Command redoCommand() {
        return redoCommand;
    }

    /** Re-reads the system clipboard so paste enablement reflects its current content. */
    public void refreshClipboardState() {
        clipboardHasFiles.set(FileClipboard.hasFiles());
    }

    private void copy() {
        List<FileObject> files = realFiles();
        if (!files.isEmpty()) {
            FileClipboardActions.copy(files);
            refreshClipboardState();
        }
    }

    private void cut() {
        List<FileObject> files = realFiles();
        if (!files.isEmpty()) {
            FileClipboardActions.cut(files);
            refreshClipboardState();
        }
    }

    private void paste() {
        FileObject target = targetFolder();
        if (target != null) {
            FileClipboardActions.paste(target);
            refreshClipboardState();
        }
    }

    /** The current selection restricted to entries backed by a real file on disk. */
    private List<FileObject> realFiles() {
        List<FileObject> selected = selection.getValue();
        if (selected == null || selected.isEmpty()) {
            return List.of();
        }
        List<FileObject> files = new ArrayList<>();
        for (FileObject fo : selected) {
            if (hasRealFile(fo)) {
                files.add(fo);
            }
        }
        return files;
    }

    /** The folder a paste targets: the first selected entry if a folder, else its parent. */
    private FileObject targetFolder() {
        List<FileObject> selected = selection.getValue();
        if (selected == null || selected.isEmpty()) {
            return null;
        }
        FileObject fo = selected.get(0);
        if (fo == null) {
            return null;
        }
        return fo.isFolder() ? fo : fo.getParent();
    }

    private static boolean hasRealFile(FileObject fo) {
        return fo != null && FileUtil.toFile(fo) != null;
    }

    private static KeyCodeCombination shortcut(KeyCode code) {
        return new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN);
    }

    private static String message(String key) {
        return NbBundle.getMessage(FileActions.class, key);
    }
}
