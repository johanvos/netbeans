package com.gluonhq.netbeans.nbfx.editor.actions;

import com.gluonhq.netbeans.nbfx.api.ActionIds;
import com.gluonhq.netbeans.nbfx.api.ActionRegistry;
import com.gluonhq.netbeans.nbfx.api.Command;
import com.gluonhq.netbeans.nbfx.api.EditorContext;
import com.gluonhq.netbeans.nbfx.api.EditorDocument;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import javafx.beans.value.ObservableValue;
import org.openide.util.Lookup;
import org.openide.util.lookup.ServiceProvider;

/**
 * Default {@link ActionRegistry}. On creation it registers the built-in Save / Save All
 * commands, wiring their enablement to the shared {@link EditorContext}.
 */
@ServiceProvider(service = ActionRegistry.class)
public class ActionRegistryImpl implements ActionRegistry {

    private static final Logger LOG = Logger.getLogger(ActionRegistryImpl.class.getName());

    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final EditorContext context;

    public ActionRegistryImpl() {
        context = Lookup.getDefault().lookup(EditorContext.class);
        if (context == null) {
            LOG.warning("No EditorContext found; Save actions will not be registered");
            return;
        }
        ObservableValue<EditorDocument> activeDocument = context.activeDocumentProperty();
        register(new SaveCommand(activeDocument));
        register(new SaveAllCommand(context));
        register(new SaveProjectCommand(context));
        register(new UndoCommand(activeDocument));
        register(new RedoCommand(activeDocument));
        register(new CopyCommand(activeDocument));
        register(new CutCommand(activeDocument));
        register(new PasteCommand(activeDocument));
    }

    @Override
    public void register(Command command) {
        Objects.requireNonNull(command);
        commands.put(command.getId(), command);
    }

    @Override
    public Optional<Command> find(String id) {
        return Optional.ofNullable(commands.get(id));
    }

    @Override
    public Collection<Command> getCommands() {
        return Collections.unmodifiableCollection(commands.values());
    }

    @Override
    public Optional<Command> createScoped(String id, ObservableValue<EditorDocument> activeDocument) {
        if (context == null || activeDocument == null) {
            return Optional.empty();
        }
        Command command = switch (id) {
            case ActionIds.SAVE -> new SaveCommand(activeDocument);
            case ActionIds.CUT -> new CutCommand(activeDocument);
            case ActionIds.COPY -> new CopyCommand(activeDocument);
            case ActionIds.PASTE -> new PasteCommand(activeDocument);
            case ActionIds.UNDO -> new UndoCommand(activeDocument);
            case ActionIds.REDO -> new RedoCommand(activeDocument);
            default -> null;
        };
        return Optional.ofNullable(command);
    }
}
