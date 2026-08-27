package com.gluonhq.netbeans.nbfx.launcher;

import com.gluonhq.netbeans.nbfx.api.EditorSettings;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.SplitPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.openide.util.NbPreferences;

/**
 * Single entry point for persisting and restoring application UI state via
 * {@link NbPreferences} (stored in the platform userdir). All preference access
 * routes through this helper; restore is best-effort and never blocks the app.
 *
 * <p>The current window bounds and split-divider position are kept in a
 * {@code volatile} snapshot updated by FX-thread listeners, so {@link #save()}
 * can run from any thread (e.g. a JVM shutdown hook) without touching live
 * scene-graph nodes. {@code save()} is idempotent.</p>
 */
final class AppState {

    private static final Logger LOG = Logger.getLogger(AppState.class.getName());

    private static final String WINDOW_X = "window.x";
    private static final String WINDOW_Y = "window.y";
    private static final String WINDOW_WIDTH = "window.width";
    private static final String WINDOW_HEIGHT = "window.height";
    private static final String WINDOW_MAXIMIZED = "window.maximized";
    private static final String SPLIT_DIVIDER = "split.divider.0";
    private static final String TOOLBARS = "toolbars.arrangement";
    private static final String TOOLBARS_HIDDEN = "toolbars.hidden";
    private static final String VIEW_SHOW_LINE_NUMBERS = "view.showLineNumbers";
    private static final String RECENT_PROJECTS = "project.recent";
    /** The projects that were open at exit, one path per line. */
    private static final String PROJECT_OPEN = "project.open";
    /** The path of the project that was selected at exit. */
    private static final String PROJECT_SELECTED = "project.selected";
    private static final String TREE_EXPANDED_NODE = "tree.expanded";
    private static final String TREE_SELECTED_NODE = "tree.selected";
    private static final String LAYOUT_NODE = "layout";

    /**
     * Key under which the whole session's layout is stored inside {@link #LAYOUT_NODE}. The layout
     * spans every open project, since one window holds the tabs of all of them. Legacy per-project
     * keys are hexadecimal hashes, so they can never collide with this name.
     */
    private static final String LAYOUT_SESSION_KEY = "session";

    /** Legacy key of the project-independent layout, read once by {@link #migrateLegacySession()}. */
    private static final String LAYOUT_LEGACY_GLOBAL_KEY = "global";

    /** Field separator between a pane's attributes and its tabs within a serialized layout line. */
    private static final String FIELD_SEP = "\t";
    /** Field separator between the attributes of a single tab within a serialized pane field. */
    private static final String TAB_SEP = "\r";

    private static final String[] EMPTY_LINES = new String[0];

    /** Default main-window size used when nothing is persisted. */
    static final double DEFAULT_WIDTH = 1200;
    static final double DEFAULT_HEIGHT = 800;
    /** Default split-divider position (left navigator vs. editor) used on first run and by Reset Windows. */
    static final double DEFAULT_DIVIDER = 0.22;

    /** Default detached-window size used when a persisted one is off-screen or invalid. */
    private static final double DEFAULT_DETACHED_WIDTH = 900;
    private static final double DEFAULT_DETACHED_HEIGHT = 650;

    /** Maximum number of recent projects retained. */
    static final int MAX_RECENT_PROJECTS = 10;

    private final Preferences prefs;

    // Snapshot of the current UI state, kept up to date on the FX thread.
    private volatile double winX = Double.NaN, winY = Double.NaN;
    private volatile double winW = DEFAULT_WIDTH, winH = DEFAULT_HEIGHT;
    private volatile boolean winMax = false;
    private volatile double dividerPos = Double.NaN;
    private volatile String toolbarArrangement = null;
    private volatile String toolbarHidden = null;
    private volatile boolean showLineNumbers = true;
    private final AtomicBoolean saved = new AtomicBoolean();

    AppState() {
        this(NbPreferences.forModule(AppState.class));
    }

    AppState(Preferences prefs) {
        this.prefs = prefs;
        migrateLegacySession();
    }

