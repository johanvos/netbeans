package com.gluonhq.netbeans.nbfx.editor.actions;

import com.gluonhq.netbeans.nbfx.api.ActionIds;
import com.gluonhq.netbeans.nbfx.api.EditorContext;
import com.gluonhq.netbeans.nbfx.api.EditorDocument;
import com.gluonhq.netbeans.nbfx.api.OpenProject;
import com.gluonhq.netbeans.nbfx.api.ProjectRegistry;

import java.util.List;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;

import org.openide.util.Lookup;
import org.openide.util.NbBundle;

/**
 * Saves the modified documents of the selected project only, leaving every other open project's
 * documents alone. Enabled whenever a document of the selected project has unsaved changes, and
 * re-evaluated as the selection moves from one project to another.
 */
class SaveProjectCommand extends SaveDocumentsCommand {

    private final ObservableValue<String> projectPath;

    SaveProjectCommand(EditorContext context) {
        this(context, selectedProjectPath());
    }

    /**
     * @param projectPath the path of the project to save, following the selection
     */
    SaveProjectCommand(EditorContext context, ObservableValue<String> projectPath) {
        super(ActionIds.SAVE_PROJECT,
                NbBundle.getMessage(SaveProjectCommand.class, "CTL_SaveProjectCommand"), null, context);
        this.projectPath = projectPath;
        projectPath.addListener((obs, old, now) -> updateDisabled());
        updateDisabled();
    }

    @Override
    protected List<EditorDocument> scope() {
        String path = projectPath.getValue();
        return path == null ? List.of() : context().documentsOf(path);
    }

    /**
     * The selected project's path, tracked from the {@link ProjectRegistry}, or a constant
     * {@code null} when no registry is registered (the command then stays disabled).
     */
    private static ObservableValue<String> selectedProjectPath() {
        ProjectRegistry registry = Lookup.getDefault().lookup(ProjectRegistry.class);
        return registry == null
                ? new SimpleObjectProperty<>(null)
                : registry.selectedProjectProperty().map(OpenProject::getPath);
    }
}
