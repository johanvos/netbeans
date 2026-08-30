package com.gluonhq.netbeans.nbfx.launcher;

import com.gluonhq.netbeans.nbfx.api.ContentManager;
import com.gluonhq.netbeans.nbfx.api.EditorContext;
import com.gluonhq.netbeans.nbfx.api.EditorDocument;
import com.gluonhq.netbeans.nbfx.api.EditorSettings;
import com.gluonhq.netbeans.nbfx.api.NavigatorProvider;
import com.gluonhq.netbeans.nbfx.api.OpenProject;
import com.gluonhq.netbeans.nbfx.api.ProjectRegistry;
import com.gluonhq.netbeans.nbfx.file.actions.FileActions;
import com.gluonhq.netbeans.nbfx.file.actions.FileUndoManager;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Subscription;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;
import org.openide.util.lookup.Lookups;

public class JavaFXLaunchApp extends Application {

    private final static Logger LOG = Logger.getLogger(JavaFXLaunchApp.class.getName());

    private Stage stage;
    private Collection<? extends NavigatorProvider> providers;
    private final AppState appState = new AppState();
    private final SessionRestorer sessionRestorer = new SessionRestorer(appState, this::navigatorTabFor);
    private SplitPane splitPane;
    private NbfxTabPane navigatorPane;
    private ActionBars actionBars;
    private StatusBar statusBar;
    private final ProjectRegistry projectRegistry = projectRegistry();
    private boolean projectStateCaptured;
    /** Whether at least one project is currently open; drives Close Project enablement. */
    private final BooleanBinding projectOpen = Bindings.isNotEmpty(projectRegistry.getOpenProjects());
    /** The projects whose trees are still loading; several can load at once. */
    private final ProjectLoads loads = new ProjectLoads();
    /** Moves the selection between open projects, from the Window menu and its shortcuts. */
    private final ProjectSwitcher projectSwitcher = new ProjectSwitcher(projectRegistry, this::revealProject);
    /** Watches the open projects' root folders, so one that is gone from disk does not stay open. */
    private final ProjectRootWatcher rootWatcher = new ProjectRootWatcher(this::projectRootGone);
    /** Picks up changes made to the open projects outside the IDE, on window activation. */
    private final FileSystemRefresher refresher = new FileSystemRefresher(this::openProjectRoots, this::refreshProjects);
    /** Whether the whole application has lost focus, i.e. the user went to another application. */
    private boolean applicationDeactivated;
    /** The focus subscription of every window that is showing, so none is subscribed to twice. */
    private final Map<Window, Subscription> focusWatches = new IdentityHashMap<>();
    /** The projects of the restored session that have not finished loading yet. */
    private final Set<String> pendingSessionProjects = new LinkedHashSet<>();
    /** Whether the last session's projects are still being reopened. */
    private boolean restoringSession;
    /** The project that was selected when the last session ended, reselected once it is restored. */
    private String sessionSelected;
    /* Dispatch for Cut/Copy/Paste/Undo/Redo on files while the project tree is focused, on the editor otherwise. */
    private final ObjectProperty<List<FileObject>> navigatorSelection = new SimpleObjectProperty<>(List.of());
    private final FileActions fileActions = new FileActions(navigatorSelection);
    /** Whether the treeView is focused or not. */
    private final BooleanProperty treeFocused = new SimpleBooleanProperty(false) {
        @Override
        protected void invalidated() {
            if (get()) {
                // The system clipboard is not observable; re-check it whenever the tree regains focus
                fileActions.refreshClipboardState();
            }
        }
    };
    private final PseudoClass FOCUS_WITH_IN_TAB_PANE = PseudoClass.getPseudoClass("focus-in-tabpane");

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        // Resolve navigator providers up front
        providers = Lookup.getDefault().lookupAll(NavigatorProvider.class);
        LOG.fine("PROVIDERS = " + providers);
        for (NavigatorProvider provider : providers) {
            provider.selectedFiles().subscribe(navigatorSelection::set);
            // A project's tree is built in the background and reported back through this callback,
            // which resolves the project from the loaded root: several can be loading at once.
            provider.setOnProjectLoaded(this::onProjectLoaded);
            provider.setOnProjectLoadFailed(this::onProjectLoadFailed);
        }

        Region leftArea = createNavigator();
        Region mainArea = createMainArea();
        splitPane = new SplitPane(leftArea, mainArea);
        BorderPane borderPane = new BorderPane(splitPane);

        actionBars = new ActionBars();
        actionBars.registerProjectCommands(this::newProject, this::openProject,
                this::closeProject, this::closeAllProjects, projectOpen.not());
        actionBars.configureRecentProjects(appState::getRecentProjects, this::openProject,
                this::clearRecentProjects, this::projectIcon);
        actionBars.configureProjectSwitching(projectRegistry, projectSwitcher, this::projectIcon);
        actionBars.configureFileActions(fileActions, treeFocused);
        // The main window's menu/tool bars are scoped to the main pane's own selected editor.
        ObservableValue<EditorDocument> mainScope = mainPaneActiveDocument();
        actionBars.registerWindowCommands(windowActions(mainScope));
        ToolBarContainer toolBars = actionBars.createToolBars(mainScope);
        VBox topBars = new VBox(actionBars.createMenuBar(mainScope), toolBars);
        borderPane.setTop(topBars);
        // Pass actionBars to the detached windows, to have their own scoped menu/tool bars
        NbfxTabPane.setActionBars(actionBars);
        // Let detached editor windows reveal their selected file in the main window's navigator.
        NbfxTabPane.setNavigatorRevealer(this::revealFileInNavigator);

        statusBar = new StatusBar();
        borderPane.setBottom(statusBar);