    // --- Lifecycle wiring ---------------------------------------------------

    /**
     * Restores the persisted window bounds onto the stage (before it is shown)
     * and starts tracking further changes into the snapshot.
     */
    void initWindow(Stage stage) {
        restoreWindowBounds(stage);
        stage.xProperty().subscribe(nv -> winX = nv.doubleValue());
        stage.yProperty().subscribe(nv -> winY = nv.doubleValue());
        stage.widthProperty().subscribe(nv -> winW = nv.doubleValue());
        stage.heightProperty().subscribe(nv -> winH = nv.doubleValue());
        stage.maximizedProperty().subscribe(nv -> winMax = nv);
    }

    /**
     * Restores the persisted divider position and starts tracking it. Must be
     * called after the stage is shown, since the divider is only meaningful
     * once the {@link SplitPane} has been laid out.
     */
    void initDivider(SplitPane splitPane) {
        restoreDivider(splitPane);
        if (!splitPane.getDividers().isEmpty()) {
            splitPane.getDividers().getFirst().positionProperty()
                    .subscribe(nv -> dividerPos = nv.doubleValue());
        }
    }

    /**
     * Restores the persisted tool-bar arrangement onto the container and starts
     * tracking further user reorderings into the snapshot.
     */
    void initToolbars(ToolBarContainer toolBars) {
        String stored = get(TOOLBARS, null);
        if (stored != null) {
            toolBars.applyArrangement(stored);
        }
        String hidden = get(TOOLBARS_HIDDEN, null);
        if (hidden != null) {
            toolBars.applyHiddenToolBarIds(hidden);
        }
        toolbarArrangement = toolBars.getArrangement();
        toolbarHidden = toolBars.getHiddenToolBarIds();
        toolBars.setOnArrangementChanged(() -> toolbarArrangement = toolBars.getArrangement());
        toolBars.setOnVisibilityChanged(() -> toolbarHidden = toolBars.getHiddenToolBarIds());
    }

    /**
     * Restores the persisted View settings into the shared {@link EditorSettings} (before editors
     * are opened, so they pick up the stored value) and starts tracking further changes into the
     * snapshot. A {@code null} settings instance (none registered) is ignored.
     */
    void initViewSettings(EditorSettings settings) {
        if (settings == null) {
            return;
        }
        settings.showLineNumbers().set(getBoolean(VIEW_SHOW_LINE_NUMBERS, true));
        showLineNumbers = settings.showLineNumbers().get();
        settings.showLineNumbers().subscribe(nv -> showLineNumbers = nv);
    }

