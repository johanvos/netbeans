package com.gluonhq.netbeans.nbfx.launcher;

import com.gluonhq.netbeans.nbfx.api.ActionIds;
import com.gluonhq.netbeans.nbfx.api.ActionRegistry;
import com.gluonhq.netbeans.nbfx.api.Command;
import com.gluonhq.netbeans.nbfx.api.EditorDocument;
import com.gluonhq.netbeans.nbfx.api.EditorSettings;
import com.gluonhq.netbeans.nbfx.api.OpenProject;
import com.gluonhq.netbeans.nbfx.api.ProjectRegistry;
import com.gluonhq.netbeans.nbfx.api.RunnableCommand;
import com.gluonhq.netbeans.nbfx.file.actions.FileActions;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import org.openide.util.Lookup;
import org.openide.util.NbBundle;

/**
 * Builds the application menu bar and tool bars from the shared {@link ActionRegistry}, and
 * registers the launcher-owned project commands. All controls are derived from {@link Command}s
 * (text, accelerator, enabled state, action), so the UI stays free of per-control behavior and
 * enablement follows context automatically.
 * <p>
 * Menu items render an icon, text and accelerator; tool bar buttons render only the icon and
 * expose the text plus shortcut through a tooltip. Actions are grouped into a File tool bar
 * (New / Open Project, Save / Save All), a Clipboard tool bar (Cut / Copy / Paste) and an Edit
 * tool bar (Undo / Redo). Each tool bar carries a leading drag handle used to relocate it within
 * the {@link ToolBarContainer}.
 */
final class ActionBars {

    private static final Logger LOG = Logger.getLogger(ActionBars.class.getName());

    private static final String DRAG_HANDLE_ICON = "ToolbarXP.png";

    private static final double TOOLBAR_ICON_SIZE = 24;
    private static final double MENU_ICON_SIZE = 16;
    private static final double DRAG_HANDLE_WIDTH = 12;
    private static final double DRAG_HANDLE_HEIGHT = 24;

    /** Maps a command id to its icon resource (relative to this class' package). */
    private static final Map<String, String> ICONS = new HashMap<>();
    static {
        ICONS.put(ActionIds.NEW_PROJECT, "newProject24.png");
        ICONS.put(ActionIds.OPEN_PROJECT, "openProject24.png");
        ICONS.put(ActionIds.SAVE, "save.png");
        ICONS.put(ActionIds.SAVE_ALL, "saveAll24.png");
        ICONS.put(ActionIds.CUT, "cut.png");
        ICONS.put(ActionIds.COPY, "copy.png");
        ICONS.put(ActionIds.PASTE, "paste.png");
        ICONS.put(ActionIds.UNDO, "undo24.png");
        ICONS.put(ActionIds.REDO, "redo24.png");
        ICONS.put(ActionIds.SELECT_PROJECTS, "projectTab.png");
        ICONS.put(ActionIds.SELECT_FILES, "filesTab.png");
    }

    private final ActionRegistry registry;
    private final EditorSettings editorSettings;
    private final Map<String, Image> imageCache = new HashMap<>();

    // File action dispatch (set via configureFileActions before the main bars are built).
    private FileActions fileActions;
    private ObservableValue<Boolean> treeFocused;

    /** The main window's tool bars, captured in {@link #createToolBars} so the View menu can toggle them. */
    private ToolBarContainer toolBarContainer;

    /**
     * Collects the window-scoped commands created while a single window's bars are being built.
     * FX-thread confined; only non-null for the duration of {@link #createDetachedBars}.
     */
    private List<Command> scopedSink;

    // Recent-projects wiring (set via configureRecentProjects before createMenuBar).
    private Supplier<List<AppState.RecentProject>> recentProjectsSupplier;
    private Consumer<File> openProjectHandler;
    private Runnable clearRecentHandler;
    private BiFunction<File, String, Node> projectIconResolver;
    private Menu recentMenu;

    // Project switching (set via configureProjectSwitching before createMenuBar).
    private ProjectRegistry projects;
    private ProjectSwitcher projectSwitcher;
    private Function<OpenProject, Node> openProjectIconResolver;

