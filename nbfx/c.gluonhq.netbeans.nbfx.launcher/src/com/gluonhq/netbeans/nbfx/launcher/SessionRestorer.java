package com.gluonhq.netbeans.nbfx.launcher;

import com.gluonhq.netbeans.nbfx.api.ContentManager;
import com.gluonhq.netbeans.nbfx.api.EditorDocument;
import com.gluonhq.netbeans.nbfx.api.NavigatorProvider;
import com.gluonhq.netbeans.nbfx.launcher.AppState.Layout;
import com.gluonhq.netbeans.nbfx.launcher.AppState.PaneLayout;
import com.gluonhq.netbeans.nbfx.launcher.AppState.TabEntry;
import com.gluonhq.netbeans.nbfx.launcher.AppState.TabKind;
import com.gluonhq.netbeans.nbfx.launcher.NbfxTabPane.PaneRole;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;

/**
 * Session orchestration on top of {@link AppState}: captures the live window layout and tree state
 * into the persistence layer and restores them back onto the scene graph. Unlike {@link AppState},
 * this reaches into the global {@link Lookup}, {@link ContentManager}, {@link EditorDocument},
 * {@link NbfxTabPane} and {@link Platform#runLater}, and is therefore FX-thread-bound and
 * scene-graph-coupled.
 *
 * <p>Layout is persisted <em>per pane</em> rather than per tab kind, because the user may drag any
 * tab into any pane: the main pane can hold navigator tabs, the navigator pane can hold editors, and
 * a detached window can hold a mix of both. Each pane is identified by its
 * {@link PaneRole} (detached panes additionally by their position in the list).</p>
 */
final class SessionRestorer {

    private static final Logger LOG = Logger.getLogger(SessionRestorer.class.getName());

    private final AppState appState;

    /**
     * Resolves a navigator provider id to its tab, reusing the existing one wherever it currently
     * lives and creating it only if it has none. Supplied by the launcher, which owns the providers.
     */
    private final Function<String, Tab> navigatorTabs;

    SessionRestorer(AppState appState, Function<String, Tab> navigatorTabs) {
        this.appState = appState;
        this.navigatorTabs = navigatorTabs;
    }

    // --- Capture ------------------------------------------------------------

    /**
     * Captures the given projects' expanded tree nodes and the current window layout so the whole
     * session can be restored at the next launch. Every open project's tree state is captured under
     * its own key, while the layout - which spans every project, since one window holds the tabs of
     * all of them - is captured once. Must be called on the FX thread while the windows are still
     * showing.
     */
    void captureSession(Collection<File> projects,
            Collection<? extends NavigatorProvider> providers) {
        if (projects != null) {
            for (File project : projects) {
                captureExpansion(project, providers);
            }
        }
        captureLayout();
    }

    private void captureExpansion(File project, Collection<? extends NavigatorProvider> providers) {
        if (project == null || providers == null) {
            return;
        }
        FileObject root = FileUtil.toFileObject(FileUtil.normalizeFile(project));
        if (root == null) {
            return;
        }
        List<String> expanded = new ArrayList<>();
        String selected = null;
        for (NavigatorProvider provider : providers) {
            // Only this project's own nodes: with several projects open the providers hold one tree
            // per root, and each project's state is persisted under its own key.
            expanded.addAll(provider.getExpandedPaths(root));
            if (selected == null) {
                selected = provider.getSelectedPath(root);
            }
        }
        appState.setExpandedNodes(project.getPath(), expanded);
        appState.setSelectedNode(project.getPath(), selected);
    }

    /** Persists the current arrangement of every pane and the tabs it holds. */
    private void captureLayout() {
        List<PaneLayout> panes = new ArrayList<>();
        TabPane focusedPane = NbfxTabPane.focusedPane();
        int focused = -1;
        for (TabPane pane : NbfxTabPane.panesByRole()) {
            PaneLayout captured = capturePane(pane);
            if (captured == null) {
                continue;
            }
            if (pane == focusedPane) {
                focused = panes.size();
            }
            panes.add(captured);
        }
        appState.setSessionLayout(new Layout(panes, focused));
        int captured = focused;
        LOG.info(() -> "Captured layout: " + panes.size() + " panes, focused index " + captured
                + " (" + (captured >= 0 ? panes.get(captured).role() : "none") + ")");
        LOG.fine(NbfxTabPane::describeFocus);
    }

    /**
     * Captures one pane, or {@code null} if it should not be persisted: a detached pane that is not
     * (or no longer) in a window, or one left with no persistable tab.
     */
    private static PaneLayout capturePane(TabPane pane) {
        PaneRole role = NbfxTabPane.roleOf(pane);
        Stage stage = role == PaneRole.DETACHED ? NbfxTabPane.stageOf(pane) : null;
        if (role == PaneRole.DETACHED && stage == null) {
            return null;
        }
        List<TabEntry> tabs = new ArrayList<>();
        Tab selected = pane.getSelectionModel().getSelectedItem();
        int activeIndex = -1;
        for (Tab tab : pane.getTabs()) {
            TabEntry entry = entryFor(tab);
            if (entry == null) {
                continue;
            }
            if (tab == selected) {
                activeIndex = tabs.size();
            }
            tabs.add(entry);
        }
        if (role == PaneRole.DETACHED) {
            if (tabs.isEmpty()) {
                return null;
            }
            return new PaneLayout(role, stage.getX(), stage.getY(),
                    stage.getWidth(), stage.getHeight(), tabs, activeIndex);
        }
        return PaneLayout.docked(role, tabs, activeIndex);
    }