    /**
     * Registers a JVM shutdown hook that persists the snapshot as a safety net
     * for exit paths that bypass the regular lifecycle.
     */
    void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::save, "nbfx-state-save"));
    }

    /** Persists the current snapshot. Idempotent and safe to call off the FX thread. */
    void save() {
        if (!saved.compareAndSet(false, true)) {
            return;
        }
        try {
            saveWindowBounds(winX, winY, winW, winH, winMax);
            if (!Double.isNaN(dividerPos)) {
                saveDivider(dividerPos);
            }
            if (toolbarArrangement != null) {
                prefs.put(TOOLBARS, toolbarArrangement);
            }
            if (toolbarHidden != null) {
                prefs.put(TOOLBARS_HIDDEN, toolbarHidden);
            }
            prefs.putBoolean(VIEW_SHOW_LINE_NUMBERS, showLineNumbers);
            flush();
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "Failed to persist application state", ex);
        }
    }

    // --- Main window bounds -------------------------------------------------

    /**
     * Applies the persisted window bounds (position, size, maximized) to the
     * given stage, falling back to the default size when nothing is stored
     * or the stored bounds are not visible on any current screen.
     */
    private void restoreWindowBounds(Stage stage) {
        double width = getDouble(WINDOW_WIDTH, DEFAULT_WIDTH);
        double height = getDouble(WINDOW_HEIGHT, DEFAULT_HEIGHT);
        double x = getDouble(WINDOW_X, Double.NaN);
        double y = getDouble(WINDOW_Y, Double.NaN);

        if (width <= 0 || height <= 0) {
            width = DEFAULT_WIDTH;
            height = DEFAULT_HEIGHT;
        }
        stage.setWidth(width);
        stage.setHeight(height);

        if (!Double.isNaN(x) && !Double.isNaN(y) && isVisibleOnAnyScreen(x, y, width, height)) {
            stage.setX(x);
            stage.setY(y);
        } else if (!Double.isNaN(x) || !Double.isNaN(y)) {
            LOG.log(Level.INFO, "Stored window position is off-screen; centering instead");
        }

        stage.setMaximized(prefs.getBoolean(WINDOW_MAXIMIZED, false));
    }

    /**
     * Persists the given window bounds. When maximized, only the flag is stored
     * so a later un-maximize keeps the previous restore-size.
     */
    private void saveWindowBounds(double x, double y, double width, double height, boolean maximized) {
        prefs.putBoolean(WINDOW_MAXIMIZED, maximized);
        if (!maximized) {
            prefs.putDouble(WINDOW_X, x);
            prefs.putDouble(WINDOW_Y, y);
            prefs.putDouble(WINDOW_WIDTH, width);
            prefs.putDouble(WINDOW_HEIGHT, height);
        }
        flush();
    }

    // --- Split divider ------------------------------------------------------

    private void restoreDivider(SplitPane splitPane) {
        if (splitPane.getDividers().isEmpty()) {
            return;
        }
        double pos = getDouble(SPLIT_DIVIDER, DEFAULT_DIVIDER);
        if (pos < 0.0 || pos > 1.0) {
            pos = DEFAULT_DIVIDER;
        }
        splitPane.setDividerPosition(0, pos);
    }

    private void saveDivider(double position) {
        if (position >= 0.0 && position <= 1.0) {
            prefs.putDouble(SPLIT_DIVIDER, position);
            flush();
        }
    }

    // --- Recent projects ----------------------------------------------------

    /** A recent project: its directory path and the resolved menu icon name (may be {@code null}). */
    record RecentProject(String path, String iconName) {}

    /** The recent projects, most-recent first. */
    List<RecentProject> getRecentProjects() {
        String raw = get(RECENT_PROJECTS, "");
        if (raw.isBlank()) {
            return List.of();
        }
        List<RecentProject> list = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) {
                list.add(new RecentProject(line, null));
            } else {
                String iconName = line.substring(tab + 1);
                list.add(new RecentProject(line.substring(0, tab), iconName.isBlank() ? null : iconName));
            }
        }
        return list;
    }

    /** The most recently opened project path, or {@code null} if none. */
    String getLastProject() {
        List<RecentProject> recent = getRecentProjects();
        return recent.isEmpty() ? null : recent.getFirst().path();
    }

    /**
     * The projects that were open when the application last exited, in the order they were opened.
     * Empty when none was open (or on first run).
     */
    List<String> getOpenProjects() {
        String raw = get(PROJECT_OPEN, "");
        if (raw.isBlank()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (!line.isBlank()) {
                paths.add(line);
            }
        }
        return List.copyOf(paths);
    }

    /** The path of the project that was selected when the application last exited, or {@code null}. */
    String getSelectedProject() {
        String selected = get(PROJECT_SELECTED, "");
        return selected.isBlank() ? null : selected;
    }

    /**
     * Records the projects that are currently open and which one is selected, so the next launch
     * reopens exactly this session. Persisted immediately (rather than only at exit) so that a
     * project the user closed is not brought back by a crash.
     */
    void setOpenProjects(List<String> paths, String selected) {
        List<String> kept = paths == null ? List.of()
                : paths.stream().filter(path -> path != null && !path.isBlank()).toList();
        if (kept.isEmpty()) {
            prefs.remove(PROJECT_OPEN);
        } else {
            prefs.put(PROJECT_OPEN, String.join("\n", kept));
        }
        if (selected == null || selected.isBlank() || !kept.contains(selected)) {
            prefs.remove(PROJECT_SELECTED);
        } else {
            prefs.put(PROJECT_SELECTED, selected);
        }
        flush();
    }

    /**
     * Removes the persisted per-project state (tree expansion and selection) of the given project,
     * so a subsequent open starts fresh at the project root. The session layout is shared by every
     * project and is left alone. A {@code null} project is ignored.
     */
    void clearProjectState(File project) {
        if (project == null) {
            return;
        }
        String key = expansionKey(project.getPath());
        prefs.node(TREE_EXPANDED_NODE).remove(key);
        prefs.node(TREE_SELECTED_NODE).remove(key);
        flush();
    }

    /** Records a project as the most recent, de-duplicating and bounding the list. */
    void addRecentProject(String path, String iconName) {
        if (path == null || path.isBlank()) {
            return;
        }
        List<RecentProject> list = new ArrayList<>(getRecentProjects());
        list.removeIf(rp -> rp.path().equals(path));
        list.addFirst(new RecentProject(path, iconName));
        while (list.size() > MAX_RECENT_PROJECTS) {
            list.removeLast();
        }
        prefs.put(RECENT_PROJECTS, serialize(list));
        flush();
    }

    /** Removes a project from the recent list (e.g. when its directory no longer exists). */
    void removeRecentProject(String path) {
        List<RecentProject> list = new ArrayList<>(getRecentProjects());
        if (list.removeIf(rp -> rp.path().equals(path))) {
            prefs.put(RECENT_PROJECTS, serialize(list));
            flush();
        }
    }

    void clearRecentProjects() {
        prefs.remove(RECENT_PROJECTS);
        flush();
    }

    // --- Project tree expansion --------------------------------------------

    /**
     * The persisted expanded-node identifiers for {@code projectPath}, or empty if none.
     * Expansion is stored per project in a dedicated preferences child node, keyed by a stable
     * hash of the project path.
     */
    List<String> getExpandedNodes(String projectPath) {
        String[] lines = projectLines(TREE_EXPANDED_NODE, projectPath, 1);
        List<String> list = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                list.add(lines[i]);
            }
        }
        return list;
    }

    /**
     * Reads the per-project payload stored under {@code childNode}, split into lines. The project
     * path is embedded as the first line and re-checked here, guarding against collisions of the
     * hashed {@link #expansionKey}. Returns an empty array when the entry is absent, too short, or
     * belongs to another project.
     */
    private String[] projectLines(String childNode, String projectPath, int minLines) {
        if (projectPath == null) {
            return EMPTY_LINES;
        }
        String raw = prefs.node(childNode).get(expansionKey(projectPath), "");
        if (raw.isBlank()) {
            return EMPTY_LINES;
        }
        String[] lines = raw.split("\n", -1);
        if (lines.length < minLines || !projectPath.equals(lines[0])) {
            return EMPTY_LINES;
        }
        return lines;
    }

    /** Persists the expanded-node identifiers for {@code projectPath}. */
    void setExpandedNodes(String projectPath, List<String> nodes) {
        if (projectPath == null) {
            return;
        }
        Preferences node = prefs.node(TREE_EXPANDED_NODE);
        String key = expansionKey(projectPath);
        if (nodes == null || nodes.isEmpty()) {
            node.remove(key);
        } else {
            node.put(key, projectPath + "\n" + String.join("\n", nodes));
        }
        flush();
    }

    /** The persisted selected-node identifier for {@code projectPath}, or {@code null} if none. */
    String getSelectedNode(String projectPath) {
        String[] lines = projectLines(TREE_SELECTED_NODE, projectPath, 2);
        if (lines.length < 2 || lines[1].isBlank()) {
            return null;
        }
        return lines[1];
    }

    /** Persists the selected-node identifier for {@code projectPath}. */
    void setSelectedNode(String projectPath, String nodeId) {
        if (projectPath == null) {
            return;
        }
        Preferences node = prefs.node(TREE_SELECTED_NODE);
        String key = expansionKey(projectPath);
        if (nodeId == null || nodeId.isBlank()) {
            node.remove(key);
        } else {
            node.put(key, projectPath + "\n" + nodeId);
        }
        flush();
    }

    // --- helpers ------------------------------------------------------------

    private double getDouble(String key, double def) {
        try {
            return prefs.getDouble(key, def);
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private String get(String key, String def) {
        try {
            return prefs.get(key, def);
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private boolean getBoolean(String key, boolean def) {
        try {
            return prefs.getBoolean(key, def);
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static boolean isVisibleOnAnyScreen(double x, double y, double w, double h) {
        Rectangle2D window = new Rectangle2D(x, y, Math.max(1, w), Math.max(1, h));
        for (Screen screen : Screen.getScreens()) {
            if (screen.getVisualBounds().intersects(window)) {
                return true;
            }
        }
        return false;
    }

    private void flush() {
        try {
            prefs.flush();
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Could not flush preferences", ex);
        }
    }

    private static String serialize(List<RecentProject> list) {
        StringBuilder sb = new StringBuilder();
        for (RecentProject rp : list) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(rp.path());
            if (rp.iconName() != null && !rp.iconName().isBlank()) {
                sb.append('\t').append(rp.iconName());
            }
        }
        return sb.toString();
    }

    private static String expansionKey(String projectPath) {
        return Integer.toHexString(projectPath.hashCode());
    }

    // --- Window layout (panes + their tabs) ---------------------------------

    /** The kind of content a persisted tab holds. */
    enum TabKind { EDITOR, NAVIGATOR }

    /**
     * A persisted tab. For {@link TabKind#EDITOR} the {@code id} is an absolute file path and the
     * scroll/caret fields are meaningful; for {@link TabKind#NAVIGATOR} the {@code id} is the
     * provider identifier and the remaining fields are unused.
     */
    record TabEntry(TabKind kind, String id, int topParagraph, int caretParagraph, int caretColumn) {

        static TabEntry navigator(String providerId) {
            return new TabEntry(TabKind.NAVIGATOR, providerId, 0, 0, 0);
        }
    }

    /**
     * A persisted pane: its {@link com.gluonhq.netbeans.nbfx.launcher.NbfxTabPane.PaneRole role},
     * its bounds (only meaningful, and only valid, for a detached pane), its tabs in order and the
     * index of the selected one ({@code -1} when the pane is empty).
     * <p>
     * Tabs of both kinds may appear in any pane, since the user can drag them freely between panes.
     */
    record PaneLayout(NbfxTabPane.PaneRole role, double x, double y, double width, double height,
                      List<TabEntry> tabs, int activeIndex) {

        static PaneLayout docked(NbfxTabPane.PaneRole role, List<TabEntry> tabs, int activeIndex) {
            return new PaneLayout(role, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    tabs, activeIndex);
        }

        /** This pane with its editor tabs removed, and {@code activeIndex} adjusted accordingly. */
        PaneLayout withoutEditors() {
            List<TabEntry> kept = new ArrayList<>();
            int active = -1;
            for (int i = 0; i < tabs.size(); i++) {
                if (tabs.get(i).kind() == TabKind.NAVIGATOR) {
                    if (i == activeIndex) {
                        active = kept.size();
                    }
                    kept.add(tabs.get(i));
                }
            }
            return new PaneLayout(role, x, y, width, height, kept,
                    active >= 0 ? active : (kept.isEmpty() ? -1 : 0));
        }
    }

    /**
     * The full window layout: every pane, ordered main pane, navigator pane, then detached panes,
     * and the index into {@code panes} of the pane that held focus ({@code -1} when unknown).
     */
    record Layout(List<PaneLayout> panes, int focusedIndex) {

        static final Layout EMPTY = new Layout(List.of(), -1);

        Layout(List<PaneLayout> panes) {
            this(panes, -1);
        }

        boolean isEmpty() {
            return panes.isEmpty();
        }

        /** The pane whose window had focus, or {@code null} when that is unknown. */
        PaneLayout focusedPane() {
            return focusedIndex >= 0 && focusedIndex < panes.size() ? panes.get(focusedIndex) : null;
        }

        /**
         * This layout reduced to what can be applied before any project has loaded: editor tabs
         * (whose files belong to the projects being reopened) are dropped, and detached panes left
         * without any tab are dropped with them.
         */
        Layout withoutEditors() {
            List<PaneLayout> kept = new ArrayList<>();
            int focused = -1;
            for (int i = 0; i < panes.size(); i++) {
                PaneLayout stripped = panes.get(i).withoutEditors();
                if (stripped.tabs().isEmpty()
                        && stripped.role() == NbfxTabPane.PaneRole.DETACHED) {
                    continue;
                }
                // Dropping panes shifts the positions the focus index refers to.
                if (i == focusedIndex) {
                    focused = kept.size();
                }
                kept.add(stripped);
            }
            return new Layout(kept, focused);
        }
    }

    /**
     * The layout of the whole session - every pane, its tabs and the windows they live in, across
     * all the projects that were open - or {@link Layout#EMPTY} when none is stored.
     */
    Layout getSessionLayout() {
        return readLayout(LAYOUT_SESSION_KEY, "");
    }

    /** Persists the layout of the whole session. */
    void setSessionLayout(Layout layout) {
        writeLayout(LAYOUT_SESSION_KEY, "", layout);
    }

    private Layout readLayout(String key, String guard) {
        String raw = prefs.node(LAYOUT_NODE).get(key, "");
        if (raw.isBlank()) {
            return Layout.EMPTY;
        }
        String[] lines = raw.split("\n", -1);
        if (lines.length < 2) {
            return Layout.EMPTY;
        }
        // Line 0 is the guard (empty for the session layout, the project path for a legacy
        // per-project one) followed by the index of the focused pane. Layouts written before the
        // focus index carry the guard alone.
        String[] header = lines[0].split(FIELD_SEP, -1);
        if (!guard.equals(header[0])) {
            return Layout.EMPTY;
        }
        List<PaneLayout> panes = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            PaneLayout pane = parsePane(lines[i]);
            if (pane != null) {
                panes.add(pane);
            }
        }
        if (panes.isEmpty()) {
            return Layout.EMPTY;
        }
        int focused = header.length > 1 ? Math.min(parseInt(header, 1), panes.size() - 1) : -1;
        return new Layout(panes, Math.max(focused, -1));
    }

    private void writeLayout(String key, String guard, Layout layout) {
        Preferences node = prefs.node(LAYOUT_NODE);
        if (layout == null || layout.isEmpty()) {
            node.remove(key);
        } else {
            StringBuilder sb = new StringBuilder(guard)
                    .append(FIELD_SEP).append(layout.focusedIndex());
            for (PaneLayout pane : layout.panes()) {
                sb.append('\n').append(serialize(pane));
            }
            node.put(key, sb.toString());
        }
        flush();
    }

    // --- Migration from the single-project session -------------------------

    /**
     * Converts the state written by the single-project versions into the session format, once:
     * the boolean {@code project.open} becomes the list of open projects, and the layout of the
     * last project (or, failing that, the project-independent one) becomes the session layout.
     * Every legacy per-project layout is then dropped. A userdir with no legacy state, or one
     * already carrying a session layout, is left untouched.
     */
    private void migrateLegacySession() {
        try {
            Preferences layoutNode = prefs.node(LAYOUT_NODE);
            String legacyOpen = prefs.get(PROJECT_OPEN, null);
            boolean legacyFlag = "true".equals(legacyOpen) || "false".equals(legacyOpen);
            if (!layoutNode.get(LAYOUT_SESSION_KEY, "").isBlank()
                    || (!legacyFlag && layoutNode.keys().length == 0)) {
                return;
            }
            String last = getLastProject();
            Layout layout = last == null ? Layout.EMPTY : readLayout(expansionKey(last), last);
            if (layout.isEmpty()) {
                layout = readLayout(LAYOUT_LEGACY_GLOBAL_KEY, "");
            }
            for (String key : layoutNode.keys()) {
                layoutNode.remove(key);
            }
            setSessionLayout(layout);
            // A missing flag meant "open": that was its default.
            boolean wasOpen = !"false".equals(legacyOpen);
            setOpenProjects(wasOpen && last != null ? List.of(last) : List.of(), last);
            LOG.info(() -> "Migrated the legacy session state" + (last == null ? "" : " of " + last));
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Could not migrate the legacy session state", ex);
        }
    }

    private static String serialize(PaneLayout pane) {
        StringBuilder sb = new StringBuilder(pane.role().name())
                .append(FIELD_SEP).append(pane.x())
                .append(FIELD_SEP).append(pane.y())
                .append(FIELD_SEP).append(pane.width())
                .append(FIELD_SEP).append(pane.height())
                .append(FIELD_SEP).append(pane.activeIndex());
        for (TabEntry tab : pane.tabs()) {
            sb.append(FIELD_SEP).append(tab.kind().name())
                    .append(TAB_SEP).append(tab.id())
                    .append(TAB_SEP).append(tab.topParagraph())
                    .append(TAB_SEP).append(tab.caretParagraph())
                    .append(TAB_SEP).append(tab.caretColumn());
        }
        return sb.toString();
    }

    /** Parses one serialized pane line, returning {@code null} if it is malformed or unknown. */
    private static PaneLayout parsePane(String line) {
        if (line.isBlank()) {
            return null;
        }
        String[] f = line.split(FIELD_SEP, -1);
        if (f.length < 6) {
            return null;
        }
        NbfxTabPane.PaneRole role = parseEnum(NbfxTabPane.PaneRole.class, f[0]);
        if (role == null) {
            return null;
        }
        List<TabEntry> tabs = new ArrayList<>();
        for (int i = 6; i < f.length; i++) {
            TabEntry tab = parseTab(f[i]);
            if (tab != null) {
                tabs.add(tab);
            }
        }
        int active = Math.min(parseInt(f, 5), tabs.size() - 1);
        if (tabs.isEmpty() && role == NbfxTabPane.PaneRole.DETACHED) {
            // A detached pane exists only for its tabs; without any it would restore as an empty window.
            return null;
        }
        return new PaneLayout(role, parseDouble(f, 1), parseDouble(f, 2),
                parseDouble(f, 3), parseDouble(f, 4), tabs, active);
    }

    /** Parses one serialized tab field, returning {@code null} if it is malformed or unknown. */
    private static TabEntry parseTab(String field) {
        if (field.isBlank()) {
            return null;
        }
        String[] p = field.split(TAB_SEP, -1);
        TabKind kind = p.length < 2 ? null : parseEnum(TabKind.class, p[0]);
        if (kind == null || p[1].isBlank()) {
            return null;
        }
        return new TabEntry(kind, p[1], parseInt(p, 2), parseInt(p, 3), parseInt(p, 4));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static int parseInt(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static double parseDouble(String[] parts, int index) {
        if (index >= parts.length) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(parts[index].trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    // --- Detached-window geometry ------------------------------------------

    /** Returns valid {@code {x, y, width, height}}, centering on the primary screen if off-screen. */
    static double[] clampBounds(double x, double y, double width, double height) {
        double w = width > 0 ? width : DEFAULT_DETACHED_WIDTH;
        double h = height > 0 ? height : DEFAULT_DETACHED_HEIGHT;
        if (Double.isNaN(x) || Double.isNaN(y) || !isVisibleOnAnyScreen(x, y, w, h)) {
            Rectangle2D vb = Screen.getPrimary().getVisualBounds();
            x = vb.getMinX() + (vb.getWidth() - w) / 2;
            y = vb.getMinY() + (vb.getHeight() - h) / 2;
        }
        return new double[]{x, y, w, h};
    }

}