    ActionBars() {
        this.registry = Lookup.getDefault().lookup(ActionRegistry.class);
        this.editorSettings = Lookup.getDefault().lookup(EditorSettings.class);
    }

    /**
     * Configures the "Open Recent Project" submenu: a supplier of recent projects, a handler to open a chosen project
     * directory, an action to clear the list, and a resolver that builds each project's icon from its stored icon name.
     */
    void configureRecentProjects(Supplier<List<AppState.RecentProject>> recentProjects, Consumer<File> openProject,
            Runnable clearRecent, BiFunction<File, String, Node> projectIcon) {
        this.recentProjectsSupplier = recentProjects;
        this.openProjectHandler = openProject;
        this.clearRecentHandler = clearRecent;
        this.projectIconResolver = projectIcon;
    }

    /**
     * Configures focus-based file/editor dispatch for the main window's Cut/Copy/Paste/Undo/Redo:
     * when {@code treeFocused} is true those actions target files (via {@code fileActions}), otherwise
     * the editor. Must be called before {@link #createMenuBar(ObservableValue)} /
     * {@link #createToolBars(ObservableValue)}.
     */
    void configureFileActions(FileActions fileActions, ObservableValue<Boolean> treeFocused) {
        this.fileActions = fileActions;
        this.treeFocused = treeFocused;
    }

    /**
     * Registers the launcher-owned project commands (New / Open Project) into the registry.
     * Must be called before {@link #createMenuBar(ObservableValue)} / {@link #createToolBars(ObservableValue)} so their
     * controls can be built.
     */
    void registerProjectCommands(Runnable newProject, Runnable openProject,
            Runnable closeProject, Runnable closeAllProjects, ObservableValue<Boolean> closeDisabled) {
        if (registry == null) {
            LOG.warning("No ActionRegistry found; project actions will not be available");
            return;
        }
        registry.register(new RunnableCommand(ActionIds.NEW_PROJECT, message("CTL_NewProjectCommand"),
                new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                newProject));
        registry.register(new RunnableCommand(ActionIds.OPEN_PROJECT, message("CTL_OpenProjectCommand"),
                new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                openProject));
        registry.register(new RunnableCommand(ActionIds.CLOSE_PROJECT, message("CTL_CloseProjectCommand"),
                null, closeProject, closeDisabled));
        registry.register(new RunnableCommand(ActionIds.CLOSE_ALL_PROJECTS, message("CTL_CloseAllProjectsCommand"),
                null, closeAllProjects, closeDisabled));
    }

    /** The launcher-owned window actions and their enablement, passed to {@link #registerWindowCommands}. */
    record WindowActions(
            Runnable selectProjects, Runnable selectFiles, Runnable selectEditor, Runnable resetWindows,
            Runnable closeDocument, ObservableValue<Boolean> closeDocumentDisabled,
            Runnable closeAllDocuments, ObservableValue<Boolean> closeAllDisabled,
            Runnable closeOtherDocuments, ObservableValue<Boolean> closeOtherDisabled) {
    }

