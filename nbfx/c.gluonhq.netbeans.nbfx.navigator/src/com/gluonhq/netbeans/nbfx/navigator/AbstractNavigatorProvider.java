package com.gluonhq.netbeans.nbfx.navigator;

import com.gluonhq.netbeans.nbfx.api.NavigatorProvider;
import com.gluonhq.netbeans.nbfx.api.OpenProject;
import com.gluonhq.netbeans.nbfx.navigator.utils.ProjectModuleInfoAccessibilityQuery;
import com.gluonhq.netbeans.nbfx.navigator.utils.Projects;
import com.gluonhq.netbeans.nbfx.navigator.utils.TreeNav;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.util.Duration;

import org.openide.filesystems.FileChangeListener;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Shared base for the two {@link NavigatorProvider} implementations: the logical Project view
 * ({@link ProjectNavigatorImpl}) and the physical Files view ({@link FileNavigatorImpl}). It pulls
 * up the algorithms that are genuinely common to both trees — the per-project bookkeeping
 * ({@link RootState}), the retry/selection machinery used after a drag-and-drop move,
 * {@link #revealFile(FileObject)}, the {@link #selectAndScroll} helper, the symmetric
 * filesystem-listener install/remove pair, and the pending expansion/selection state — and leaves
 * the view-specific parts as abstract hooks: how a {@link FileObject} maps to a (possibly lazily
 * built) tree node ({@link #findItem(FileObject)}) and how the view-specific filesystem listener is
 * created ({@link #createFileChangeListener(RootState)}).
 * <p>
 * Both views can show several projects at once: their {@link TreeView} has a hidden root whose
 * children are the top-level node of each open project, one per {@link RootState}.
 *
 * @param <T> the tree's value type
 */
abstract class AbstractNavigatorProvider<T> implements NavigatorProvider {

    private static final Logger BASE_LOG = Logger.getLogger(AbstractNavigatorProvider.class.getName());

    protected TreeView<T> view;

    /**
     * The state of every project this view knows about, keyed by the project root's path and kept in
     * the order the projects were added. Entries are created either when a project is added or when
     * expansion/selection is restored for it ahead of the project being added.
     * <p>
     * Projects are added and removed on the FX thread, but a project's own loading runs on a
     * background thread that installs its filesystem listener when done, so every access to the map
     * and to the listener fields is guarded by {@link #rootsLock}.
     */
    private final Map<Path, RootState> roots = new LinkedHashMap<>();

    private final Object rootsLock = new Object();

    /** Set while a persisted tree selection is being applied; see {@link #restoringSelection}. */
    private boolean restoringSelection;

    /** Everything this view tracks for a single open project. */
    protected class RootState {

        private final FileObject root;
        private final Path path;
        /** The project's top-level node, a child of the tree's hidden root; {@code null} until built. */
        private TreeItem<T> item;
        private FileChangeListener fileChangeListener;
        /** The exact {@link File} the recursive listener was registered on, retained for symmetric removal. */
        private File listenerRoot;
        /**
         * Bumped whenever the listener is dropped, so a registration that is still running (it is
         * done outside {@link #rootsLock}) knows it has been superseded and must undo itself.
         */
        private long listenerGeneration;
        /** Node identifiers requested for expansion once this project's tree is (lazily) built. */
        private List<String> pendingExpanded;
        /** Node identifier requested for selection once this project's tree is (lazily) built. */
        private String pendingSelected;

        protected RootState(FileObject root, Path path) {
            this.root = root;
            this.path = path;
        }

        final FileObject root() {
            return root;
        }

        /** The project root's path, the key identifying this state. */
        final Path path() {
            return path;
        }

        final TreeItem<T> item() {
            return item;
        }

        final void setItem(TreeItem<T> item) {
            this.item = item;
        }

        final List<String> pendingExpanded() {
            return pendingExpanded;
        }

        /** Returns the pending expansion for this project and forgets it, so it is applied once. */
        final List<String> takePendingExpanded() {
            List<String> paths = pendingExpanded;
            pendingExpanded = null;
            return paths;
        }

        final String pendingSelected() {
            return pendingSelected;
        }

        final void setPendingSelected(String pendingSelected) {
            this.pendingSelected = pendingSelected;
        }

        /** Hook for subclasses that keep further per-project state; called when the project is removed. */
        protected void dispose() {
        }
    }

    /**
     * Creates the per-project state. Subclasses that track more than the base class does override
     * this to return their own {@link RootState} subclass.
     * @param root the project root
     * @param path the project root's path
     * @return the new state
     */
    protected RootState createRootState(FileObject root, Path path) {
        return new RootState(root, path);
    }

    /**
     * Returns the (possibly lazily expanded) tree node backing {@code file}, or {@code null} when it
     * is not present in this view. Each view maps a {@link FileObject} to a node in its own way, and
     * has to look for it across all the projects it shows.
     */
    protected abstract TreeItem<T> findItem(FileObject file);

    /** Creates the view-specific recursive {@link FileChangeListener} for {@code state}. */
    protected abstract FileChangeListener createFileChangeListener(RootState state);

    // --- Per-project state --------------------------------------------------

    /** The states of all known projects, in the order they were added. */
    protected final List<RootState> rootStates() {
        synchronized (rootsLock) {
            return List.copyOf(roots.values());
        }
    }

    /** The state tracking {@code root}, or {@code null} when that project is unknown. */
    protected final RootState rootState(FileObject root) {
        Path path = root == null ? null : FileUtil.toPath(root);
        if (path == null) {
            return null;
        }
        synchronized (rootsLock) {
            return roots.get(path);
        }
    }

    /** The state whose project root contains {@code path}, or {@code null} when no project owns it. */
    protected final RootState rootStateOf(Path path) {
        if (path == null) {
            return null;
        }
        RootState match = null;
        for (RootState state : rootStates()) {
            // Nested projects: the deepest root owning the path wins.
            if (path.startsWith(state.path())
                    && (match == null || state.path().getNameCount() > match.path().getNameCount())) {
                match = state;
            }
        }
        return match;
    }

    /** Creates (or returns) the state for {@code root}; {@code null} when the root has no path. */
    protected final RootState addRootState(FileObject root) {
        Path path = root == null ? null : FileUtil.toPath(root);
        if (path == null) {
            return null;
        }
        synchronized (rootsLock) {
            return roots.computeIfAbsent(path, p -> createRootState(root, p));
        }
    }

    @Override
    public List<FileObject> getProjectRoots() {
        List<FileObject> result = new ArrayList<>();
        for (RootState state : rootStates()) {
            result.add(state.root());
        }
        return result;
    }

    /** Whether this view currently knows about at least one project. */
    protected final boolean hasRoots() {
        synchronized (rootsLock) {
            return !roots.isEmpty();
        }
    }

    @Override
    public void restoreExpandedPaths(FileObject root, List<String> paths) {
        RootState state = addRootState(root);
        if (state != null) {
            state.pendingExpanded = (paths == null || paths.isEmpty()) ? null : List.copyOf(paths);
        }
    }

    @Override
    public void restoreSelectedPath(FileObject root, String path) {
        RootState state = addRootState(root);
        if (state != null) {
            state.setPendingSelected((path == null || path.isEmpty()) ? null : path);
        }
    }

    @Override
    public void removeProject(FileObject root) {
        RootState state = rootState(root);
        if (state != null) {
            removeRootState(state);
        }
    }

    @Override
    public void removeProjectAt(String path) {
        RootState state;
        synchronized (rootsLock) {
            state = path == null || path.isBlank() ? null : roots.get(Path.of(path));
        }
        if (state != null) {
            removeRootState(state);
        }
    }

    @Override
    public void removeAllProjects() {
        for (RootState state : rootStates()) {
            removeRootState(state);
        }
    }

    /**
     * Detaches this view from one project: drops its filesystem listener and pending state, removes
     * its node from the tree and forgets it.
     */
    private void removeRootState(RootState state) {
        state.dispose();
        // The project is going away: nothing parsed from its sources may outlive it. Keyed by the
        // path it was added with, which is where its files were parsed from even if the folder has
        // since been renamed on disk.
        ProjectModuleInfoAccessibilityQuery.evictProject(state.path());
        // Forgetting the project and dropping its listener must be atomic with respect to a
        // background load still trying to install one for it (see installFileChangeListener).
        synchronized (rootsLock) {
            roots.remove(state.path(), state);
            removeListener(state);
        }
        state.pendingExpanded = null;
        state.setPendingSelected(null);
        TreeItem<T> item = state.item();
        state.setItem(null);
        if (item != null) {
            onFxThread(() -> {
                if (item.getParent() != null) {
                    item.getParent().getChildren().remove(item);
                }
            });
        }
    }

    // --- Tree root ----------------------------------------------------------

    /**
     * The tree's hidden root, whose children are the projects' top-level nodes. Created on demand,
     * so both views can attach a project node without caring about the tree's lifecycle.
     */
    protected final TreeItem<T> treeRoot() {
        if (view == null) {
            return null;
        }
        if (view.getRoot() == null) {
            view.setRoot(new TreeItem<>());
        }
        return view.getRoot();
    }

    /**
     * Attaches {@code item} as {@code state}'s top-level node, positioned so that the tree keeps the
     * order in which the projects were added (they can finish loading in any order).
     */
    protected final void setRootItem(RootState state, TreeItem<T> item) {
        TreeItem<T> root = treeRoot();
        if (root == null) {
            return;
        }
        TreeItem<T> previous = state.item();
        if (previous != null) {
            root.getChildren().remove(previous);
        }
        state.setItem(item);
        root.getChildren().add(rootItemIndex(state, root), item);
    }

    /** The insertion index for {@code state}'s node: after the nodes of all projects added before it. */
    private int rootItemIndex(RootState state, TreeItem<T> root) {
        List<TreeItem<T>> children = root.getChildren();
        int index = 0;
        for (RootState other : rootStates()) {
            if (other == state) {
                break;
            }
            if (other.item() != null && children.contains(other.item())) {
                index++;
            }
        }
        return Math.min(index, children.size());
    }

    // --- Reveal / selection -------------------------------------------------

    @Override
    public void revealFile(FileObject file) {
        if (file == null || view == null || !hasRoots()) {
            return;
        }
        Platform.runLater(() -> {
            TreeItem<T> item = findItem(file);
            if (item != null) {
                selectAndScroll(item);
            }
        });
    }

    /**
     * Selects the moved files at their new location once the tree has rebuilt. The rebuild is driven
     * asynchronously by filesystem events, so the lookup is retried a few times until every node is available.
     */
    void selectMovedFiles(List<FileObject> files) {
        if (files == null || files.isEmpty() || view == null || !hasRoots()) {
            return;
        }
        Platform.runLater(() -> {
            List<TreeItem<T>> found = new ArrayList<>();
            for (FileObject file : files) {
                TreeItem<T> item = findItem(file);
                if (item != null) {
                    found.add(item);
                }
            }
            if (found.size() == files.size()) {
                if (!found.isEmpty()) {
                    view.getSelectionModel().clearSelection();
                    for (TreeItem<T> item : found) {
                        view.getSelectionModel().select(item);
                    }
                    view.scrollTo(view.getRow(found.getFirst()));
                }
                return;
            }
            PauseTransition pause = new PauseTransition(Duration.millis(60));
            pause.setOnFinished(a -> selectMovedFiles(files));
            pause.play();
        });
    }

    /**
     * Applies a selection that comes from restoring a session rather than from the user, so the
     * views know not to make its project the selected one: which project is selected follows what
     * the user last touched, and at start-up that is decided by the tab the session comes back
     * focused on, not by the trees quietly restoring their own selections as they finish loading.
     */
    protected final void restoringSelection(Runnable selection) {
        restoringSelection = true;
        try {
            selection.run();
        } finally {
            restoringSelection = false;
        }
    }

    /** Whether the selection currently being applied is a restored one rather than the user's. */
    protected final boolean isRestoringSelection() {
        return restoringSelection;
    }

    /**
     * Repaints {@code tree} whenever the selected project changes, so the project roots' styling
     * keeps showing which project the project-scoped actions apply to.
     */
    protected final void trackSelectedProject(TreeView<T> tree) {
        ObservableValue<OpenProject> selected = Projects.selectedProject();
        if (selected != null) {
            selected.addListener((_, _, _) -> tree.refresh());
        }
    }

    /** Selects {@code item} and scrolls it into view (see {@link TreeNav#selectAndScroll}). */
    protected void selectAndScroll(TreeItem<T> item) {
        TreeNav.selectAndScroll(view, item);
    }

    // --- Filesystem listeners -----------------------------------------------

    /**
     * Registers the view-specific recursive filesystem listener for {@code state} rooted at
     * {@code root} so external and internal changes are reflected live in the tree. Any listener
     * previously installed for that project is removed first, and the exact {@code File} it was
     * registered on is retained for symmetric removal.
     * <p>
     * The registration walks the whole hierarchy - seconds on a deep one, as
     * {@link FileUtil#addRecursiveListener(FileChangeListener, File)} warns - so it deliberately runs
     * without holding {@link #rootsLock}: every navigator lookup takes that lock on the FX thread and
     * would otherwise freeze for the duration. Losing the lock in between means the project may be
     * closed (or given another listener) while this one registers, so the result is only kept if the
     * state is still the live one for its path and its generation has not moved; otherwise the
     * just-registered listener is unregistered again.
     */
    protected final void installFileChangeListener(RootState state, File root) {
        if (state == null || root == null) {
            removeFileChangeListener(state);
            return;
        }
        FileChangeListener listener;
        long generation;
        synchronized (rootsLock) {
            removeListener(state);
            // The project may have been removed while it was still loading in the background: its
            // state is no longer the live one for that path, so nothing must be registered for it.
            if (roots.get(state.path()) != state) {
                BASE_LOG.info("Skipping the file listener of a project that is no longer shown: " + root);
                return;
            }
            generation = state.listenerGeneration;
            listener = createFileChangeListener(state);
        }
        FileUtil.addRecursiveListener(listener, root);
        boolean kept;
        synchronized (rootsLock) {
            kept = roots.get(state.path()) == state && state.listenerGeneration == generation;
            if (kept) {
                state.fileChangeListener = listener;
                state.listenerRoot = root;
            }
        }
        if (!kept) {
            BASE_LOG.info("Dropping the file listener of a project that is no longer shown: " + root);
            removeRecursiveListener(listener, root);
        }
    }

    protected final void removeFileChangeListener(RootState state) {
        if (state == null) {
            return;
        }
        synchronized (rootsLock) {
            removeListener(state);
        }
    }

    /** Unregisters {@code state}'s listener; always called holding {@link #rootsLock}. */
    private void removeListener(RootState state) {
        if (state.fileChangeListener != null && state.listenerRoot != null) {
            removeRecursiveListener(state.fileChangeListener, state.listenerRoot);
        }
        state.fileChangeListener = null;
        state.listenerRoot = null;
        // Tells a registration that is still running for this state that it has been superseded.
        state.listenerGeneration++;
    }

    private static void removeRecursiveListener(FileChangeListener listener, File root) {
        try {
            FileUtil.removeRecursiveListener(listener, root);
        } catch (Exception e) {
            BASE_LOG.warning("Failed to remove file listener: " + e);
        }
    }

    /** The listener installed for {@code state}, or {@code null}; for tests and diagnostics. */
    protected final FileChangeListener fileChangeListener(RootState state) {
        synchronized (rootsLock) {
            return state == null ? null : state.fileChangeListener;
        }
    }

    /** The directory {@code state}'s listener was installed on, or {@code null}. */
    protected final File listenerRoot(RootState state) {
        synchronized (rootsLock) {
            return state == null ? null : state.listenerRoot;
        }
    }

    /** Runs {@code action} on the FX thread, immediately if already on it. */
    protected static void onFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