    /** The persistable descriptor of {@code tab}, or {@code null} if it carries no stable id. */
    private static TabEntry entryFor(Tab tab) {
        EditorDocument document = NbfxTabPane.documentOf(tab);
        if (document != null) {
            String path = pathOf(document.getFileObject());
            return path == null ? null : new TabEntry(TabKind.EDITOR, path,
                    document.getTopParagraph(), document.getCaretParagraph(),
                    document.getCaretColumn());
        }
        String navigatorId = NbfxTabPane.navigatorId(tab);
        return navigatorId == null ? null : TabEntry.navigator(navigatorId);
    }

    // --- Restore ------------------------------------------------------------

    /**
     * The persisted session layout stripped of its editor tabs, holding navigator tabs only. Used at
     * start-up to arrange the navigator pane before the session's projects have loaded; the editors
     * come back with {@link #restoreSession(Consumer)} once they have.
     */
    Layout startupLayout() {
        return appState.getSessionLayout().withoutEditors();
    }

    /**
     * Restores the persisted session: reopens the editors of every project, moves the navigator tabs
     * into the panes they were left in and recreates the detached windows at their bounds. Files that
     * no longer exist - typically those of a project that is no longer open - are skipped. Safe to
     * call on the FX thread once every project of the session has finished loading.
     *
     * @param done run on the FX thread once the layout has been applied, with the document of the
     *             tab that ended up focused ({@code null} when that is not an editor); may be
     *             {@code null} itself
     */
    void restoreSession(Consumer<EditorDocument> done) {
        Layout layout = appState.getSessionLayout();
        if (layout.isEmpty()) {
            runSafely(done, null);
            return;
        }
        ContentManager cm = Lookup.getDefault().lookup(ContentManager.class);
        if (cm == null) {
            LOG.warning("No ContentManager found; cannot restore the window layout");
            runSafely(done, null);
            return;
        }
        List<TabEntry> editors = openEditors(cm, layout);
        Platform.runLater(() -> {
            TabPane focusPane = applyLayout(layout);
            // Every detached window is shown after the primary one, so it ends up in front whether
            // or not it should be. Fall back to the main pane, which also covers layouts persisted
            // before the focused pane was recorded.
            if (focusPane == null) {
                focusPane = NbfxTabPane.paneWithRole(PaneRole.MAIN);
            }
            restoreViews(cm, editors);
            focusActivePane(focusPane);
            runSafely(done, focusedDocument(focusPane));
        });
    }

    /** The document of the tab selected in {@code pane}, or {@code null} if it holds no editor. */
    private static EditorDocument focusedDocument(TabPane pane) {
        return pane == null ? null : NbfxTabPane.documentOf(pane.getSelectionModel().getSelectedItem());
    }

    private static void runSafely(Consumer<EditorDocument> done, EditorDocument focused) {
        if (done != null) {
            done.accept(focused);
        }
    }

    /**
     * Opens every editor the layout holds. Each one attaches its tab in its own
     * {@link Platform#runLater}, so a block queued after this call sees them all and can distribute
     * them. Paths are de-duplicated because {@code openFile} only finds an existing tab once it has
     * been attached, so opening the same file twice in one block would create two tabs for it.
     *
     * @return the entries that were opened, in layout order
     */
    private static List<TabEntry> openEditors(ContentManager cm, Layout layout) {
        List<TabEntry> editors = new ArrayList<>();
        Set<String> opened = new LinkedHashSet<>();
        for (PaneLayout pane : layout.panes()) {
            for (TabEntry tab : pane.tabs()) {
                if (tab.kind() != TabKind.EDITOR || !opened.add(tab.id())) {
                    continue;
                }
                FileObject fo = toFileObject(tab.id());
                if (fo == null) {
                    LOG.warning("Skipping restore of missing file: " + tab.id());
                    continue;
                }
                cm.openFile(fo, null);
                editors.add(tab);
            }
        }
        return editors;
    }