    /**
     * Registers the launcher-owned Window-menu commands (Projects / Files / Editor selection, Reset
     * Windows, and the Close Document/All/Other actions) into the registry. Must be called before
     * {@link #createMenuBar(ObservableValue)} so the Window menu can be built.
     */
    void registerWindowCommands(WindowActions actions) {
        if (registry == null) {
            LOG.warning("No ActionRegistry found; window actions will not be available");
            return;
        }
        registry.register(new RunnableCommand(ActionIds.SELECT_PROJECTS, message("CTL_SelectProjectsCommand"),
                new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN), actions.selectProjects()));
        registry.register(new RunnableCommand(ActionIds.SELECT_FILES, message("CTL_SelectFilesCommand"),
                new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.SHORTCUT_DOWN), actions.selectFiles()));
        registry.register(new RunnableCommand(ActionIds.SELECT_EDITOR, message("CTL_SelectEditorCommand"),
                new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN), actions.selectEditor()));
        registry.register(new RunnableCommand(ActionIds.RESET_WINDOWS, message("CTL_ResetWindowsCommand"),
                null, actions.resetWindows()));
        registry.register(new RunnableCommand(ActionIds.CLOSE_DOCUMENT, message("CTL_CloseDocumentCommand"),
                new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN),
                actions.closeDocument(), actions.closeDocumentDisabled()));
        registry.register(new RunnableCommand(ActionIds.CLOSE_ALL_DOCUMENTS, message("CTL_CloseAllDocumentsCommand"),
                new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                actions.closeAllDocuments(), actions.closeAllDisabled()));
        registry.register(new RunnableCommand(ActionIds.CLOSE_OTHER_DOCUMENTS, message("CTL_CloseOtherDocumentsCommand"),
                null, actions.closeOtherDocuments(), actions.closeOtherDisabled()));
    }

    /**
     * Configures project switching: registers the Next/Previous Project commands and gives the
     * Window menu what it needs to list the open projects - the registry to observe, the
     * {@code switcher} that performs the switch, and a resolver for each project's icon. Must be
     * called before {@link #createMenuBar(ObservableValue)}.
     */
    void configureProjectSwitching(ProjectRegistry registry, ProjectSwitcher switcher,
            Function<OpenProject, Node> projectIcon) {
        this.projects = registry;
        this.projectSwitcher = switcher;
        this.openProjectIconResolver = projectIcon;
        if (this.registry == null || switcher == null) {
            return;
        }
        this.registry.register(new RunnableCommand(ActionIds.NEXT_PROJECT, message("CTL_NextProjectCommand"),
                new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN),
                switcher::next, switcher.disabled()));
        this.registry.register(new RunnableCommand(ActionIds.PREVIOUS_PROJECT, message("CTL_PreviousProjectCommand"),
                new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN),
                switcher::previous, switcher.disabled()));
    }

    /**
     * Builds the menu bar + tool bars for a detached editor window, with its active-document actions scoped
     * to {@code scope}, that window's own selected editor.
     * <p>
     * The window-scoped commands created here observe documents that outlive the window, so they are
     * collected into {@code scopedCommands}; the caller must {@link Command#dispose() dispose} them when
     * the window closes.
     */
    VBox createDetachedBars(ObservableValue<EditorDocument> scope, List<Command> scopedCommands) {
        List<Command> previous = scopedSink;
        scopedSink = scopedCommands;
        try {
            return new VBox(createDetachedMenuBar(scope), createDetachedToolBars(scope));
        } finally {
            scopedSink = previous;
        }
    }

    /**
     * Builds the main menu bar, scoping the active-document actions to {@code mainScope}, so they act on the main
     * window regardless of which window has focus. Save All and project actions stay global.
     */
    MenuBar createMenuBar(ObservableValue<EditorDocument> mainScope) {
        try {
            LOG.info("[NBFX] Start creating menubar");
        Menu fileMenu = new Menu(message("Menu.file"));
        recentMenu = new Menu(message("Menu.openRecent"));
        refreshRecentProjects();
        addMenuItems(fileMenu,
                createMenuItem(ActionIds.NEW_PROJECT, mainScope, treeFocused),
                createMenuItem(ActionIds.OPEN_PROJECT, mainScope, treeFocused),
                recentMenu,
                createMenuItem(ActionIds.CLOSE_PROJECT, mainScope, treeFocused),
                createMenuItem(ActionIds.CLOSE_ALL_PROJECTS, mainScope, treeFocused),
                new SeparatorMenuItem(),
                createMenuItem(ActionIds.SAVE, mainScope, treeFocused),
                createMenuItem(ActionIds.SAVE_ALL, mainScope, treeFocused));

        Menu editMenu = createEditMenu(mainScope, treeFocused);
        Menu viewMenu = createViewMenu();
        Menu windowMenu = createWindowMenu(mainScope);

        Menu helpMenu = new Menu(message("Menu.help"));
        MenuBar menuBar = new MenuBar(fileMenu, editMenu, viewMenu, windowMenu, helpMenu);
        // TODO: Fix https://bugs.openjdk.org/browse/JDK-8388508
        menuBar.setUseSystemMenuBar(true);
        return menuBar;
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return null;
    }

    /**
     * Builds the View menu: a Toolbars submenu with a check item per main-window tool bar (to hide
     * or show it) plus a Reset Toolbars action, and a Show Line Numbers toggle bound to the shared
     * {@link EditorSettings}. The Toolbars submenu is only present once {@link #createToolBars} has
     * created the main tool bars.
     */
    private Menu createViewMenu() {
        Menu viewMenu = new Menu(message("Menu.view"));
        if (toolBarContainer != null) {
            Menu toolbarsMenu = new Menu(message("Menu.view.toolbars"));
            for (ToolBar bar : toolBarContainer.getToolBars()) {
                CheckMenuItem item = new CheckMenuItem(toolBarName(bar.getId()));
                item.selectedProperty().bindBidirectional(bar.visibleProperty());
                toolbarsMenu.getItems().add(item);
            }
            toolbarsMenu.getItems().add(new SeparatorMenuItem());
            MenuItem reset = new MenuItem(message("Menu.view.resetToolbars"));
            reset.setOnAction(e -> toolBarContainer.resetArrangement());
            toolbarsMenu.getItems().add(reset);
            viewMenu.getItems().add(toolbarsMenu);
        }
        if (editorSettings != null) {
            CheckMenuItem lineNumbers = new CheckMenuItem(message("Menu.view.showLineNumbers"));
            lineNumbers.selectedProperty().bindBidirectional(editorSettings.showLineNumbers());
            viewMenu.getItems().add(lineNumbers);
        }
        return viewMenu;
    }

    /** Human-readable name for a tool bar id, from the resource bundle (falls back to the id). */
    private String toolBarName(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        try {
            return message("Toolbar.name." + id);
        } catch (RuntimeException ex) {
            return id;
        }
    }

    /**
     * Builds the Window menu: Projects / Files / Editor selection, Reset Windows, the
     * Close Document / Close All / Close Other actions, all routed through the shared registry, and
     * finally the project section - Next / Previous Project plus one entry per open project.
     */
    private Menu createWindowMenu(ObservableValue<EditorDocument> mainScope) {
        Menu windowMenu = new Menu(message("Menu.window"));
        addMenuItems(windowMenu,
                createMenuItem(ActionIds.SELECT_PROJECTS, mainScope, null),
                createMenuItem(ActionIds.SELECT_FILES, mainScope, null),
                createMenuItem(ActionIds.SELECT_EDITOR, mainScope, null),
                new SeparatorMenuItem(),
                createMenuItem(ActionIds.RESET_WINDOWS, mainScope, null),
                new SeparatorMenuItem(),
                createMenuItem(ActionIds.CLOSE_DOCUMENT, mainScope, null),
                createMenuItem(ActionIds.CLOSE_ALL_DOCUMENTS, mainScope, null),
                createMenuItem(ActionIds.CLOSE_OTHER_DOCUMENTS, mainScope, null));
        if (projectSwitcher != null) {
            addMenuItems(windowMenu,
                    new SeparatorMenuItem(),
                    createMenuItem(ActionIds.NEXT_PROJECT, mainScope, null),
                    createMenuItem(ActionIds.PREVIOUS_PROJECT, mainScope, null));
            installOpenProjects(windowMenu);
        }
        return windowMenu;
    }

    /**
     * Appends the open projects to the Window menu, the way a window manager lists its windows: one
     * checked entry per project, in the order they were opened, marking the selected one. The
     * entries are rebuilt whenever a project is opened or closed, and re-marked when the selection
     * changes - which happens on its own as the user moves between the navigator and the editor
     * tabs, so the menu always names the project the project-scoped actions apply to.
     */
    private void installOpenProjects(Menu windowMenu) {
        int fixedItems = windowMenu.getItems().size();
        Runnable rebuild = () -> {
            windowMenu.getItems().remove(fixedItems, windowMenu.getItems().size());
            List<OpenProject> open = List.copyOf(projects.getOpenProjects());
            if (open.isEmpty()) {
                return;
            }
            // A group per rebuild: the discarded items must not stay referenced by a lasting one.
            ToggleGroup group = new ToggleGroup();
            windowMenu.getItems().add(new SeparatorMenuItem());
            for (OpenProject project : open) {
                windowMenu.getItems().add(createProjectItem(project, group));
            }
        };
        rebuild.run();
        projects.getOpenProjects().addListener((ListChangeListener<OpenProject>) change -> rebuild.run());
        projects.selectedProjectProperty().subscribe(selected -> markSelectedProject(windowMenu, fixedItems, selected));
    }

    /** A Window-menu entry for {@code project}: its name, icon, and a check mark while it is the selected one. */
    private RadioMenuItem createProjectItem(OpenProject project, ToggleGroup group) {
        RadioMenuItem item = new RadioMenuItem(project.getDisplayName());
        // Project names are user data: an underscore in one must not become a mnemonic.
        item.setMnemonicParsing(false);
        item.setToggleGroup(group);
        item.setUserData(project);
        item.setSelected(project.equals(projects.getSelected()));
        if (openProjectIconResolver != null) {
            Node icon = openProjectIconResolver.apply(project);
            if (icon != null) {
                item.setGraphic(icon);
            }
        }
        item.setOnAction(e -> projectSwitcher.switchTo(project));
        return item;
    }

    /** Moves the check mark to {@code selected} among the project entries after {@code fixedItems}. */
    private static void markSelectedProject(Menu windowMenu, int fixedItems, OpenProject selected) {
        List<MenuItem> items = windowMenu.getItems();
        for (int i = fixedItems; i < items.size(); i++) {
            if (items.get(i) instanceof RadioMenuItem item) {
                item.setSelected(item.getUserData() != null && item.getUserData().equals(selected));
            }
        }
    }

    private Menu createEditMenu(ObservableValue<EditorDocument> scope, ObservableValue<Boolean> preferFile) {        Menu editMenu = new Menu(message("Menu.edit"));
        addMenuItems(editMenu,
                createMenuItem(ActionIds.UNDO, scope, preferFile),
                createMenuItem(ActionIds.REDO, scope, preferFile),
                new SeparatorMenuItem(),
                createMenuItem(ActionIds.CUT, scope, preferFile),
                createMenuItem(ActionIds.COPY, scope, preferFile),
                createMenuItem(ActionIds.PASTE, scope, preferFile));
        return editMenu;
    }

    /**
     * Builds a menu bar for a detached editor window, with its active-document actions scoped to
     * {@code scope}, that window's selected editor. Contains a File menu and the same Edit and View menus
     * as the main window, so editor and save actions act on the detached window while it is focused.
     */
    private MenuBar createDetachedMenuBar(ObservableValue<EditorDocument> scope) {
        Menu fileMenu = new Menu(message("Menu.file"));
        addMenuItems(fileMenu,
                createMenuItem(ActionIds.SAVE, scope, null),
                createMenuItem(ActionIds.SAVE_ALL, scope, null));

        Menu helpMenu = new Menu(message("Menu.help"));
        MenuBar menuBar = new MenuBar(fileMenu, createEditMenu(scope, null),
                createViewMenu(), createDetachedWindowMenu(scope), helpMenu);
        menuBar.setUseSystemMenuBar(true);
        return menuBar;
    }

    /**
     * Builds a reduced Window menu for a detached editor window: Close Document / Close All / Close
     * Other Documents, scoped to that window's selected editor ({@code scope}), plus Next / Previous
     * Project. Navigator/layout items (Projects / Files / Editor / Reset Windows) are omitted, as
     * they only apply to the main window, and so is the list of open projects: the selection is
     * shown - and switched to - in the main window's navigator.
     */
    private Menu createDetachedWindowMenu(ObservableValue<EditorDocument> scope) {
        Menu windowMenu = new Menu(message("Menu.window"));
        ObservableList<EditorDocument> documents = openDocuments();

        MenuItem close = new MenuItem(message("CTL_CloseDocumentCommand"));
        close.setAccelerator(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN));
        close.setOnAction(e -> DocumentCloser.closeDocument(scope == null ? null : scope.getValue()));
        if (scope != null) {
            close.disableProperty().bind(Bindings.createBooleanBinding(() -> scope.getValue() == null, scope));
        }

        MenuItem closeAll = new MenuItem(message("CTL_CloseAllDocumentsCommand"));
        closeAll.setAccelerator(new KeyCodeCombination(KeyCode.W,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        closeAll.setOnAction(e -> DocumentCloser.closeAllDocuments());
        closeAll.disableProperty().bind(Bindings.isEmpty(documents));

        MenuItem closeOther = new MenuItem(message("CTL_CloseOtherDocumentsCommand"));
        closeOther.setOnAction(e -> DocumentCloser.closeOtherDocuments(scope == null ? null : scope.getValue()));
        closeOther.disableProperty().bind(Bindings.size(documents).lessThan(2));

        addMenuItems(windowMenu, close, closeAll, closeOther);
        if (projectSwitcher != null) {
            addMenuItems(windowMenu,
                    new SeparatorMenuItem(),
                    createMenuItem(ActionIds.NEXT_PROJECT, scope, null),
                    createMenuItem(ActionIds.PREVIOUS_PROJECT, scope, null));
        }
        return windowMenu;
    }

    /** The shared, observable list of currently open documents (empty if no editor context is present). */
    private static ObservableList<EditorDocument> openDocuments() {
        return EditorContexts.openDocuments();
    }

    /**
     * Rebuilds the "Open Recent Project" submenu from the current recent list. Each entry opens its
     * project; a trailing action clears the list. When empty, a single disabled placeholder is shown.
     */
    void refreshRecentProjects() {
        if (recentMenu == null) {
            return;
        }
        recentMenu.getItems().clear();
        List<AppState.RecentProject> recent = recentProjectsSupplier != null
                ? recentProjectsSupplier.get() : List.of();
        if (recent.isEmpty()) {
            MenuItem empty = new MenuItem(message("Menu.openRecent.empty"));
            empty.setDisable(true);
            recentMenu.getItems().add(empty);
            return;
        }
        for (AppState.RecentProject project : recent) {
            File dir = new File(project.path());
            MenuItem item = new MenuItem(dir.getName());
            item.setMnemonicParsing(false);
            if (projectIconResolver != null && project.iconName() != null) {
                Node icon = projectIconResolver.apply(dir, project.iconName());
                if (icon != null) {
                    item.setGraphic(icon);
                }
            }
            if (openProjectHandler != null) {
                item.setOnAction(e -> openProjectHandler.accept(dir));
            }
            recentMenu.getItems().add(item);
        }
        recentMenu.getItems().add(new SeparatorMenuItem());
        MenuItem clear = new MenuItem(message("Menu.openRecent.clear"));
        if (clearRecentHandler != null) {
            clear.setOnAction(e -> clearRecentHandler.run());
        }
        recentMenu.getItems().add(clear);
    }

    /**
     * Builds the three main tool bars (File, Clipboard, Edit), scoping the active-document actions
     * to {@code mainScope}, and hosts them in a {@link ToolBarContainer} that packs them to the left and top,
     * wraps them onto further rows when needed, and lets the user reorder them by dragging their leading drag handle.
     *
     * @return the tool bar container for the top area
     */
    ToolBarContainer createToolBars(ObservableValue<EditorDocument> mainScope) {
        ToolBar fileBar = createToolBar("file", mainScope, treeFocused, ActionIds.NEW_PROJECT, ActionIds.OPEN_PROJECT,
                ActionIds.SAVE, ActionIds.SAVE_ALL);
        ToolBar clipboardBar = createToolBar("clipboard", mainScope, treeFocused, ActionIds.CUT, ActionIds.COPY, ActionIds.PASTE);
        ToolBar editBar = createToolBar("edit", mainScope, treeFocused, ActionIds.UNDO, ActionIds.REDO);
        toolBarContainer = new ToolBarContainer(fileBar, clipboardBar, editBar);
        return toolBarContainer;
    }

    private ToolBar createToolBar(String id, ObservableValue<EditorDocument> scope,
            ObservableValue<Boolean> preferFile, String... commandIds) {
        ToolBar toolBar = new ToolBar();
        toolBar.setId(id);
        toolBar.getItems().add(createDragHandle());
        addButtons(toolBar, scope, preferFile, commandIds);
        return toolBar;
    }

    /**
     * Builds the tool bars for a detached editor window, scoping the active-document actions to
     * {@code scope}, the window's selected editor. Contains a File bar with just the save actions,
     * plus Clipboard and Edit bars. Unlike the main tool bars these carry no drag handle and are not relocatable.
     */
    private Region createDetachedToolBars(ObservableValue<EditorDocument> scope) {
        ToolBar fileBar = new ToolBar(new Separator());
        addButtons(fileBar, scope, null, ActionIds.SAVE, ActionIds.SAVE_ALL);
        mirrorMainVisibility(fileBar, "file");
        ToolBar clipboardBar = new ToolBar(new Separator());
        addButtons(clipboardBar, scope, null, ActionIds.CUT, ActionIds.COPY, ActionIds.PASTE);
        mirrorMainVisibility(clipboardBar, "clipboard");
        ToolBar editBar = new ToolBar(new Separator());
        addButtons(editBar, scope, null, ActionIds.UNDO, ActionIds.REDO);
        mirrorMainVisibility(editBar, "edit");
        return new HBox(fileBar, clipboardBar, editBar);
    }

    /**
     * Makes a detached window's tool bar follow the visibility of the matching main tool bar, so
     * hiding/showing a tool bar from the View menu (or Reset Toolbars) applies to every window. The
     * main tool bar's {@code visibleProperty} is the single source of truth; the bind uses a weak
     * listener internally, so it is released when the detached window is closed.
     */
    private void mirrorMainVisibility(ToolBar detachedBar, String id) {
        ToolBar mainBar = mainToolBar(id);
        if (mainBar != null) {
            detachedBar.visibleProperty().bind(mainBar.visibleProperty());
            detachedBar.managedProperty().bind(detachedBar.visibleProperty());
        }
    }

    /** Returns the main-window tool bar with the given id, or {@code null} if the tool bars aren't built yet. */
    private ToolBar mainToolBar(String id) {
        if (toolBarContainer == null) {
            return null;
        }
        for (ToolBar bar : toolBarContainer.getToolBars()) {
            if (id.equals(bar.getId())) {
                return bar;
            }
        }
        return null;
    }

    private void addButtons(ToolBar toolBar, ObservableValue<EditorDocument> scope,
            ObservableValue<Boolean> preferFile, String... commandIds) {
        for (String commandId : commandIds) {
            Button button = createButton(commandId, scope, preferFile);
            if (button != null) {
                toolBar.getItems().add(button);
            }
        }
    }

    private Button createDragHandle() {
        Button handle = new Button();
        ImageView graphic = toolbarIcon(DRAG_HANDLE_ICON, DRAG_HANDLE_WIDTH, DRAG_HANDLE_HEIGHT);
        if (graphic != null) {
            handle.setGraphic(graphic);
        }
        handle.getStyleClass().add("toolbar-drag-handle");
        handle.setFocusTraversable(false);
        handle.setTooltip(new Tooltip(message("Toolbar.dragHandle.tooltip")));
        return handle;
    }

    private static void addMenuItems(Menu menu, MenuItem... items) {
        for (MenuItem item : items) {
            if (item != null) {
                menu.getItems().add(item);
            }
        }
    }

    private MenuItem createMenuItem(String commandId, ObservableValue<EditorDocument> scope,
            ObservableValue<Boolean> preferFile) {
        LOG.info("Create menuItem for "+commandId);
        Command command = resolve(commandId, scope, preferFile);
        if (command == null) {
            return null;
        }
        ImageView graphic = menuIcon(ICONS.get(commandId));
        MenuItem item = new MenuItem(command.getText(), graphic);
        item.disableProperty().bind(command.disabledProperty());
        item.setOnAction(e -> command.run());
        KeyCombination accelerator = command.getAccelerator();
        if (accelerator != null) {
            item.setAccelerator(accelerator);
        }
        return item;
    }

    private Button createButton(String commandId, ObservableValue<EditorDocument> scope,
            ObservableValue<Boolean> preferFile) {
        Command command = resolve(commandId, scope, preferFile);
        if (command == null) {
            return null;
        }
        Button button = new Button();
        button.getStyleClass().add("toolbar-button");
        // Action buttons must not take keyboard focus: otherwise clicking one steals focus from the
        // editor/tree, and if the action then disables the button (e.g. Redo emptying the redo stack)
        // focus escapes to another node (the tree), flipping the focus-based Cut/Copy/Paste/Undo/Redo
        // dispatch to the wrong target.
        button.setFocusTraversable(false);
        ImageView graphic = toolbarIcon(ICONS.get(commandId), TOOLBAR_ICON_SIZE, TOOLBAR_ICON_SIZE);
        if (graphic != null) {
            button.setGraphic(graphic);
        } else {
            button.setText(command.getText());
        }
        button.disableProperty().bind(command.disabledProperty());
        button.setOnAction(e -> command.run());
        KeyCombination accelerator = command.getAccelerator();
        String tooltip = accelerator == null
                ? command.getText()
                : command.getText() + " (" + accelerator.getDisplayText() + ")";
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    /** Builds a tool bar graphic by fit-scaling the full-resolution source in an {@link ImageView} */
    private ImageView toolbarIcon(String resourceName, double fitWidth, double fitHeight) {
        Image image = fullImage(resourceName);
        if (image == null) {
            return null;
        }
        ImageView view = new ImageView(image);
        view.setFitWidth(fitWidth);
        view.setFitHeight(fitHeight);
        view.setPreserveRatio(true);
        return view;
    }

    /** Builds a menu graphic from the dedicated 16&times;16 icon variant (named {@code <base>-16.png}). */
    private ImageView menuIcon(String resourceName) {
        if (resourceName == null) {
            return null;
        }
        String menuResource = resourceName.replaceFirst("\\.png$", "-16.png");
        return toolbarIcon(menuResource, MENU_ICON_SIZE, MENU_ICON_SIZE);
    }

    /**
     * Loads (and caches) the full-resolution source image for the given resource name, falling back
     * to the 16&times;16 variant for icons that only ship in that size (menu-only commands).
     */
    private Image fullImage(String resourceName) {
        if (resourceName == null) {
            return null;
        }
        return imageCache.computeIfAbsent(resourceName, name -> {
            var url = ActionBars.class.getResource(name);
            if (url == null) {
                String smallResource = name.replaceFirst("(?<!-16)\\.png$", "-16.png");
                url = ActionBars.class.getResource(smallResource);
            }
            if (url == null) {
                LOG.warning("Icon resource not found: " + name);
                return null;
            }
            return new Image(url.toExternalForm());
        });
    }

    /**
     * Resolves the command for {@code commandId}: a window-scoped variant bound to {@code scope} for active-document
     * actions, or the shared global command for global actions or if no scope is given. When {@code preferFile} is
     * given and the command has a file counterpart (Cut/Copy/Paste/Undo/Redo), returns a {@link DispatchingCommand}
     * that targets files while the tree is focused and the editor otherwise.
     */
    private Command resolve(String commandId, ObservableValue<EditorDocument> scope,
            ObservableValue<Boolean> preferFile) {
        LOG.info("Need to resolve "+commandId+ " and registry = "+registry);
        if (registry == null) {
            return null;
        }
        Command editor = registry.createScoped(commandId, scope)
                .map(this::trackScoped)
                .or(() -> registry.find(commandId))
                .orElse(null);
        LOG.info("Editor = "+editor+" and preferFile = "+preferFile+" and fa = " + fileActions);
        if (preferFile != null && fileActions != null) {
            Command file = fileCommandFor(commandId);
            if (file != null && editor != null) {
                return new DispatchingCommand(editor, file, preferFile);
            }
        }
        if (editor == null) {
            LOG.warning("No command registered for id: " + commandId);
        }
        return editor;
    }

    /** Records a freshly created window-scoped command so the owning window can dispose it on close. */
    private Command trackScoped(Command command) {
        if (scopedSink != null) {
            scopedSink.add(command);
        }
        return command;
    }

    /** Returns the file-scoped command for {@code commandId}, or {@code null} if none applies. */
    private Command fileCommandFor(String commandId) {
        return switch (commandId) {
            case ActionIds.CUT -> fileActions.cutCommand();
            case ActionIds.COPY -> fileActions.copyCommand();
            case ActionIds.PASTE -> fileActions.pasteCommand();
            case ActionIds.UNDO -> fileActions.undoCommand();
            case ActionIds.REDO -> fileActions.redoCommand();
            default -> null;
        };
    }

    private static String message(String key) {
        return NbBundle.getMessage(ActionBars.class, key);
    }
}
