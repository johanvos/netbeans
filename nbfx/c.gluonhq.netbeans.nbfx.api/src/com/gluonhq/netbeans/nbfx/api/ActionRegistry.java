package com.gluonhq.netbeans.nbfx.api;

import java.util.Collection;
import java.util.Optional;

import javafx.beans.value.ObservableValue;

/**
 * A shared registry of application {@link Command}s.
 * <p>
 * It is the single place through which all actions are published and discovered: behavior is
 * registered once (by the action providers) and every UI surface (menu, toolbar, shortcuts)
 * builds its controls from the registered commands. The registry is resolved through the
 * global {@link org.openide.util.Lookup}.
 */
public interface ActionRegistry {

    /**
     * Registers a command, replacing any previously registered command with the same id.
     *
     * @param command the command to register
     */
    void register(Command command);

    /**
     * Looks up a command by its id.
     *
     * @param id the command id (see {@link ActionIds})
     * @return the command, or an empty optional if none is registered under {@code id}
     */
    Optional<Command> find(String id);

    /**
     * Returns all registered commands.
     *
     * @return an unmodifiable view of the registered commands
     */
    Collection<Command> getCommands();

    /**
     * Creates a window-scoped variant of an active-document command for {@code activeDocument}
     * instead of the global active document. This lets each window (main or detached) drive its
     * own toolbar/menu from its own selected editor, independent of which window has focus.
     *
     * @param id             the command id (see {@link ActionIds})
     * @param activeDocument the window's active document, observed for changes
     * @return a fresh scoped command, or empty if {@code id} is not an active-document command
     */
    Optional<Command> createScoped(String id, ObservableValue<EditorDocument> activeDocument);
}
