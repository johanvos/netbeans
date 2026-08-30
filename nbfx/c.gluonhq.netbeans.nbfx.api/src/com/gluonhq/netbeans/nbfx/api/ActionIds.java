package com.gluonhq.netbeans.nbfx.api;

/**
 * Stable identifiers for the built-in {@link Command}s, used to look them up from the
 * {@link ActionRegistry} and to wire UI controls to them.
 */
public final class ActionIds {

    /** Creates a new project. */
    public static final String NEW_PROJECT = "newProject";

    /** Opens an existing project. */
    public static final String OPEN_PROJECT = "openProject";

    /** Closes the currently selected project without closing the application. */
    public static final String CLOSE_PROJECT = "closeProject";

    /** Closes every open project without closing the application. */
    public static final String CLOSE_ALL_PROJECTS = "closeAllProjects";

    /** Selects the project after the selected one, wrapping around at the end of the list. */
    public static final String NEXT_PROJECT = "nextProject";

    /** Selects the project before the selected one, wrapping around at the start of the list. */
    public static final String PREVIOUS_PROJECT = "previousProject";

    /** Saves the active editor document. */
    public static final String SAVE = "save";

    /** Saves all modified editor documents. */
    public static final String SAVE_ALL = "saveAll";

    /** Saves the modified documents of the selected project. */
    public static final String SAVE_PROJECT = "saveProject";

    /** Undoes the last edit in the active editor document. */
    public static final String UNDO = "undo";

    /** Redoes the last undone edit in the active editor document. */
    public static final String REDO = "redo";

    /** Copies the current selection of the active editor document to the clipboard. */
    public static final String COPY = "copy";

    /** Cuts the current selection of the active editor document to the clipboard. */
    public static final String CUT = "cut";

    /** Pastes the clipboard content into the active editor document. */
    public static final String PASTE = "paste";

    /** Shows/selects the Projects navigator tab. */
    public static final String SELECT_PROJECTS = "selectProjects";

    /** Shows/selects the Files navigator tab. */
    public static final String SELECT_FILES = "selectFiles";

    /** Selects and focuses the main editor pane. */
    public static final String SELECT_EDITOR = "selectEditor";

    /** Restores the default window layout. */
    public static final String RESET_WINDOWS = "resetWindows";

    /** Closes the selected editor document. */
    public static final String CLOSE_DOCUMENT = "closeDocument";

    /** Closes all open editor documents. */
    public static final String CLOSE_ALL_DOCUMENTS = "closeAllDocuments";

    /** Closes all open editor documents except the selected one. */
    public static final String CLOSE_OTHER_DOCUMENTS = "closeOtherDocuments";

    private ActionIds() {
    }
}
