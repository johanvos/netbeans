package com.gluonhq.netbeans.nbfx.file.actions;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.gluonhq.netbeans.nbfx.api.ErrorReporter;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;

import org.openide.util.NbBundle;

/**
 * Undo/redo stacks for reversible file operations (currently pastes). A single shared instance
 * ({@link #getDefault()}) is used so that operations triggered from the tree context menu and from
 * the menu-bar/toolbar share one history.
 *
 * <p>History is kept <b>per project</b>: an operation is recorded against the project that owns the
 * folder it changed, and Undo/Redo apply to the {@link #setScope(String) scope} - the selected
 * project. Closing one project therefore drops only its own history
 * ({@link #clear(String)}), leaving the other open projects' history intact. Operations outside
 * every open project share one unscoped history.</p>
 *
 * <p>All methods must be called on the JavaFX Application Thread.</p>
 */
public final class FileUndoManager {

    private static final Logger LOG = Logger.getLogger(FileUndoManager.class.getName());
    private static final FileUndoManager DEFAULT = new FileUndoManager();
    private static final int MAX_HISTORY = 50;

    /** The key the operations that belong to no open project are recorded under. */
    private static final String NO_PROJECT = "";

    /** One undo/redo history, belonging to a single project. */
    private static final class History {
        private final Deque<FileEdit> undoStack = new ArrayDeque<>();
        private final Deque<FileEdit> redoStack = new ArrayDeque<>();
    }

    private final Map<String, History> histories = new LinkedHashMap<>();
    private final ReadOnlyBooleanWrapper canUndo = new ReadOnlyBooleanWrapper(this, "canUndo", false);
    private final ReadOnlyBooleanWrapper canRedo = new ReadOnlyBooleanWrapper(this, "canRedo", false);
    /** The project Undo/Redo currently apply to. */
    private String scope = NO_PROJECT;

    private FileUndoManager() {
    }

    public static FileUndoManager getDefault() {
        return DEFAULT;
    }

    public ReadOnlyBooleanProperty canUndoProperty() {
        return canUndo.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty canRedoProperty() {
        return canRedo.getReadOnlyProperty();
    }

    /**
     * Sets the project Undo/Redo apply to, normally the selected project. A {@code null} path means
     * no project is selected, and the operations performed outside every open project are used.
     */
    public void setScope(String projectPath) {
        String newScope = key(projectPath);
        if (!Objects.equals(scope, newScope)) {
            scope = newScope;
            updateProperties();
        }
    }

    /** The project Undo/Redo currently apply to, or {@code null} when none is selected. */
    public String getScope() {
        return NO_PROJECT.equals(scope) ? null : scope;
    }

    /**
     * Records a freshly performed edit against the project that owns it, clearing that project's
     * redo history.
     *
     * @param edit        the edit performed
     * @param projectPath the path of the project the edit belongs to, or {@code null} when it
     *                    happened outside every open project
     */
    void push(FileEdit edit, String projectPath) {
        History history = history(key(projectPath));
        history.undoStack.push(edit);
        while (history.undoStack.size() > MAX_HISTORY) {
            history.undoStack.removeLast();
        }
        history.redoStack.clear();
        updateProperties();
    }

    /** Clears the undo/redo history of every project. */
    public void clear() {
        histories.clear();
        updateProperties();
    }

    /**
     * Clears the undo/redo history of one project, e.g. when it is closed. The other projects'
     * history is untouched.
     *
     * @param projectPath the path of the project whose history is dropped
     */
    public void clear(String projectPath) {
        histories.remove(key(projectPath));
        updateProperties();
    }

    /** Undoes the most recent edit of the current scope, reporting failures visibly. */
    public void undo() {
        History history = history(scope);
        if (history.undoStack.isEmpty()) {
            return;
        }
        FileEdit edit = history.undoStack.pop();
        try {
            edit.undo();
            history.redoStack.push(edit);
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Failed to undo file operation", ex);
            history.undoStack.push(edit);
            showError("Undo", ex);
        }
        updateProperties();
    }

    /** Redoes the most recently undone edit of the current scope, reporting failures visibly. */
    public void redo() {
        History history = history(scope);
        if (history.redoStack.isEmpty()) {
            return;
        }
        FileEdit edit = history.redoStack.pop();
        try {
            edit.redo();
            history.undoStack.push(edit);
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Failed to redo file operation", ex);
            history.redoStack.push(edit);
            showError("Redo", ex);
        }
        updateProperties();
    }

    /** The history of {@code key}, created on first use. */
    private History history(String key) {
        return histories.computeIfAbsent(key, unused -> new History());
    }

    private static String key(String projectPath) {
        return projectPath == null ? NO_PROJECT : projectPath;
    }

    private void updateProperties() {
        History history = histories.get(scope);
        canUndo.set(history != null && !history.undoStack.isEmpty());
        canRedo.set(history != null && !history.redoStack.isEmpty());
    }

    private static void showError(String action, IOException ex) {
        String message = ex.getMessage();
        String safe = (message == null || message.isBlank()) ? ex.getClass().getSimpleName() : message;
        ErrorReporter.report(NbBundle.getMessage(FileUndoManager.class, "FileUndo.title"), null,
                NbBundle.getMessage(FileUndoManager.class, "FileUndo.error", action, safe), ex);
    }
}
