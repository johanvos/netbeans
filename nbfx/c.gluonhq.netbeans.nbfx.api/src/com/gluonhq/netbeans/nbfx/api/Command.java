package com.gluonhq.netbeans.nbfx.api;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.scene.input.KeyCombination;

/**
 * A single, context-aware application action (for example {@code Save} or {@code Save All}).
 * <p>
 * Commands are the unit of behavior held by the {@link ActionRegistry}. UI controls (menu
 * items, toolbar buttons) are built from commands: their action invokes {@link #run()} and
 * their enabled state is bound to {@link #disabledProperty()}, so enablement is driven by
 * context rather than by each individual UI control.
 */
public interface Command {

    /**
     * Returns the stable identifier of this command (see {@link ActionIds}).
     *
     * @return the command id, never {@code null}
     */
    String getId();

    /**
     * Returns the human-readable display text for this command.
     *
     * @return the display text
     */
    String getText();

    /**
     * Returns the preferred keyboard accelerator (shortcut) for this command, so the shortcut
     * travels with the command definition instead of being configured on each UI control.
     *
     * @return the accelerator, or {@code null} if the command has no default shortcut
     */
    default KeyCombination getAccelerator() {
        return null;
    }

    /**
     * Indicates whether the command is currently disabled (not executable in the current
     * context).
     *
     * @return {@code true} if the command cannot be run right now
     */
    boolean isDisabled();

    /**
     * The observable disabled state of this command. UI controls should bind their own
     * {@code disable} property to this so enablement follows context automatically.
     *
     * @return the read-only disabled property
     */
    ReadOnlyBooleanProperty disabledProperty();

    /**
     * Executes the command. Implementations must not fail silently: any error is surfaced to
     * the user. Invoked on the JavaFX Application Thread.
     */
    void run();

    /**
     * Releases any listeners this command installed.
     */
    default void dispose() {
    }
}
