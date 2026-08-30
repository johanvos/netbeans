package com.gluonhq.netbeans.nbfx.launcher;

import com.gluonhq.netbeans.nbfx.api.Command;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.gluonhq.netbeans.nbfx.api.ContentManager;
import com.gluonhq.netbeans.nbfx.api.EditorContext;
import com.gluonhq.netbeans.nbfx.api.EditorDocument;
import com.gluonhq.netbeans.nbfx.api.FileTypes;
import com.gluonhq.netbeans.nbfx.file.actions.FileDragAndDrop;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.openide.filesystems.FileObject;
import org.openide.util.Lookup;

/**
 * A {@link TabPane} control with cross-window drag and drop, holding tabs whose content is either a
 * code editor or a project navigator (tree). All tab logic lives here:
 * <ul>
 *     <li>tabs can be reordered within their own pane,</li>
 *     <li>tabs can be detached to a new {@link Stage} when dropped outside their window,</li>
 *     <li>tabs can be moved to another existing pane (in any window),</li>
 *     <li>selecting a tab moves keyboard focus onto its content (editor caret or tree),</li>
 *     <li>selecting an editor tab tracks it as the shared active document.</li>
 * </ul>
 * <p>Visual feedback is provided by {@link TabDropIndicator} (drop target bounds highlighted with a placeholder tab)
 * and {@link TabDragOverlays} (per-screen near-transparent stages catching drops outside the current window).
 * <p>Files dropped on a pane - dragged from a navigator tree or from another application - are
 * opened in an editor tab, wherever in the pane they land.
 */
final class NbfxTabPane extends TabPane {

    private static final Logger LOG = Logger.getLogger(NbfxTabPane.class.getName());

    /**
     * The role a pane plays in the window layout. Tabs of either kind (editor or navigator) may be
     * dragged into any pane, so the role describes the <em>pane</em>, never the tabs it holds. It is
     * the only stable identity a pane has and is what layout persistence is keyed on.
     */
    enum PaneRole {
        /** The docked editor pane in the main window; exactly one exists. */
        MAIN,
        /** The docked navigator pane in the main window; exactly one exists. */
        NAVIGATOR,
        /** A pane living in its own detached window; any number may exist. */
        DETACHED
    }

    /** Custom drag board data format used to identify internal tab drags. */
    static final DataFormat TAB_DATA_FORMAT = new DataFormat("application/x-nbfx-tab-id");

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase().contains("mac");

    /** Keyboard modifier symbols, matching the platform's menu accelerators. */
    static final String SYM_SHIFT = MAC ? "\u21E7" : "Shift+";
    static final String SYM_CMD = MAC ? "\u2318" : "Ctrl+";
    static final String SYM_OPT = MAC ? "\u2325" : "Alt+";

    /** Key under which each tab stores its {@link EditorDocument} in {@code Tab.getProperties()}. */
    private static final String DOCUMENT_KEY = "nbfx.editorDocument";

    /** Key under which a navigator tab stores its stable provider identifier for persistence. */
    private static final String NAVIGATOR_ID_KEY = "nbfx.navigatorId";

    private static final String STYLESHEET = Objects.requireNonNull(
            NbfxTabPane.class.getResource("styles.css")).toExternalForm();
    private static final String TAB_PANE_CLASS = "nbfx-tab-pane";
    private static final String TAB_HEADER_CLASS = "nbfx-tab-header";
    private static final PseudoClass FOCUS_WITH_IN_TAB_PANE = PseudoClass.getPseudoClass("focus-in-tabpane");

    private static final double DETACHED_WIDTH = 900;
    private static final double DETACHED_HEIGHT = 650;
    private static final double DETACHED_OFFSET_X = 450;
    private static final double DETACHED_OFFSET_Y = 20;

    /**
     * Maps of drag id and dragged Tab pairs, that are populated on {@code onDragDetected},
     * cleared on {@code onDragDone}.
     * */
    private static final Map<String, Tab> DRAG_REGISTRY = new HashMap<>();
    /** All panes created (main + navigator + every detached window). */
    private static final Set<TabPane> TAB_PANES = new LinkedHashSet<>();

    /** True while a tab-header drag gesture is in progress (between drag-detected and drag-done). */
    private static boolean tabDragInProgress;
    /**
     * Detached panes that became empty while a drag was in progress; their windows are closed only
     * once the drag finishes. Closing a window mid-drag crashes the macOS Cocoa drag manager.
     */
    private static final Set<TabPane> PANES_PENDING_CLOSE = new LinkedHashSet<>();

    /** The single {@link ActionBars} instance used to build each detached window's chrome; set by the launcher. */
    private static ActionBars actionBars;

    /** Reveals a file in the main window's navigator; set by the launcher so detached windows can use it. */
    private static NavigatorRevealer navigatorRevealer;

    /** Switches the selected project; set by the launcher so every window's shortcut can reach it. */
    private static ProjectSwitcher projectSwitcher;

    /** Bridges a detached window's reveal shortcut to the launcher's navigator (which lives only in the main window). */
    @FunctionalInterface
    interface NavigatorRevealer {
        void reveal(FileObject file, int index);
    }

