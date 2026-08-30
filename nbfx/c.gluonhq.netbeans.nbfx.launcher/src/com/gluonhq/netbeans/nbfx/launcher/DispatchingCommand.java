package com.gluonhq.netbeans.nbfx.launcher;

import java.util.Objects;

import com.gluonhq.netbeans.nbfx.api.AbstractCommand;
import com.gluonhq.netbeans.nbfx.api.Command;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;

/**
 * A {@link Command} that dispatches to one of two delegate commands based on where keyboard focus
 * is: a file command when the project tree is focused, or an editor command otherwise. Its display
 * text and accelerator come from the editor command (labels are identical across targets), while its
 * enabled state and action follow whichever target is currently active.
 */
final class DispatchingCommand extends AbstractCommand {

    private final Command editorCommand;
    private final Command fileCommand;
    private final ObservableValue<Boolean> preferFile;

    DispatchingCommand(Command editorCommand, Command fileCommand, ObservableValue<Boolean> preferFile) {
        super(editorCommand.getId(), editorCommand.getText(), editorCommand.getAccelerator());
        this.editorCommand = Objects.requireNonNull(editorCommand);
        this.fileCommand = Objects.requireNonNull(fileCommand);
        this.preferFile = Objects.requireNonNull(preferFile);
        bindDisabled(Bindings.createBooleanBinding(
                () -> active().isDisabled(),
                preferFile, editorCommand.disabledProperty(), fileCommand.disabledProperty()));
    }

    private Command active() {
        return Boolean.TRUE.equals(preferFile.getValue()) ? fileCommand : editorCommand;
    }

    @Override
    public void run() {
        active().run();
    }
}