        // The selected project follows the navigator selection (handled by the navigator itself) and,
        // when the user moves to another editor tab (in this window or a detached one), the project
        // owning that tab's file.
        ObservableValue<EditorDocument> activeDocument = EditorContexts.activeDocument();

        Scene scene = new Scene(borderPane, AppState.DEFAULT_WIDTH, AppState.DEFAULT_HEIGHT);
        // Reflect which area (navigator tree vs editor) holds keyboard focus, both when the focus owner
        // changes within the window and when the window itself (re)gains focus.
        scene.focusOwnerProperty().subscribe(fo -> {
            if (stage.isFocused()) {
                updateFocusScope(fo, leftArea, mainArea);
                // Moving the focus into an area makes what is current there decide the project: the
                // file being edited, or the file selected in the navigator tree. Their own change
                // events are not enough, as clicking the tab or the tree node that is ALREADY
                // selected fires none, yet the user has just moved back to that project.
                if (isWithin(fo, mainArea) && activeDocument != null) {
                    selectProjectOf(activeDocument.getValue());
                } else if (isWithin(fo, leftArea)) {
                    selectProjectOf(focusedNavigatorFile(fo));
                }
            }
        });
        stage.focusedProperty().subscribe(focused -> {
            if (!focused) {
                leftArea.pseudoClassStateChanged(FOCUS_WITH_IN_TAB_PANE, false);
                mainArea.pseudoClassStateChanged(FOCUS_WITH_IN_TAB_PANE, false);
                treeFocused.set(false);
            } else if (isWithin(scene.getFocusOwner(), leftArea)) {
                leftArea.pseudoClassStateChanged(FOCUS_WITH_IN_TAB_PANE, true);
                mainArea.pseudoClassStateChanged(FOCUS_WITH_IN_TAB_PANE, false);
                treeFocused.set(isNavigatorFocused(scene.getFocusOwner()));
            } else {
                leftArea.pseudoClassStateChanged(FOCUS_WITH_IN_TAB_PANE, false);
                mainArea.pseudoClassStateChanged(FOCUS_WITH_IN_TAB_PANE, true);
                treeFocused.set(isNavigatorFocused(scene.getFocusOwner()));
            }
        });
        scene.getStylesheets().add(JavaFXLaunchApp.class.getResource("styles.css").toExternalForm());
        // Shift+Cmd+1 / Shift+Cmd+2 reveal the active editor's file in the Logical / Physical view.
        NbfxTabPane.installNavigatorRevealShortcut(scene, this::activeEditorFile);
        // Project switching must work wherever the focus is, including the navigator tree, whose own
        // arrow-key handling would otherwise swallow the menu accelerator.
        NbfxTabPane.setProjectSwitcher(projectSwitcher);
        NbfxTabPane.installProjectSwitchShortcut(scene);
        stage.setTitle(WindowTitles.of(projectRegistry.getSelected()));
        projectRegistry.selectedProjectProperty().subscribe(project -> {
            stage.setTitle(WindowTitles.of(project));
            statusBar.setProject(project == null ? null : project.getDisplayName());
            // Undo/Redo of file operations apply to the selected project's own history.
            FileUndoManager.getDefault().setScope(project == null ? null : project.getPath());
        });
        if (activeDocument != null) {
            activeDocument.subscribe(this::selectProjectOf);
        }
        stage.setScene(scene);

        // Restore + track window bounds before showing; register the save hook.
        appState.initWindow(stage);
        appState.initToolbars(toolBars);
        appState.initViewSettings(Lookup.getDefault().lookup(EditorSettings.class));
        appState.installShutdownHook();

        // The window-close gesture is the only interactive path: confirm any
        // unsaved changes, then request shutdown. Actual teardown (persist +
        // exit) is centralized in stop(), invoked by Platform.exit().
        stage.setOnCloseRequest(event -> {
            if (!confirmClose()) {
                event.consume();
                return;
            }
            // Capture while the window is still showing, so editor scroll (top visible line) can be
            // read via screen coordinates before the stage is hidden by Platform.exit().
            captureSession();
            projectStateCaptured = true;
            Platform.exit();
        });

        // Everything the IDE knows about the open projects comes from the platform's filesystem
        // layer, which does not see changes made outside the IDE until it is refreshed. Coming back
        // from another application is exactly when the user may have just made some.
        watchApplicationFocus();

        stage.show();