    /**
     * Moves the existing tabs into the panes described by {@code layout}. Docked panes are rebuilt in
     * place; each detached pane is recreated as a new window. Navigator tabs the layout does not
     * mention were closed by the user and are removed.
     *
     * @return the pane that should receive focus, or {@code null} when the layout does not say
     */
    private TabPane applyLayout(Layout layout) {
        Set<Tab> placed = new LinkedHashSet<>();
        PaneLayout focusedPane = layout.focusedPane();
        TabPane focusTarget = null;
        for (PaneLayout pane : layout.panes()) {
            List<Tab> tabs = resolveTabs(pane);
            placed.addAll(tabs);
            if (pane.role() == PaneRole.DETACHED) {
                if (tabs.isEmpty()) {
                    continue;
                }
                double[] bounds = AppState.clampBounds(pane.x(), pane.y(), pane.width(), pane.height());
                TabPane detached = NbfxTabPane.openDetachedWindow(tabs,
                        activeTab(tabs, pane.activeIndex()),
                        bounds[0], bounds[1], bounds[2], bounds[3]);
                if (pane == focusedPane) {
                    focusTarget = detached;
                }
            } else {
                TabPane target = NbfxTabPane.paneWithRole(pane.role());
                if (target == null) {
                    LOG.warning("No " + pane.role() + " pane to restore into");
                    continue;
                }
                // Only tabs that are not already in place are moved, so re-applying an unchanged
                // layout leaves the panes (and the editors' skins) untouched. Tabs the layout does
                // not mention stay, pushed after the ones it does.
                for (int i = 0; i < tabs.size(); i++) {
                    if (target.getTabs().indexOf(tabs.get(i)) != i) {
                        NbfxTabPane.moveTab(target, tabs.get(i), i);
                    }
                }
                Tab active = activeTab(tabs, pane.activeIndex());
                if (active != null) {
                    target.getSelectionModel().select(active);
                }
                if (pane == focusedPane) {
                    focusTarget = target;
                }
            }
        }
        closeUnplacedNavigatorTabs(placed);
        return focusTarget;
    }

    /** The tabs of {@code pane}, in order, skipping entries whose tab could not be resolved. */
    private List<Tab> resolveTabs(PaneLayout pane) {
        List<Tab> tabs = new ArrayList<>();
        for (TabEntry entry : pane.tabs()) {
            Tab tab = entry.kind() == TabKind.NAVIGATOR
                    ? navigatorTabs.apply(entry.id())
                    : editorTab(entry.id());
            if (tab != null && !tabs.contains(tab)) {
                tabs.add(tab);
            }
        }
        return tabs;
    }

    private static Tab activeTab(List<Tab> tabs, int activeIndex) {
        return activeIndex >= 0 && activeIndex < tabs.size() ? tabs.get(activeIndex) : null;
    }

    private static Tab editorTab(String path) {
        FileObject fo = toFileObject(path);
        return fo == null ? null : NbfxTabPane.findTab(fo).orElse(null);
    }

    /**
     * Removes every navigator tab the restored layout did not claim. Such a tab was closed by the
     * user before the layout was captured; without this it would linger from the previous project's
     * arrangement (or from the default one built at start-up).
     */
    private static void closeUnplacedNavigatorTabs(Set<Tab> placed) {
        for (TabPane pane : NbfxTabPane.tabPanes()) {
            pane.getTabs().removeIf(tab -> NbfxTabPane.navigatorId(tab) != null && !placed.contains(tab));
        }
    }

    /**
     * Restores the scroll and caret position of each reopened editor.
     */
    private static void restoreViews(ContentManager cm, List<TabEntry> editors) {
        for (TabEntry entry : editors) {
            EditorDocument document = cm.documentForFile(toFileObject(entry.id()));
            if (document != null) {
                document.restoreView(entry.topParagraph(), entry.caretParagraph(), entry.caretColumn());
            }
        }
    }

    /**
     * Brings {@code pane}'s window to the front and gives focus to the tab selected in it - the pane
     * that was active when the layout was captured, which is not necessarily the main one since
     * editors can be dragged into any pane.
     * <p>
     * Every pane already focuses its own selected tab when its window becomes active, so focusing
     * ours right away would just be undone by those handlers. Waiting for the window to actually
     * become active and listening after them (listeners run in registration order) makes ours the
     * one that wins.
     */
    private static void focusActivePane(TabPane pane) {
        if (pane == null) {
            return;
        }
        Stage stage = NbfxTabPane.stageOf(pane);
        if (stage == null || stage.isFocused()) {
            // Already active: no focus change is coming, so nothing will compete with this.
            NbfxTabPane.focusDocument(pane.getSelectionModel().getSelectedItem());
            return;
        }
        stage.focusedProperty().addListener(new ChangeListener<Boolean>() {
            @Override
            public void changed(ObservableValue<? extends Boolean> obs, Boolean was, Boolean active) {
                if (Boolean.TRUE.equals(active)) {
                    stage.focusedProperty().removeListener(this);
                    NbfxTabPane.focusDocument(pane.getSelectionModel().getSelectedItem());
                }
            }
        });
        stage.toFront();
        stage.requestFocus();
    }

    private static String pathOf(FileObject fo) {
        File f = FileUtil.toFile(fo);
        return f != null ? f.getAbsolutePath() : null;
    }

    private static FileObject toFileObject(String path) {
        File f = new File(path);
        return f.isFile() ? FileUtil.toFileObject(f) : null;
    }

}