    /** Whether Shift / Alt was down on the most recent mouse press (read by editor close-button handling). */
    private static boolean lastPressShift;
    private static boolean lastPressAlt;

    /**
     * While {@code true}, a navigator reveal is moving keyboard focus into the tree, so a pane must not
     * re-focus its selected editor when its window (re)gains focus. Without this, activating the main
     * window during a reveal fired from a detached window would steal focus back to the editor.
     */
    private static boolean navigatorRevealInProgress;

    static {
        TabDragOverlays.setDropHandler(NbfxTabPane::detachTabToNewWindow);
    }

    private final PaneRole role;

    /** The pane that last held focus, so the persisted layout can restore the active pane and window. */
    private static TabPane lastFocusedPane;

    /** Creates a detached-window pane; see {@link #NbfxTabPane(PaneRole)}. */
    NbfxTabPane() {
        this(PaneRole.DETACHED);
    }

    /** Creates a new pane with cross-pane drag and drop features and focus/active-document wiring. */
    NbfxTabPane(PaneRole role) {
        this.role = Objects.requireNonNull(role);
        setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        getStyleClass().add(TAB_PANE_CLASS);
        attachStylesheetWhenAttached(this);
        trackFocus(this);
        TAB_PANES.add(this);
        addTabPaneDragHandlers(this);
        wireEditorContext(this);
        // Record modifier keys on every press so a close-button click (which fires onCloseRequest
        // right after) can tell a plain click from Shift+click / Alt+click.
        addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            lastPressShift = e.isShiftDown();
            lastPressAlt = e.isAltDown();
        });
    }

    /** Returns an optional with the {@code tab} that holds the {@code fileObject}, or empty if not found. */
    static Optional<Tab> findTab(FileObject fileObject) {
        for (TabPane tabPane : TAB_PANES) {
            for (Tab tab : tabPane.getTabs()) {
                if (Objects.requireNonNull(fileObject).equals(tab.getUserData())) {
                    return Optional.of(tab);
                }
            }
        }
        return Optional.empty();
    }

    /** Selects the given {@code tab} and moves its window to the front. */
    static void selectTabAndMoveToFront(Tab tab) {
        Platform.runLater(() -> selectTabAndMoveToFront(tab.getTabPane(), tab));
    }

    /** Associates an {@link EditorDocument} with a {@code tab}. */
    static void setDocument(Tab tab, EditorDocument document) {
        tab.getProperties().put(DOCUMENT_KEY, document);
    }

    /** Returns the {@link EditorDocument} associated with {@code tab}, or {@code null} if none. */
    static EditorDocument documentOf(Tab tab) {
        return tab == null ? null : (EditorDocument) tab.getProperties().get(DOCUMENT_KEY);
    }

    /** Associates a stable navigator provider id with {@code tab} (for order/selection persistence). */
    static void setNavigatorId(Tab tab, String id) {
        tab.getProperties().put(NAVIGATOR_ID_KEY, id);
    }

    /** Returns the navigator provider id associated with {@code tab}, or {@code null} if none. */
    static String navigatorId(Tab tab) {
        return tab == null ? null : (String) tab.getProperties().get(NAVIGATOR_ID_KEY);
    }

    /** Requests focus on {@code tabPane}'s selected tab content (editor caret or tree), if any. */
    static void focusSelectedTab(TabPane tabPane) {
        if (tabPane != null) {
            focusDocument(tabPane.getSelectionModel().getSelectedItem());
        }
    }

    /**
     * Restores the default docking layout: moves every tab out of the detached windows back into
     * their home pane (editor tabs into the {@link PaneRole#MAIN} pane, navigator tabs into the
     * {@link PaneRole#NAVIGATOR} one), which empties and closes the detached windows via the
     * existing pane listeners.
     */
    static void redockAll(TabPane editorHome, TabPane navigatorHome) {
        for (TabPane pane : List.copyOf(TAB_PANES)) {
            if (pane == editorHome || pane == navigatorHome) {
                continue;
            }
            for (Tab tab : List.copyOf(pane.getTabs())) {
                pane.getTabs().remove(tab);
                attachTab(documentOf(tab) != null ? editorHome : navigatorHome, tab);
            }
        }
    }

    /** Adds {@code tab} to {@code tabPane} (at the end) and sets its drag handlers up. */
    static void attachTab(TabPane tabPane, Tab tab) {
        attachTabAtIndex(tabPane, tab, -1);
    }

    /**
     * Handles a modifier click on an editor tab's close button, invoked from its close-request handler:
     * Shift closes all documents, Alt/Option closes the other documents. Returns {@code true} (having
     * consumed {@code event} to cancel the single-tab close) when a modifier action was taken.
     */
    static boolean handleEditorCloseModifiers(javafx.event.Event event, EditorDocument document) {
        if (lastPressShift) {
            event.consume();
            DocumentCloser.closeAllDocuments();
            return true;
        }
        if (lastPressAlt) {
            event.consume();
            DocumentCloser.closeOtherDocuments(document);
            return true;
        }
        return false;
    }

    /**
     * Installs {@code text} as the tooltip of {@code tab}'s close button. The button is a skin node
     * built lazily once the tab's header is shown, so installation waits until the tab's graphic is
     * attached to a scene and then retries over a few pulses until the button node exists.
     */
    static void installCloseButtonTooltip(Tab tab, String text) {
        Node graphic = tab.getGraphic();
        if (graphic == null) {
            return;
        }
        if (graphic.getScene() != null) {
            doInstallCloseButtonTooltip(tab, text);
        }
        // The tab may not be in a scene yet (created before its pane is shown), and its graphic
        // re-enters a scene whenever the tab is moved to another window; (re)install each time.
        graphic.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                doInstallCloseButtonTooltip(tab, text);
            }
        });
    }

    /**
     * Installs {@code text} as the tooltip of {@code tab}'s label (graphic) only, so it does not
     * cover the close button, which carries its own tooltip.
     */
    static void installTabLabelTooltip(Tab tab, String text) {
        Node graphic = tab.getGraphic();
        if (graphic != null) {
            Tooltip.install(graphic, new Tooltip(text));
        }
    }

    private static void doInstallCloseButtonTooltip(Tab tab, String text) {
        Node button = closeButtonFor(tab);
        if (button != null) {
            Tooltip.install(button, new Tooltip(text));
            return;
        }
        if (tab.getGraphic() == null || tab.getGraphic().getScene() == null) {
            return;
        }
        Platform.runLater(() -> doInstallCloseButtonTooltip(tab, text));
    }

    /** Finds the {@code .tab-close-button} node belonging to {@code tab}'s header, or {@code null}. */
    private static Node closeButtonFor(Tab tab) {
        Node graphic = tab.getGraphic();
        if (graphic == null) {
            return null;
        }
        for (Node n = graphic.getParent(); n != null; n = n.getParent()) {
            if (n.getStyleClass().contains("tab")) {
                return n.lookup(".tab-close-button");
            }
        }
        return null;
    }

    /**
     * Closes every editor tab in every pane (main and detached), leaving navigator (non-editor)
     * tabs in place. Removing the tabs unregisters their documents and lets detached windows close
     * once emptied, via the existing pane listeners.
     */
    static void closeAllTabs() {
        closeTabs(document -> true);
    }

    /**
     * Closes the editor tabs whose document matches {@code filter}, in every pane (main and
     * detached), leaving navigator (non-editor) tabs in place. Removing the tabs unregisters their
     * documents and lets detached windows close once emptied, via the existing pane listeners.
     */
    static void closeTabs(Predicate<EditorDocument> filter) {
        Platform.runLater(() -> {
            for (TabPane tabPane : List.copyOf(TAB_PANES)) {
                tabPane.getTabs().removeIf(tab -> {
                    EditorDocument document = documentOf(tab);
                    return document != null && filter.test(document);
                });
            }
        });
    }

    /** Every open editor document, in pane and tab order. */
    static List<EditorDocument> allDocuments() {
        List<EditorDocument> documents = new ArrayList<>();
        for (TabPane tabPane : TAB_PANES) {
            documents.addAll(documentsOf(tabPane));
        }
        return documents;
    }

    /**
     * Closes a single {@code tab} by removing it from its pane. The pane listeners then unregister
     * its document and close the owning detached window if it becomes empty.
     */
    static void closeTab(Tab tab) {
        Platform.runLater(() -> {
            TabPane pane = tab.getTabPane();
            if (pane != null) {
                pane.getTabs().remove(tab);
            }
        });
    }

    /** Returns an optional with the dragged tab encoded in {@code db}, or empty if none/unknown. */
    static Optional<Tab> draggedTab(Dragboard db) {
        if (db == null || !db.hasContent(TAB_DATA_FORMAT)) {
            return Optional.empty();
        }
        return Optional.of(DRAG_REGISTRY.get((String) db.getContent(TAB_DATA_FORMAT)));
    }

    private static void wireEditorContext(TabPane tabPane) {
        // Track the active document: whenever an editor tab becomes selected in any pane, it is active.
        // Selecting a navigator (non-editor) tab keeps the last editor active; an empty pane clears it.
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, selectedTab) -> {
            trackActiveDocument(selectedTab);
            focusDocument(selectedTab);
        });
        tabPane.focusedProperty().subscribe(focused -> {
            if (Boolean.TRUE.equals(focused)) {
                Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
                focusDocument(selectedTab);
            }
        });
        // Also follow window focus: selecting the already-selected tab fires no selection event,
        // so without this the active document would go stale when switching between windows.
        tabPane.sceneProperty()
                .flatMap(Scene::windowProperty)
                .flatMap(Window::focusedProperty)
                .subscribe(focused -> {
                    if (Boolean.TRUE.equals(focused)) {
                        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
                        trackActiveDocument(selectedTab);
                        // Only the pane that actually held focus restores it. Every pane of the
                        // window gets this callback, so focusing unconditionally would hand focus
                        // to whichever pane subscribed last (the main one, as it is built after the
                        // navigator) on every activation, wherever the user had been working.
                        if (!navigatorRevealInProgress && isLastFocused(tabPane)) {
                            focusDocument(selectedTab);
                        }
                    }
                });
        // Unregister documents whose tab has been closed (removed from every pane).
        tabPane.getTabs().addListener((ListChangeListener<Tab>) change -> {
            while (change.next()) {
                if (change.wasRemoved()) {
                    List<Tab> removed = List.copyOf(change.getRemoved());
                    // Defer: a move/reorder removes then re-adds the tab within the same pulse.
                    Platform.runLater(() -> removed.forEach(NbfxTabPane::unregisterIfClosed));
                }
            }
        });
    }

    private static void setActiveDocument(EditorDocument document) {
        EditorContext context = editorContext();
        if (context != null) {
            context.setActiveDocument(document);
        }
    }

    /**
     * Updates the shared active document from the selected tab: an editor tab becomes active; a
     * navigator (non-editor) tab keeps the previous editor active; no selection clears it.
     */
    private static void trackActiveDocument(Tab selectedTab) {
        if (selectedTab == null) {
            setActiveDocument(null);
        } else {
            EditorDocument document = documentOf(selectedTab);
            if (document != null) {
                setActiveDocument(document);
            }
        }
    }

    /**
     * Moves keyboard focus onto the selected tab's content so it is ready for input: an editor tab
     * focuses its caret via the {@link EditorDocument}; any other tab (e.g. a navigator tree) focuses
     * its content node directly.
     */
    /** Focuses the content of {@code tab} (an editor through its document, so its caret shows). */
    static void focusDocument(Tab tab) {
        if (tab == null) {
            return;
        }
        EditorDocument document = documentOf(tab);
        if (document != null) {
            Platform.runLater(document::requestFocus);
        } else if (tab.getContent() != null) {
            Node content = tab.getContent();
            Platform.runLater(content::requestFocus);
        }
    }

    private static void unregisterIfClosed(Tab tab) {
        if (isTabAttached(tab)) {
            return;
        }
        EditorDocument document = documentOf(tab);
        EditorContext context = editorContext();
        if (document != null && context != null) {
            context.unregister(document);
        }
    }

    private static boolean isTabAttached(Tab tab) {
        for (TabPane tabPane : TAB_PANES) {
            if (tabPane.getTabs().contains(tab)) {
                return true;
            }
        }
        return false;
    }

    private static EditorContext editorContext() {
        return Lookup.getDefault().lookup(EditorContext.class);
    }

    private static void addTabPaneDragHandlers(TabPane tabPane) {
        addFileDropHandlers(tabPane);
        tabPane.setOnDragOver(e -> {
            draggedTab(e.getDragboard()).ifPresent(dragged -> {
                e.acceptTransferModes(TransferMode.MOVE);
                TabDropIndicator.show(tabPane, e.getX(), dragged);
            });
            e.consume();
        });
        tabPane.setOnDragEntered(e -> {
            draggedTab(e.getDragboard()).ifPresent(dragged ->
                TabDropIndicator.show(tabPane, e.getX(), dragged));
            e.consume();
        });
        tabPane.setOnDragExited(e -> {
            TabDropIndicator.hide(tabPane);
            e.consume();
        });
        tabPane.setOnDragDropped(e -> {
            boolean success = draggedTab(e.getDragboard()).map(dragged -> {
                int dropIndex = TabDropIndicator.getDropIndex(tabPane, e.getX());
                applyDrop(tabPane, dragged, dropIndex);
                return true;
            }).orElse(false);
            e.setDropCompleted(success);
            TabDropIndicator.hide(tabPane);
            e.consume();
        });
    }

    /**
     * Accepts files dropped on {@code tabPane} - dragged from a navigator tree or from outside the
     * application - and opens them in an editor tab, as a double-click or Open would.
     * <p>
     * These are event <em>filters</em>, so the whole pane is a drop target (tab headers, the empty
     * header area and the editor content alike) and the editor cannot swallow the gesture first.
     * Drops aimed at a node handling files itself (a navigator tree, which imports them into the
     * folder they land on) are left alone.
     */
    private static void addFileDropHandlers(TabPane tabPane) {
        tabPane.addEventFilter(DragEvent.DRAG_OVER, e -> {
            if (openableFilesOf(e).isEmpty()) {
                return;
            }
            e.acceptTransferModes(TransferMode.COPY);
            TabDropIndicator.showHighlight(tabPane);
            e.consume();
        });
        tabPane.addEventFilter(DragEvent.DRAG_EXITED_TARGET, e -> {
            // DRAG_EXITED never reaches the pane (its skin's children are the picked targets), and
            // this fires whenever the gesture leaves one of them - including when it ends. The next
            // DRAG_OVER, delivered in the same pulse while the gesture is still over the pane,
            // highlights it again, so moving across the pane does not flicker.
            if (FileDragAndDrop.hasFiles(e.getDragboard())) {
                TabDropIndicator.hide(tabPane);
            }
        });
        tabPane.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
            List<FileObject> files = openableFilesOf(e);
            if (files.isEmpty()) {
                return;
            }
            ContentManager contentManager = Lookup.getDefault().lookup(ContentManager.class);
            if (contentManager != null) {
                files.forEach(file -> openFileInto(tabPane, file, contentManager));
            }
            TabDropIndicator.hide(tabPane);
            e.setDropCompleted(contentManager != null);
            e.consume();
        });
    }

    /**
     * Opens {@code file} in {@code tabPane}: a file already open anywhere is simply revealed, while
     * a newly opened one - which {@link ContentManager} always adds to the main pane - is moved into
     * the pane it was dropped on, once its tab has been created (on a later pulse).
     */
    private static void openFileInto(TabPane tabPane, FileObject file, ContentManager contentManager) {
        boolean open = findTab(file).isPresent();
        contentManager.openFile(file, null);
        if (open) {
            return;
        }
        Platform.runLater(() -> findTab(file).ifPresent(tab -> {
            if (tab.getTabPane() != tabPane) {
                moveTab(tabPane, tab, tabPane.getTabs().size());
                selectTabAndMoveToFront(tabPane, tab);
            }
        }));
    }

    /**
     * The files carried by {@code event} that can be opened in an editor, or an empty list when it
     * carries none, when none of them is openable (a folder or a binary file), or when the drop is
     * aimed at a node that handles files itself.
     */
    private static List<FileObject> openableFilesOf(DragEvent event) {
        if (!FileDragAndDrop.hasFiles(event.getDragboard())
                || FileDragAndDrop.isWithinDropTarget(event.getTarget())) {
            return List.of();
        }
        return FileDragAndDrop.filesFrom(event.getDragboard()).stream()
                .filter(FileTypes::isOpenable)
                .toList();
    }

    private static void applyDrop(TabPane tabPane, Tab dragged, int dropIndex) {
        TabPane source = dragged.getTabPane();
        if (source == tabPane) {
            // reorder within the same TabPane
            int currentIndex = tabPane.getTabs().indexOf(dragged);
            if (currentIndex >= 0 && currentIndex != dropIndex) {
                tabPane.getTabs().remove(currentIndex);
                int newIndex = currentIndex < dropIndex ? dropIndex - 1 : dropIndex;
                newIndex = Math.clamp(newIndex, 0, tabPane.getTabs().size());
                tabPane.getTabs().add(newIndex, dragged);
            }
            tabPane.getSelectionModel().select(dragged);
        } else {
            // move from another TabPane
            if (source != null) {
                source.getTabs().remove(dragged);
            }
            int newIndex = Math.clamp(dropIndex, 0, tabPane.getTabs().size());
            attachTabAtIndex(tabPane, dragged, newIndex);
        }
    }

    private static void attachTabAtIndex(TabPane tabPane, Tab tab, int index) {
        if (index < 0) {
            tabPane.getTabs().add(tab);
        } else {
            tabPane.getTabs().add(index, tab);
        }
        selectTabAndMoveToFront(tabPane, tab);
        addTabDragHandlers(tab);
    }

    private static void selectTabAndMoveToFront(TabPane tabPane, Tab tab) {
        Objects.requireNonNull(tabPane).getSelectionModel().select(Objects.requireNonNull(tab));
        if (tabPane.getScene() != null && tabPane.getScene().getWindow() instanceof Stage stage) {
            stage.toFront();
        }
    }

    private static void addTabDragHandlers(Tab tab) {
        if (!(tab.getGraphic() instanceof Label label)) {
            return;
        }
        if (!label.getStyleClass().contains(TAB_HEADER_CLASS)) {
            label.getStyleClass().add(TAB_HEADER_CLASS);
        }

        label.setOnDragDetected(e -> {
            String id = UUID.randomUUID().toString();
            DRAG_REGISTRY.put(id, tab);
            tabDragInProgress = true;

            TabDragOverlays.showAll();

            Dragboard db = label.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent cc = new ClipboardContent();
            cc.put(TAB_DATA_FORMAT, id);
            cc.putString(label.getText() == null ? "" : label.getText());
            db.setContent(cc);

            WritableImage dragImage = snapshotTab(tab);
            if (dragImage != null) {
                db.setDragView(dragImage, 0, 0);
            }
            e.consume();
        });

        label.setOnDragDone(e -> {
            tabDragInProgress = false;
            TabDragOverlays.hideAll();
            TabDropIndicator.hideAll(TAB_PANES);
            DRAG_REGISTRY.entrySet().removeIf(entry -> entry.getValue() == tab);
            flushPendingStageCloses();
            e.consume();
        });
    }

    private static WritableImage snapshotTab(Tab tab) {
        Node content = Objects.requireNonNull(tab).getContent();
        if (content == null || content.getScene() == null || content.getScene().getWindow() == null) {
            return null;
        }
        Bounds bounds = content.getLayoutBounds();
        if (bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            return null;
        }

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        double scale = content.getScene().getWindow().getOutputScaleY();
        params.setTransform(Transform.scale(scale, scale));

        WritableImage contentImage = content.snapshot(params, null);
        WritableImage labelImage = (tab.getGraphic() instanceof Label label) ?
                label.snapshot(params, null) : null;

        // Build a temporary TabPane with one Tab whose graphic + content are the snapshots above.
        TabPane tempPane = new TabPane();
        Tab tempTab = new Tab();
        if (labelImage != null) {
            tempTab.setGraphic(scaledImageView(labelImage, scale));
        }
        tempTab.setContent(scaledImageView(contentImage, scale));
        tempPane.getTabs().add(tempTab);

        // The Scene is required so the TabPane gets laid out before being snapshotted.
        new Scene(tempPane);
        tempPane.applyCss();
        tempPane.layout();
        return tempPane.snapshot(params, null);
    }

    private static ImageView scaledImageView(WritableImage image, double scale) {
        ImageView imageView = new ImageView(image);
        imageView.setFitWidth(image.getWidth() / scale);
        imageView.setFitHeight(image.getHeight() / scale);
        return imageView;
    }

    private static void detachTabToNewWindow(Tab tab, Point2D screenLocation) {
        if (tab.getGraphic() instanceof Label label) {
            LOG.info(() -> "Detaching tab [" + label.getText() + "] to a new window");
        }
        TabPane source = tab.getTabPane();
        if (source != null) {
            source.getTabs().remove(tab);
        }

        TabPane newPane = new NbfxTabPane();
        attachTab(newPane, tab);

        Stage stage = createDetachedStage(newPane,
                screenLocation.getX() - DETACHED_OFFSET_X,
                screenLocation.getY() - DETACHED_OFFSET_Y,
                DETACHED_WIDTH, DETACHED_HEIGHT);
        stage.show();
    }

    /**
     * Recreates a detached window at the given bounds holding {@code tabs} (moved out of their
     * current panes, in order), selecting {@code activeTab}.
     */
    static TabPane openDetachedWindow(List<Tab> tabs, Tab activeTab,
                                      double x, double y, double width, double height) {
        if (tabs == null || tabs.isEmpty()) {
            return null;
        }
        TabPane newPane = new NbfxTabPane();
        for (Tab tab : tabs) {
            TabPane source = tab.getTabPane();
            if (source != null) {
                source.getTabs().remove(tab);
            }
            attachTab(newPane, tab);
        }
        Stage stage = createDetachedStage(newPane, x, y, width, height);
        if (activeTab != null) {
            newPane.getSelectionModel().select(activeTab);
        }
        stage.show();
        return newPane;
    }

    /** Creates (but does not show) a detached {@link Stage} wrapping {@code newPane} at the bounds. */
    private static Stage createDetachedStage(TabPane newPane,
                                             double x, double y, double width, double height) {
        Stage stage = new Stage();
        newPane.getSelectionModel().selectedItemProperty().subscribe(newTab -> {
            if (newTab == null) {
                return;
            }
            if (newTab.getUserData() instanceof FileObject fo) {
                stage.setTitle(fo.getNameExt());
            } else if (newTab.getGraphic() instanceof Label label) {
                // Navigator tabs carry no FileObject; fall back to their header text.
                stage.setTitle(label.getText());
            }
        });

        BorderPane root = new BorderPane(newPane);
        List<Command> scopedCommands = new ArrayList<>();
        if (actionBars != null) {
            ObservableValue<EditorDocument> activeDocument =
                    newPane.getSelectionModel().selectedItemProperty().map(NbfxTabPane::documentOf);
            root.setTop(actionBars.createDetachedBars(activeDocument, scopedCommands));
        }

        stage.setScene(new Scene(root, width, height));
        // Shift+Cmd+1 / Shift+Cmd+2 reveal this window's selected editor file in the main navigator.
        installNavigatorRevealShortcut(root.getScene(), () -> {
            EditorDocument document = documentOf(newPane.getSelectionModel().getSelectedItem());
            return document == null ? null : document.getFileObject();
        });
        installProjectSwitchShortcut(root.getScene());
        stage.setX(x);
        stage.setY(y);
        stage.setOnCloseRequest(event -> {
            if (!CloseConfirmation.confirmClose(documentsOf(newPane))) {
                event.consume();
            }
        });
        stage.focusedProperty().subscribe(focused ->
                newPane.pseudoClassStateChanged(FOCUS_WITH_IN_TAB_PANE, focused));
        registerCloseOnLastTabRemoved(newPane, stage, scopedCommands);
        return stage;
    }

    /** Sets the shared {@link ActionBars} used to build the menu bar and tool bars of each detached window. */
    static void setActionBars(ActionBars bars) {
        actionBars = bars;
    }

    /** Sets the hook used by detached editor windows to reveal their selected file in the main navigator. */
    static void setNavigatorRevealer(NavigatorRevealer revealer) {
        navigatorRevealer = revealer;
    }

    /** Sets the switcher driving the Next/Previous Project shortcut in every window. */
    static void setProjectSwitcher(ProjectSwitcher switcher) {
        projectSwitcher = switcher;
    }

    /**
     * Installs the Next/Previous Project shortcut ({@code Shortcut+Alt+Right/Left}) on {@code scene}.
     * <p>
     * It is a filter rather than a menu accelerator because the accelerator only fires for events
     * the focused control leaves alone: the navigator tree claims the arrow keys, so from the tree
     * the menu accelerator never ran. Filtering means the shortcut behaves the same wherever the
     * focus is, in the main window and in the detached editor windows alike.
     */
    static void installProjectSwitchShortcut(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (projectSwitcher == null
                    || !event.isShortcutDown() || !event.isAltDown() || event.isShiftDown()) {
                return;
            }
            if (event.getCode() == KeyCode.RIGHT) {
                projectSwitcher.next();
            } else if (event.getCode() == KeyCode.LEFT) {
                projectSwitcher.previous();
            } else {
                return;
            }
            event.consume();
        });
    }

    /** See {@link #navigatorRevealInProgress}: set while a reveal is moving focus into the navigator. */
    static void setNavigatorRevealInProgress(boolean inProgress) {
        navigatorRevealInProgress = inProgress;
    }

    /**
     * Installs the Shift+Cmd/Ctrl+1 / +2 shortcut on {@code scene} to reveal a file in the Logical /
     * Physical navigator view. The file is taken from {@code fileSupplier} (the active editor of the
     * window owning the scene), so the same handler serves both the main window and detached windows.
     * A capturing filter is used so the shortcut fires even when the editor or tree has focus.
     */
    static void installNavigatorRevealShortcut(Scene scene, Supplier<FileObject> fileSupplier) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (navigatorRevealer == null
                    || !event.isShortcutDown() || !event.isShiftDown() || event.isAltDown()) {
                return;
            }
            int index = event.getCode() == KeyCode.DIGIT1 ? 0 : event.getCode() == KeyCode.DIGIT2 ? 1 : -1;
            if (index < 0) {
                return;
            }
            FileObject file = fileSupplier.get();
            if (file != null) {
                navigatorRevealer.reveal(file, index);
                event.consume();
            }
        });
    }

    /** All TabPanes tracked by this class (main pane first, then every detached window's pane). */
    static List<TabPane> tabPanes() {
        return List.copyOf(TAB_PANES);
    }

    /**
     * The role of {@code pane}. Panes not created by this class are reported as
     * {@link PaneRole#DETACHED}, which cannot happen today (every pane is an {@code NbfxTabPane}).
     */
    static PaneRole roleOf(TabPane pane) {
        return pane instanceof NbfxTabPane nbfx ? nbfx.role : PaneRole.DETACHED;
    }

    /** The single pane playing {@code role}, or {@code null} if it does not exist (yet). */
    static TabPane paneWithRole(PaneRole role) {
        for (TabPane pane : TAB_PANES) {
            if (roleOf(pane) == role) {
                return pane;
            }
        }
        return null;
    }

    /**
     * Every pane ordered {@link PaneRole#MAIN}, {@link PaneRole#NAVIGATOR}, then the detached panes
     * in creation order. Layout persistence relies on this order, since detached windows have no
     * identity beyond their position in the list.
     */
    static List<TabPane> panesByRole() {
        List<TabPane> panes = new ArrayList<>(TAB_PANES);
        panes.sort(Comparator.comparingInt(pane -> roleOf(pane).ordinal()));
        return panes;
    }

    /** Returns the navigator tab whose provider id is {@code navigatorId}, searching every pane. */
    static Optional<Tab> findNavigatorTab(String navigatorId) {
        for (TabPane pane : TAB_PANES) {
            for (Tab tab : pane.getTabs()) {
                if (Objects.requireNonNull(navigatorId).equals(navigatorId(tab))) {
                    return Optional.of(tab);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Moves {@code tab} to position {@code index} of {@code target}, removing it from whichever pane
     * currently holds it. Unlike {@link #attachTab} this neither selects the tab nor fronts its
     * window, so a layout can be rebuilt without stealing focus.
     */
    static void moveTab(TabPane target, Tab tab, int index) {
        TabPane source = tab.getTabPane();
        if (source != null) {
            source.getTabs().remove(tab);
        }
        target.getTabs().add(Math.clamp(index, 0, target.getTabs().size()), tab);
        addTabDragHandlers(tab);
    }

    /** Returns the {@link Stage} showing {@code pane}, or {@code null} if it is not in a Stage. */
    static Stage stageOf(TabPane pane) {
        if (pane != null && pane.getScene() != null
                && pane.getScene().getWindow() instanceof Stage stage) {
            return stage;
        }
        return null;
    }

    /** Returns the {@link EditorDocument}s held by the tabs of {@code tabPane}, in order. */
    static List<EditorDocument> documentsOf(TabPane tabPane) {
        List<EditorDocument> documents = new ArrayList<>();
        for (Tab tab : tabPane.getTabs()) {
            EditorDocument document = documentOf(tab);
            if (document != null) {
                documents.add(document);
            }
        }
        return documents;
    }

    private static void registerCloseOnLastTabRemoved(TabPane newPane, Stage stage, List<Command> scopedCommands) {
        Objects.requireNonNull(newPane).getTabs().addListener((ListChangeListener<Tab>) _ -> {
            if (newPane.getTabs().isEmpty()) {
                // Check again, after the next pulse, whether the pane is still empty (it could have
                // been a reordering gesture where removed tabs were added back again).
                Platform.runLater(() -> closeDetachedStageIfEmpty(newPane));
            }
        });
        Objects.requireNonNull(stage).setOnHidden(_ -> {
            TAB_PANES.remove(newPane);
            PANES_PENDING_CLOSE.remove(newPane);
            if (lastFocusedPane == newPane) {
                // Don't keep a closed window (and its scene graph) alive through the focus tracking.
                lastFocusedPane = null;
            }
            // This window's scoped commands observe documents that outlive it, so detach them.
            scopedCommands.forEach(Command::dispose);
            scopedCommands.clear();
            // The window closed with tabs still in it (Save/Discard on close): those tabs are
            // gone now, so unregister their documents. If the pane was emptied by a drag-out or
            // last-tab close, documentsOf(newPane) is empty and there is nothing to unregister.
            EditorContext context = editorContext();
            if (context != null) {
                documentsOf(newPane).forEach(context::unregister);
            }
        });
    }

    /**
     * Closes the detached window owning {@code pane} if the pane is (still) empty. While a tab drag
     * is in progress the close is deferred until the drag finishes, because destroying a window's
     * native view during a drag session crashes the macOS Cocoa drag manager (SIGSEGV).
     */
    private static void closeDetachedStageIfEmpty(TabPane pane) {
        if (!pane.getTabs().isEmpty()) {
            PANES_PENDING_CLOSE.remove(pane);
            return;
        }
        Window window = pane.getScene() != null ? pane.getScene().getWindow() : null;
        if (!(window instanceof Stage stage)) {
            return;
        }
        if (tabDragInProgress) {
            PANES_PENDING_CLOSE.add(pane);
        } else {
            PANES_PENDING_CLOSE.remove(pane);
            stage.close();
        }
    }

    /** Closes windows of panes that emptied during a drag, once the drag has fully finished. */
    private static void flushPendingStageCloses() {
        if (PANES_PENDING_CLOSE.isEmpty()) {
            return;
        }
        List<TabPane> panes = List.copyOf(PANES_PENDING_CLOSE);
        PANES_PENDING_CLOSE.clear();
        // Defer one more pulse so the native drag session is fully torn down before closing windows.
        Platform.runLater(() -> panes.forEach(NbfxTabPane::closeDetachedStageIfEmpty));
    }

    /**
     * Records {@code tabPane} as the last focused one whenever focus enters it or any of its
     * content, so the layout can be persisted with the pane that was active - and the window that
     * held it - and restore both.
     */
    private static void trackFocus(TabPane tabPane) {
        tabPane.focusWithinProperty().subscribe(focused -> {
            if (focused) {
                lastFocusedPane = tabPane;
                LOG.fine(() -> "Focus entered the " + roleOf(tabPane) + " pane");
            }
        });
    }

    /** Whether {@code pane} was the last one to hold focus. */
    static boolean isLastFocused(TabPane pane) {
        return pane != null && pane == lastFocusedPane;
    }

    /**
     * The pane that holds the keyboard focus, for persisting the layout.
     * <p>
     * Read from the live scene graph rather than from {@link #lastFocusedPane}: a scene keeps its
     * focus owner while its window is deactivated, so it still tells where the caret was even though
     * every pane reports {@code focusWithin == false} by then. Panes of a focused window win over
     * panes of a background one; {@link #lastFocusedPane} only breaks ties between windows that are
     * all deactivated (the state during shutdown on some platforms).
     */
    static TabPane focusedPane() {
        TabPane owner = null;
        for (TabPane pane : TAB_PANES) {
            if (!holdsFocusOwner(pane)) {
                continue;
            }
            Window window = pane.getScene().getWindow();
            if (window != null && window.isFocused()) {
                return pane;
            }
            if (owner == null) {
                owner = pane;
            }
        }
        if (holdsFocusOwner(lastFocusedPane)) {
            return lastFocusedPane;
        }
        return owner != null ? owner : lastFocusedPane;
    }

    /** Whether {@code pane}'s scene currently points its focus owner at the pane or its content. */
    private static boolean holdsFocusOwner(TabPane pane) {
        if (pane == null || !TAB_PANES.contains(pane)) {
            return false;
        }
        Scene scene = pane.getScene();
        return scene != null && isWithin(scene.getFocusOwner(), pane);
    }

    private static boolean isWithin(Node node, Node ancestor) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** Per-pane focus state, logged when persisting the layout to explain the pane that was picked. */
    static String describeFocus() {
        StringBuilder text = new StringBuilder("last focused = ")
                .append(lastFocusedPane == null ? "none" : roleOf(lastFocusedPane));
        for (TabPane pane : TAB_PANES) {
            Scene scene = pane.getScene();
            Window window = scene == null ? null : scene.getWindow();
            Node focusOwner = scene == null ? null : scene.getFocusOwner();
            text.append("; ").append(roleOf(pane))
                    .append(": focusWithin=").append(pane.isFocusWithin())
                    .append(", ownsFocusOwner=").append(isWithin(focusOwner, pane))
                    .append(", windowFocused=").append(window != null && window.isFocused())
                    .append(", focusOwner=")
                    .append(focusOwner == null ? "none" : focusOwner.getClass().getSimpleName());
        }
        return text.toString();
    }

    private static void attachStylesheetWhenAttached(TabPane tabPane) {
        tabPane.sceneProperty().addListener(new InvalidationListener() {
            @Override
            public void invalidated(Observable observable) {
                Scene scene = tabPane.getScene();
                if (scene != null) {
                    if (!scene.getStylesheets().contains(STYLESHEET)) {
                        scene.getStylesheets().add(STYLESHEET);
                    }
                    tabPane.sceneProperty().removeListener(this);
                }
            }
        });
    }
}