        // Divider position is only meaningful once the SplitPane is laid out.
        Platform.runLater(() -> {
            appState.initDivider(splitPane);
            restoreSessionProjects();
        });
    }

    private boolean confirmClose() {
        return CloseConfirmation.confirmClose(EditorContexts.documentsSnapshot());
    }

    /**
     * Follows the focus of every window of the application, the main stage and the windows detached
     * tabs live in, so that a refresh is asked for when the user comes back from another
     * application - and not when they move between our own windows, which cannot have changed
     * anything on disk. This is what the IDE gets from {@code WindowEvent.getOppositeWindow()}.
     */
    private void watchApplicationFocus() {
        ObservableList<Window> windows = Window.getWindows();
        windows.forEach(this::watchFocus);
        windows.addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                change.getRemoved().forEach(this::unwatchFocus);
                change.getAddedSubList().forEach(this::watchFocus);
            }
        });
    }

    private void watchFocus(Window window) {
        // That list follows show/hide, not the life of a window: a completion popup or a drag
        // overlay is shown again and again, and would collect a subscription every time.
        focusWatches.computeIfAbsent(window,
                w -> w.focusedProperty().subscribe((was, now) -> applicationFocusChanged(now)));
    }

    private void unwatchFocus(Window window) {
        Subscription watch = focusWatches.remove(window);
        if (watch != null) {
            watch.unsubscribe();
        }
    }

    private void applicationFocusChanged(boolean focused) {
        if (focused) {
            if (applicationDeactivated) {
                applicationDeactivated = false;
                refresher.requestRefresh();
            }
        } else {
            // Focus may just be moving to another window of ours, which is only visible once that
            // window has taken it.
            Platform.runLater(() -> {
                if (Window.getWindows().stream().noneMatch(Window::isFocused)) {
                    applicationDeactivated = true;
                }
            });
        }
    }

    /**
     * Brings the open projects in line with what is on disk, on the refresh thread: first the ones
     * that are no longer there, which are closed rather than refreshed, then the rest.
     */
    private void refreshProjects(File[] roots) {
        rootWatcher.checkRoots();
        FileUtil.refreshFor(roots);
    }

    /** The root folders of the open projects, as the refresher needs them: plain files, off the FX thread. */
    private List<File> openProjectRoots() {
        List<File> dirs = new ArrayList<>();
        for (OpenProject project : List.copyOf(projectRegistry.getOpenProjects())) {
            dirs.add(new File(project.getPath()));
        }
        return dirs;
    }

    @Override
    public void stop() {
        // Single teardown point: capture the tree state and window layout, persist, then shut down.
        if (!projectStateCaptured) {
            captureSession();
        }
        appState.save();
        refresher.shutdown();
        LOG.info("JavaFX application stopping, shutting down NetBeans Platform...");
        System.exit(0);
    }

    private TabPane createNavigator() {
        navigatorPane = new NbfxTabPane(NbfxTabPane.PaneRole.NAVIGATOR);
        // Every persisted navigator tab is docked here first, whichever pane the layout last put it
        // in; opening a project then redistributes them (restoreLayout). This keeps every navigator
        // view reachable even when no project is ever opened.
        AppState.Layout layout = sessionRestorer.startupLayout();
        List<String> order = navigatorIdsOf(layout);
        for (NavigatorProvider provider : orderedProviders(order)) {
            // A provider missing from a persisted layout was closed by the user; Reset Windows brings
            // it back. Only the absence of any layout at all (first run) docks every provider - an
            // empty order means the user closed them all, and docking them here would flash them into
            // the pane until the project's own layout removed them again.
            if (layout.isEmpty() || order.contains(navigatorId(provider))) {
                NbfxTabPane.attachTab(navigatorPane, createNavigatorTab(provider));
            }
        }
        String selected = navigatorSelectedOf(layout);
        if (selected != null) {
            for (Tab tab : navigatorPane.getTabs()) {
                if (selected.equals(NbfxTabPane.navigatorId(tab))) {
                    navigatorPane.getSelectionModel().select(tab);
                    break;
                }
            }
        }
        return navigatorPane;
    }

    /** The navigator provider ids held by {@code layout}, in pane order then tab order. */
    private static List<String> navigatorIdsOf(AppState.Layout layout) {
        List<String> ids = new ArrayList<>();
        for (AppState.PaneLayout pane : layout.panes()) {
            for (AppState.TabEntry tab : pane.tabs()) {
                if (tab.kind() == AppState.TabKind.NAVIGATOR) {
                    ids.add(tab.id());
                }
            }
        }
        return ids;
    }

    /** The navigator provider selected in {@code layout}'s navigator pane, or {@code null}. */
    private static String navigatorSelectedOf(AppState.Layout layout) {
        for (AppState.PaneLayout pane : layout.panes()) {
            if (pane.role() != NbfxTabPane.PaneRole.NAVIGATOR) {
                continue;
            }
            AppState.TabEntry active = pane.activeIndex() >= 0 && pane.activeIndex() < pane.tabs().size()
                    ? pane.tabs().get(pane.activeIndex()) : null;
            return active != null && active.kind() == AppState.TabKind.NAVIGATOR ? active.id() : null;
        }
        return null;
    }

    /**
     * The tab of the navigator provider identified by {@code providerId}: the live one, wherever it
     * currently is, or a freshly built one if that provider has no tab. Returns {@code null} for an
     * unknown provider (one persisted by an earlier run but no longer installed).
     */
    private Tab navigatorTabFor(String providerId) {
        return NbfxTabPane.findNavigatorTab(providerId).orElseGet(() -> {
            for (NavigatorProvider provider : providers) {
                if (navigatorId(provider).equals(providerId)) {
                    return createNavigatorTab(provider);
                }
            }
            return null;
        });
    }

    /** Builds a navigator tab for {@code provider}: a draggable Label graphic, its view, and its stable id. */
    private Tab createNavigatorTab(NavigatorProvider provider) {
        Tab tab = new Tab();
        // A Label graphic (not tab text) is required so the tab can be dragged/detached.
        tab.setGraphic(new Label(provider.getTitle()));
        tab.setContent(provider.getView());
        NbfxTabPane.setNavigatorId(tab, navigatorId(provider));
        int index = new ArrayList<>(providers).indexOf(provider);
        NbfxTabPane.installTabLabelTooltip(tab, NbBundle.getMessage(JavaFXLaunchApp.class,
                "Tab.navigator.tooltip", provider.getDescription(),
                NbfxTabPane.SYM_SHIFT, NbfxTabPane.SYM_CMD, String.valueOf(index + 1)));
        NbfxTabPane.installCloseButtonTooltip(tab,
                NbBundle.getMessage(JavaFXLaunchApp.class, "Tab.navigator.close.tooltip"));
        return tab;
    }

    /** The stable persistence id for a navigator provider (its implementation class name). */
    private static String navigatorId(NavigatorProvider provider) {
        return provider.getClass().getName();
    }

    /** Providers ordered by {@code order} (persisted ids); unknown providers keep their natural order at the end. */
    private List<NavigatorProvider> orderedProviders(List<String> order) {
        List<NavigatorProvider> ordered = new ArrayList<>(providers);
        if (!order.isEmpty()) {
            ordered.sort(Comparator.comparingInt(p -> {
                int i = order.indexOf(navigatorId(p));
                return i < 0 ? Integer.MAX_VALUE : i;
            }));
        }
        return ordered;
    }


    private Region createMainArea() {
        ContentManager contentManager = Lookup.getDefault().lookup(ContentManager.class);
        if (contentManager == null) {
            LOG.severe("We have no content manager!!");
            return new StackPane(new Label("No content manager found"));
        }
        if (contentManager instanceof ContentManagerImpl cmi) {
            return cmi.getMainPane();
        } else {
            LOG.severe("We have competing content managers!");
            return new StackPane(new Label("Competing content manager found"));
        }
    }

    /** The main pane's active-document observable, or {@code null} if no content manager is present. */
    private ObservableValue<EditorDocument> mainPaneActiveDocument() {
        ContentManager contentManager = Lookup.getDefault().lookup(ContentManager.class);
        return contentManager != null ? contentManager.mainPaneActiveDocument() : null;
    }

    private ContentManager contentManager() {
        return Lookup.getDefault().lookup(ContentManager.class);
    }

    /** Builds the Window-menu actions and their context-driven enablement, scoped to the main editor pane. */
    private ActionBars.WindowActions windowActions(ObservableValue<EditorDocument> mainScope) {
        ObservableList<EditorDocument> documents = EditorContexts.openDocuments();
        ObservableValue<Boolean> closeDocumentDisabled = mainScope == null
                ? new SimpleBooleanProperty(true)
                : Bindings.createBooleanBinding(() -> mainScope.getValue() == null, mainScope);
        ObservableValue<Boolean> closeAllDisabled = Bindings.isEmpty(documents);
        ObservableValue<Boolean> closeOtherDisabled = Bindings.size(documents).lessThan(2);
        return new ActionBars.WindowActions(
                () -> selectNavigator(firstNavigatorId(0)),
                () -> selectNavigator(firstNavigatorId(1)),
                this::selectEditor,
                this::resetWindows,
                () -> DocumentCloser.closeDocument(mainScope == null ? null : mainScope.getValue()),
                closeDocumentDisabled,
                DocumentCloser::closeAllDocuments, closeAllDisabled,
                () -> DocumentCloser.closeOtherDocuments(mainScope == null ? null : mainScope.getValue()),
                closeOtherDisabled);
    }

    /** The stable navigator id of the provider at {@code index} in natural order, or {@code null} if absent. */
    private String firstNavigatorId(int index) {
        List<NavigatorProvider> list = new ArrayList<>(providers);
        return index < list.size() ? navigatorId(list.get(index)) : null;
    }

    /** Shows the navigator tab for {@code providerId}: selects it or reopens it in the docked navigator pane. */
    private void selectNavigator(String providerId) {
        selectNavigator(providerId, true);
    }

    private void selectNavigator(String providerId, boolean focusTree) {
        if (providerId == null) {
            return;
        }
        for (TabPane pane : NbfxTabPane.tabPanes()) {
            for (Tab tab : pane.getTabs()) {
                if (providerId.equals(NbfxTabPane.navigatorId(tab))) {
                    NbfxTabPane.selectTabAndMoveToFront(tab);
                    Node content = tab.getContent();
                    if (focusTree && content != null) {
                        Platform.runLater(content::requestFocus);
                    }
                    return;
                }
            }
        }
        reopenNavigatorTab(providerId);
    }

    /** Recreates and selects a closed navigator tab in the docked navigator pane. */
    private void reopenNavigatorTab(String providerId) {
        Tab tab = navigatorTabFor(providerId);
        if (tab != null) {
            NbfxTabPane.attachTab(navigatorPane, tab);
            navigatorPane.getSelectionModel().select(tab);
        }
    }

    /**
     * Reveals {@code file} in the navigator provider at {@code index}, showing its tab first and
     * bringing the main window to the front.
     */
    private void revealFileInNavigator(FileObject file, int index) {
        if (file == null) {
            return;
        }
        List<NavigatorProvider> list = new ArrayList<>(providers);
        if (index < 0 || index >= list.size()) {
            return;
        }
        NavigatorProvider provider = list.get(index);
        NbfxTabPane.setNavigatorRevealInProgress(true);
        selectNavigator(navigatorId(provider), false);
        provider.revealFile(file);
        Platform.runLater(() -> focusNavigatorView(provider.getView()));
    }

    /**
     * Activates the main window and moves keyboard focus into the navigator {@code view}.
     * <p>
     * Focus may not land synchronously (activating the main window from a detached one is
     * asynchronous), so the reveal flag is cleared from a one-shot listener once focus actually
     * arrives, with an unconditional fallback on the next pulse. Leaving the flag set would
     * permanently stop {@link NbfxTabPane} from re-focusing the editor on window activation.
     */
    private void focusNavigatorView(Node view) {
        if (view == null) {
            NbfxTabPane.setNavigatorRevealInProgress(false);
            return;
        }
        if (stage != null) {
            stage.toFront();
            stage.requestFocus();
        }
        ChangeListener<Boolean> onFocused = new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends Boolean> obs, Boolean was, Boolean isFocused) {
                if (isFocused) {
                    view.focusedProperty().removeListener(this);
                    NbfxTabPane.setNavigatorRevealInProgress(false);
                }
            }
        };
        view.focusedProperty().addListener(onFocused);
        view.requestFocus();
        Platform.runLater(() -> {
            view.focusedProperty().removeListener(onFocused);
            NbfxTabPane.setNavigatorRevealInProgress(false);
        });
    }

    /** The file of the editor visible in the main pane, falling back to the globally active document. */
    private FileObject activeEditorFile() {
        ContentManager cm = contentManager();
        FileObject file = cm != null ? cm.getActiveFile() : null;
        if (file != null) {
            return file;
        }
        EditorContext context = Lookup.getDefault().lookup(EditorContext.class);
        EditorDocument document = context != null ? context.getActiveDocument() : null;
        return document != null ? document.getFileObject() : null;
    }

    /** Focuses the main editor pane's selected tab so its caret blinks. */
    private void selectEditor() {
        ContentManager cm = contentManager();
        if (cm != null) {
            cm.focusActiveEditor();
        }
    }

    /**
     * Restores the default layout: re-docks every detached window back into the main/navigator panes,
     * moves each tab back to its home pane (navigator tabs to the navigator pane, editor tabs to the
     * main pane), restores any closed navigator tab in default order (Projects selected), and resets
     * the split divider.
     */
    private void resetWindows() {
        ContentManager cm = contentManager();
        TabPane mainPane = cm instanceof ContentManagerImpl cmi ? (TabPane) cmi.getMainPane() : null;
        if (mainPane != null) {
            NbfxTabPane.redockAll(mainPane, navigatorPane);
            // Editor tabs dragged into the navigator pane belong back in the editor pane.
            for (Tab tab : List.copyOf(navigatorPane.getTabs())) {
                if (NbfxTabPane.documentOf(tab) != null) {
                    NbfxTabPane.moveTab(mainPane, tab, mainPane.getTabs().size());
                }
            }
        }
        // Reuse the live tab of each provider wherever it currently sits (it may have been dragged
        // into the editor pane), so no emptied duplicate is left behind there.
        int index = 0;
        for (NavigatorProvider provider : providers) {
            Tab tab = navigatorTabFor(navigatorId(provider));
            if (tab != null) {
                NbfxTabPane.moveTab(navigatorPane, tab, index++);
            }
        }
        if (!navigatorPane.getTabs().isEmpty()) {
            navigatorPane.getSelectionModel().select(0);
        }
        if (splitPane != null) {
            splitPane.setDividerPositions(AppState.DEFAULT_DIVIDER);
        }
    }

    /**
     * Sets the {@code focus-in-tabpane} styling on whichever of the navigator ({@code leftArea}) or
     * editor ({@code mainArea}) currently owns keyboard focus, and tracks whether that focus is in a
     * navigator view.
     */
    private void updateFocusScope(Node focusOwner, Node leftArea, Node mainArea) {
        if (isWithin(focusOwner, leftArea)) {
            leftArea.pseudoClassStateChanged(FOCUS_WITH_IN_TAB_PANE, true);
            mainArea.pseudoClassStateChanged(FOCUS_WITH_IN_TAB_PANE, false);
            treeFocused.set(isNavigatorFocused(focusOwner));
        } else if (isWithin(focusOwner, mainArea)) {
            leftArea.pseudoClassStateChanged(FOCUS_WITH_IN_TAB_PANE, false);
            mainArea.pseudoClassStateChanged(FOCUS_WITH_IN_TAB_PANE, true);
            treeFocused.set(isNavigatorFocused(focusOwner));
        }
    }

    /**
     * Whether {@code focusOwner} sits inside one of the navigator views.
     * <p>
     * This drives which target Cut/Copy/Paste act on, so it must test the view itself and not the
     * pane holding it: tabs can be dragged between panes, so an editor may well live in the
     * navigator pane (where treating it as the tree would cut the file selected in the tree instead
     * of the editor's selection) and a navigator view in the editor pane.
     */
    private boolean isNavigatorFocused(Node focusOwner) {
        if (focusOwner == null || providers == null) {
            return false;
        }
        for (NavigatorProvider provider : providers) {
            if (isWithin(focusOwner, provider.getView())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The file selected in the navigator view that holds {@code focusOwner}, or {@code null} if the
     * focus is not inside a navigator tree. Read from the focused provider rather than from the
     * shared selection so the Logical and Physical views do not answer for each other.
     */
    private FileObject focusedNavigatorFile(Node focusOwner) {
        if (focusOwner == null || providers == null) {
            return null;
        }
        for (NavigatorProvider provider : providers) {
            if (isWithin(focusOwner, provider.getView())) {
                return provider.selectedFile().getValue();
            }
        }
        return null;
    }

    private static boolean isWithin(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    void setLogging() {
        System.setProperty("netbeans.logger.console", "true");
        java.util.logging.Logger rootLog = java.util.logging.Logger.getLogger("");
        rootLog.getHandlers()[0].setLevel(java.util.logging.Level.ALL);
        java.util.logging.Logger.getLogger("org.openide.util.Lookup").setLevel(java.util.logging.Level.FINEST);
        java.util.logging.Logger.getLogger("org.openide.util.lookup.MetaInfServicesLookup").setLevel(java.util.logging.Level.FINEST);
        java.util.logging.Logger.getLogger("org.netbeans.core.startup").setLevel(java.util.logging.Level.FINEST);
    }

    /** The registry of open projects, resolved from the Lookup or a local instance if the service is not registered. */
    private static ProjectRegistry projectRegistry() {
        ProjectRegistry registry = Lookup.getDefault().lookup(ProjectRegistry.class);
        if (registry == null) {
            LOG.warning("No ProjectRegistry found in the Lookup; using a local instance");
            registry = new ProjectRegistryImpl();
        }
        return registry;
    }

    /**
     * Selects the project owning {@code document}, so that moving to another editor tab moves the
     * project-scoped actions (Close Project, Save Project, file Undo/Redo) with it. Documents that
     * belong to no open project leave the selection alone.
     */
    private void selectProjectOf(EditorDocument document) {
        if (document == null) {
            return;
        }
        // Resolved from the file rather than from the document's own project path, so a document
        // that was created before its project was registered still finds its owner.
        selectProjectOf(document.getFileObject());
    }

    /** Selects the project owning {@code file}; files outside every open project change nothing. */
    private void selectProjectOf(FileObject file) {
        if (file == null) {
            return;
        }
        OpenProject owner = projectRegistry.ownerOf(file);
        if (owner != null) {
            projectRegistry.select(owner);
        }
    }

    /** The directories of every open project, in the order they were opened. */
    private List<File> openProjectFiles() {
        return projectRegistry.getOpenProjects().stream()
                .map(project -> new File(project.getPath()))
                .toList();
    }

    /**
     * Captures the tree state of every open project, plus the layout of the whole session. Quitting
     * while the last session is still being reopened leaves it untouched: a half-restored window
     * would otherwise overwrite it with the tabs that did make it back.
     */
    private void captureSession() {
        if (restoringSession) {
            LOG.info("The session is still being restored; keeping the persisted one");
            return;
        }
        sessionRestorer.captureSession(openProjectFiles(), providers);
        persistSession();
    }

    /**
     * Records which projects are open and which one is selected, so the next launch reopens this
     * same session. Called whenever that set changes, not only at exit.
     */
    private void persistSession() {
        OpenProject selected = projectRegistry.getSelected();
        appState.setOpenProjects(
                projectRegistry.getOpenProjects().stream().map(OpenProject::getPath).toList(),
                selected == null ? null : selected.getPath());
    }

    void newProject() {
        LOG.info("New Project action invoked (not yet implemented)");
    }

    void openProject() {
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Open Project");
        File selectedDir = dirChooser.showDialog(stage);
        if (selectedDir != null) {
            openProject(selectedDir);
        } else {
            LOG.warning("No directory selected");
        }
    }


    /**
     * Opens {@code dir} <em>alongside</em> the projects already open: every navigator view gains a
     * root for it and its tree is built in the background, while the other projects keep their trees,
     * their editors and their own loading. Reopening an already open project just selects it.
     */
    void openProject(File dir) {
        if (dir == null || !dir.isDirectory()) {
            LOG.warning("Project directory does not exist: " + dir);
            if (dir != null) {
                appState.removeRecentProject(dir.getPath());
                actionBars.refreshRecentProjects();
            }
            return;
        }
        // Normalized so the directory, the FileObject and the persisted state all agree on one path.
        File projectDir = FileUtil.normalizeFile(dir);
        FileObject rootFileObject = FileUtil.toFileObject(projectDir);
        if (rootFileObject == null) {
            LOG.warning("Can't create a rootFoContext for " + projectDir);
            return;
        }
        String path = OpenProject.pathOf(rootFileObject);
        OpenProject alreadyOpen = projectRegistry.find(path);
        if (alreadyOpen != null) {
            LOG.info("Project already open, selecting it: " + path);
            projectRegistry.select(alreadyOpen);
            return;
        }
        if (loads.isLoading(path)) {
            LOG.info("Project already loading: " + path);
            return;
        }
        // Nesting is allowed (NetBeans does the same), but ownership is then resolved to the nearest
        // root, so both projects showing the same files is worth a warning.
        OpenProject enclosing = projectRegistry.ownerOf(rootFileObject);
        if (enclosing != null) {
            LOG.warning("Opening " + path + " inside the already open project " + enclosing.getPath());
        }
        if (providers == null || providers.isEmpty()) {
            LOG.warning("No NavigatorProviders found");
            return;
        }
        Lookup rootFoContext = Lookups.singleton(rootFileObject);
        loads.begin(path, projectDir);
        updateLoadingProgress();
        // Read with the normalized path, the same key the state was captured under.
        List<String> expandedPaths = appState.getExpandedNodes(projectDir.getPath());
        for (NavigatorProvider provider : providers) {
            LOG.info("Adding project " + path + " to " + provider);
            provider.restoreExpandedPaths(rootFileObject, expandedPaths);
            provider.restoreSelectedPath(rootFileObject, appState.getSelectedNode(projectDir.getPath()));
            provider.addProject(rootFoContext);
        }
    }

    /**
     * Shows the status bar's progress for the projects being loaded, or hides it once none is
     * loading. Projects load in parallel, so a single one is reported by name and its cancel button
     * cancels that load; when several are loading at once, naming any one of them would be
     * arbitrary, so they are reported together and cancelling cancels all of them.
     */
    private void updateLoadingProgress() {
        File current = loads.current();
        if (current == null) {
            statusBar.hideProgress();
            return;
        }
        int loading = loads.size();
        if (loading > 1) {
            statusBar.showProgress(NbBundle.getMessage(JavaFXLaunchApp.class,
                    "StatusBar.openingProjects", loading), this::cancelAllProjectLoading);
            return;
        }
        String path = loads.currentPath();
        statusBar.showProgress(NbBundle.getMessage(JavaFXLaunchApp.class,
                "StatusBar.openingProject", current.getName()), () -> cancelProjectLoading(path));
    }

    /**
     * Cancels the loading of the project at {@code path}, if it is still loading, and drops the
     * partially built tree it left in the navigator views. Every other project is untouched.
     */
    private void cancelProjectLoading(String path) {
        File dir = loads.dirOf(path);
        if (dir == null) {
            return;
        }
        FileObject root = FileUtil.toFileObject(dir);
        if (providers != null && root != null) {
            for (NavigatorProvider provider : providers) {
                provider.cancelLoading(root);
                provider.removeProject(root);
            }
        }
        loads.end(path);
        updateLoadingProgress();
        // A cancelled load never reports back, so the session must stop waiting for it: the projects
        // that did load still get their editors and their arrangement back.
        sessionProjectSettled(path);
    }

    /** Cancels every project still loading. */
    private void cancelAllProjectLoading() {
        for (String path : loads.paths()) {
            cancelProjectLoading(path);
        }
    }

    /**
     * Invoked on the FX thread once a project's tree has loaded. Registers it as the (now selected)
     * open project, records it as the most recent project together with its resolved icon name, and
     * reopens the editors it was left with.
     */
    private void onProjectLoaded(FileObject rootFileObject) {
        String path = OpenProject.pathOf(rootFileObject);
        loads.end(path);
        updateLoadingProgress();
        if (projectRegistry.find(path) != null) {
            sessionProjectSettled(path);
            return;
        }
        File dir = new File(path);
        // Registered before its editors are restored, so each of them resolves its owning project.
        OpenProject project = projectRegistry.open(rootFileObject);
        rootWatcher.watch(project);
        String iconName = null;
        if (providers != null) {
            for (NavigatorProvider provider : providers) {
                iconName = provider.getProjectIconName(rootFileObject);
                if (iconName != null) {
                    break;
                }
            }
        }
        appState.addRecentProject(dir.getPath(), iconName);
        actionBars.refreshRecentProjects();
        persistSession();
        // The persisted layout describes the whole window across every project of the session, so it
        // is applied once - when the last of them has loaded. A project opened later comes up empty:
        // the tabs it had are only restored as part of the session they were captured in.
        sessionProjectSettled(path);
    }

    /**
     * Invoked on the FX thread when a project's tree could not be built: the directory is not a
     * project, or reading it failed. Stops waiting for it and drops the partial root the views may
     * already show, so the user can fix the project and open it again.
     */
    private void onProjectLoadFailed(FileObject rootFileObject) {
        String path = OpenProject.pathOf(rootFileObject);
        if (!loads.isLoading(path)) {
            return;
        }
        LOG.warning("Project could not be loaded: " + path);
        cancelProjectLoading(path);
        // A project of the session that never loads must not hold the layout restore back.
        sessionProjectSettled(path);
    }

    /**
     * Closes the selected project without closing the application: confirms its unsaved changes,
     * closes its editor tabs, removes it from the navigator views and drops its persisted state. The
     * other open projects keep their trees, tabs and state.
     * <p>
     * Closing a project resets all persisted state for it, so it is not reopened on the next launch and,
     * if picked from Recent Projects, opens fresh at the project root.
     */
    private void closeProject() {
        closeProject(projectRegistry.getSelected());
    }

    private void closeProject(OpenProject project) {
        closeProject(project, true);
    }

    /**
     * Closes {@code project}, asking about its unsaved changes only when {@code confirmUnsaved} is
     * set: a project whose folder has just disappeared from disk cannot be saved anywhere, so it is
     * closed without a question that has no good answer.
     */
    private void closeProject(OpenProject project, boolean confirmUnsaved) {
        if (project == null) {
            return;
        }
        String path = project.getPath();
        if (confirmUnsaved) {
            EditorContext context = Lookup.getDefault().lookup(EditorContext.class);
            List<EditorDocument> documents = context != null ? context.documentsOf(path) : List.of();
            if (!documents.isEmpty() && !CloseConfirmation.confirmClose(documents)) {
                return;
            }
        }
        rootWatcher.unwatch(path);
        // Closing a project of the session being restored means the user has taken over: the
        // previous arrangement is no longer what they want back.
        abortSessionRestore();
        // A project still loading stops loading now: nothing should keep building for a closed project.
        cancelProjectLoading(path);
        ContentManager contentManager = Lookup.getDefault().lookup(ContentManager.class);
        if (contentManager != null) {
            contentManager.closeFiles(path);
        }
        if (providers != null) {
            providers.forEach(provider -> provider.removeProjectAt(path));
        }
        projectRegistry.close(project);
        // Only this project's file-operation history goes: the other projects keep theirs.
        FileUndoManager.getDefault().clear(path);
        appState.clearProjectState(new File(path));
        persistSession();
    }

    /**
     * Closes every open project without closing the application. Same contract as
     * {@link #closeProject()}, applied to all of them: a single unsaved-changes confirmation, then
     * each project's navigator root and persisted state are dropped.
     */
    private void closeAllProjects() {
        List<OpenProject> projects = List.copyOf(projectRegistry.getOpenProjects());
        if (projects.isEmpty()) {
            return;
        }
        EditorContext context = Lookup.getDefault().lookup(EditorContext.class);
        List<EditorDocument> documents = context != null
                ? List.copyOf(context.getDocuments()) : List.of();
        if (!documents.isEmpty() && !CloseConfirmation.confirmClose(documents)) {
            return;
        }
        abortSessionRestore();
        rootWatcher.unwatchAll();
        cancelAllProjectLoading();
        ContentManager contentManager = Lookup.getDefault().lookup(ContentManager.class);
        if (contentManager != null) {
            contentManager.closeAll();
        }
        // File undo history refers to paths inside the closed projects; drop it.
        FileUndoManager.getDefault().clear();
        if (providers != null) {
            providers.forEach(NavigatorProvider::removeAllProjects);
        }
        projectRegistry.closeAll();
        for (OpenProject project : projects) {
            appState.clearProjectState(new File(project.getPath()));
        }
        persistSession();
    }

    /**
     * Invoked off the FX thread when an open project's root folder is deleted or renamed on disk:
     * the project is closed as if the user had closed it, dropping its tree, its tabs and its
     * persisted state - it can no longer be reopened at that path.
     */
    private void projectRootGone(OpenProject project) {
        Platform.runLater(() -> {
            if (projectRegistry.find(project.getPath()) == null) {
                return;
            }
            closeProject(project, false);
            appState.removeRecentProject(project.getPath());
            actionBars.refreshRecentProjects();
        });
    }

    private void clearRecentProjects() {
        appState.clearRecentProjects();
        actionBars.refreshRecentProjects();
    }

    /**
     * Reveals {@code project}'s root in the navigator and moves the focus there, so switching
     * project from the Window menu or its shortcut lands the user in that project's tree.
     * <p>
     * The reveal happens in the navigator view the user is currently looking at (Projects, Files,
     * or whichever is selected), falling back to the first one when the navigator pane holds none.
     */
    private void revealProject(OpenProject project) {
        NavigatorProvider provider = project == null ? null : selectedNavigatorProvider();
        if (provider == null) {
            return;
        }
        NbfxTabPane.setNavigatorRevealInProgress(true);
        selectNavigator(navigatorId(provider), false);
        provider.revealFile(project.getRoot());
        Platform.runLater(() -> focusNavigatorView(provider.getView()));
    }

    /** The provider of the navigator tab currently selected in the navigator pane, or the first one. */
    private NavigatorProvider selectedNavigatorProvider() {
        List<NavigatorProvider> list = new ArrayList<>(providers);
        if (list.isEmpty()) {
            return null;
        }
        Tab selected = navigatorPane == null ? null : navigatorPane.getSelectionModel().getSelectedItem();
        String id = selected == null ? null : NbfxTabPane.navigatorId(selected);
        if (id != null) {
            for (NavigatorProvider provider : list) {
                if (id.equals(navigatorId(provider))) {
                    return provider;
                }
            }
        }
        return list.get(0);
    }

    /** The icon of an open project, resolved from its root through the navigator providers. */
    private Node projectIcon(OpenProject project) {
        if (project == null || providers == null) {
            return null;
        }
        FileObject root = project.getRoot();
        for (NavigatorProvider provider : providers) {
            String iconName = provider.getProjectIconName(root);
            Node icon = iconName == null ? null : provider.getProjectIcon(root, iconName);
            if (icon != null) {
                return icon;
            }
        }
        return null;
    }

    private Node projectIcon(File dir, String iconName) {
        if (dir == null || iconName == null || providers == null) {
            return null;
        }
        FileObject fo = FileUtil.toFileObject(dir);
        if (fo == null) {
            return null;
        }
        for (NavigatorProvider provider : providers) {
            Node icon = provider.getProjectIcon(fo, iconName);
            if (icon != null) {
                return icon;
            }
        }
        return null;
    }

    /**
     * Reopens every project of the last session, in the order they were opened. Each one loads on its
     * own; the window layout - which spans all of them - is applied by
     * {@link #sessionProjectSettled(String)} once the last has finished. Projects whose directory is
     * gone are pruned.
     */
    private void restoreSessionProjects() {
        List<String> projects = appState.getOpenProjects();
        if (projects.isEmpty()) {
            return;
        }
        sessionSelected = appState.getSelectedProject();
        List<File> dirs = new ArrayList<>();
        for (String path : projects) {
            File dir = FileUtil.normalizeFile(new File(path));
            if (dir.isDirectory()) {
                dirs.add(dir);
                pendingSessionProjects.add(dir.getPath());
            } else {
                LOG.info("Project of the last session no longer exists, pruning: " + path);
                appState.removeRecentProject(path);
            }
        }
        if (dirs.isEmpty()) {
            actionBars.refreshRecentProjects();
            persistSession();
            return;
        }
        restoringSession = true;
        LOG.info("Reopening the last session: " + pendingSessionProjects);
        for (File dir : dirs) {
            openProject(dir);
            // A project whose load never started (a directory that is no longer a project, or no
            // navigator at all) would otherwise keep the session waiting for a load that will never
            // report back.
            if (!loads.isLoading(dir.getPath())) {
                sessionProjectSettled(dir.getPath());
            }
        }
    }

    /**
     * Gives up on restoring the last session, keeping whatever is already on screen. Used when the
     * user closes projects while it is still coming back.
     */
    private void abortSessionRestore() {
        if (restoringSession) {
            LOG.info("Session restore abandoned: a project was closed while it was still loading");
        }
        restoringSession = false;
        pendingSessionProjects.clear();
        sessionSelected = null;
    }

    /**
     * Notes that a project of the restored session has finished loading (or failed to). Once they all
     * have, the session's layout is applied and the project that was selected at exit is reselected.
     */
    private void sessionProjectSettled(String path) {
        if (!restoringSession || !pendingSessionProjects.remove(path) || !pendingSessionProjects.isEmpty()) {
            return;
        }
        restoringSession = false;
        String selected = sessionSelected;
        sessionSelected = null;
        sessionRestorer.restoreSession(focused -> {
            // The tab the session comes back focused on decides the project, exactly as moving to a
            // tab does while the app runs. The project that was selected at exit only applies when
            // the focus does not land on an editor of an open project (an empty or navigator pane).
            OpenProject owner = focused == null ? null
                    : projectRegistry.ownerOf(focused.getFileObject());
            if (owner == null && selected != null) {
                owner = projectRegistry.find(selected);
            }
            if (owner != null) {
                projectRegistry.select(owner);
            }
            persistSession();
        });
    }

}
