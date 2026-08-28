package com.gluonhq.netbeans.nbfx.navigator;

import com.gluonhq.netbeans.nbfx.api.NavigatorProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.gluonhq.netbeans.nbfx.navigator.utils.NavigatorIcons;
import com.gluonhq.netbeans.nbfx.navigator.utils.PackageScanner;
import com.gluonhq.netbeans.nbfx.navigator.utils.Projects;
import com.gluonhq.netbeans.nbfx.navigator.utils.TreeNav;
import com.gluonhq.netbeans.nbfx.file.actions.FileDragAndDrop;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import org.openide.filesystems.FileAttributeEvent;
import org.openide.filesystems.FileChangeListener;
import org.openide.filesystems.FileEvent;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileRenameEvent;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service = NavigatorProvider.class, position=20)
public class FileNavigatorImpl extends AbstractNavigatorProvider<FileObject> {

    static final Logger LOG = Logger.getLogger(FileNavigatorImpl.class.getName());

    /**
     * Runs the recursive filesystem listener registrations off the FX thread, one at a time, in the
     * order the projects were opened. Daemon, so it never keeps the application alive.
     */
    private static final ExecutorService LISTENER_INSTALLS = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "FileNavigator-listeners");
        thread.setDaemon(true);
        return thread;
    });

    public FileNavigatorImpl() {
    }

    @Override
    protected TreeItem<FileObject> findItem(FileObject file) {
        for (RootState state : rootStates()) {
            TreeItem<FileObject> item = state.item();
            if (item == null) {
                continue;
            }
            TreeItem<FileObject> found = findUnder(item, file);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Override
    public String getTitle() {
        return "Files";
    }

    @Override
    public String getDescription() {
        return NbBundle.getMessage(FileNavigatorImpl.class, "Navigator.description.physical");
    }

    /** Expands the folder nodes leading to {@code file} and returns its node, or {@code null}. */
    private TreeItem<FileObject> findUnder(TreeItem<FileObject> parent, FileObject file) {
        FileObject value = parent.getValue();
        if (value == null || file == null) {
            return null;
        }
        if (value.equals(file)) {
            return parent;
        }
        if (!FileUtil.isParentOf(value, file)) {
            return null;
        }
        if (!parent.isExpanded()) {
            parent.setExpanded(true);
        }
        for (TreeItem<FileObject> child : new ArrayList<>(parent.getChildren())) {
            TreeItem<FileObject> result = findUnder(child, file);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    @Override
    public void addProject(Lookup context) {
        LOG.info("Show Fileexplorer");
        FileObject fileObject = context.lookup(FileObject.class);
        if (fileObject == null) {
            throw new IllegalArgumentException("Can't render filetree without fileobject");
        }
        if (view == null) {
            view = createTreeView();
        }
        RootState state = addRootState(fileObject);
        if (state == null) {
            LOG.warning("Can't show a project without a path: " + fileObject);
            return;
        }
        if (state.item() != null) {
            LOG.info("Project already shown in the Files view: " + fileObject.getPath());
            return;
        }
        installFileChangeListenerAsync(state, FileUtil.toFile(fileObject));
        TreeItem<FileObject> rootItem = new FileTreeItem(fileObject);
        rootItem.setExpanded(true);
        setRootItem(state, rootItem);
        applyPendingExpanded(state);

        LOG.info("View created");

        view.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                TreeItem<FileObject> selectedItem = view.getSelectionModel().getSelectedItem();
                LOG.info("ACTION and selectedItem = " + selectedItem);
                if (selectedItem == null) {
                    return;
                }
                FileObject selectedFileObject = selectedItem.getValue();
                PackageScanner.openFile(selectedFileObject, NavigatorIcons.getFileIconName(selectedFileObject));
            }
        });
    }

    /**
     * Registers {@code state}'s recursive filesystem listener on a background thread. Registering it
     * walks the whole project - seconds on a deep hierarchy, as {@code FileUtil.addRecursiveListener}
     * warns - and {@link #addProject} runs on the FX thread, so doing it inline freezes the UI while
     * a project opens. The project may be closed before the registration runs; installing it then is
     * a no-op, as {@code installFileChangeListener} skips a state that is no longer the live one.
     */
    private void installFileChangeListenerAsync(RootState state, File root) {
        LISTENER_INSTALLS.execute(() -> {
            installFileChangeListener(state, root);
            // The tree was built before the listener was watching, so anything that changed in the
            // meantime produced no event: the nodes already built are rebuilt once, or they would
            // stay stale until an unrelated change refreshed their folder.
            Platform.runLater(() -> {
                if (state.item() instanceof FileTreeItem item) {
                    item.refreshBuiltSubtree();
                }
            });
        });
    }

    @Override
    public Node getView() {
        LOG.info("View asked, return " + view);
        if (view == null) {
            view = createTreeView();
        }
        return view;
    }

    @Override
    public List<String> getExpandedPaths(FileObject root) {
        List<String> result = new ArrayList<>();
        RootState state = rootState(root);
        if (state != null && state.item() != null) {
            collectExpanded(state.item(), result);
        }
        return result;
    }

    /** Collects the file paths of all currently expanded folder nodes, depth-first. */
    private void collectExpanded(TreeItem<FileObject> item, List<String> out) {
        if (!item.isExpanded()) {
            return;
        }
        FileObject fo = item.getValue();
        if (fo != null && fo.isFolder()) {
            File file = FileUtil.toFile(fo);
            if (file != null) {
                out.add(file.getPath());
            }
        }
        for (TreeItem<FileObject> child : item.getChildren()) {
            collectExpanded(child, out);
        }
    }

    /** Re-expands the folders recorded by {@code restoreExpandedPaths}, building nodes lazily. */
    private void applyPendingExpanded(RootState state) {
        List<String> paths = state.takePendingExpanded();
        if (paths == null || state.item() == null) {
            return;
        }
        for (String path : paths) {
            File file = new File(path);
            // skip anything that is not one of this view's own absolute paths, and normalize before resolving
            if (!file.isAbsolute()) {
                continue;
            }
            FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(file));
            if (fo == null || !fo.isFolder()) {
                continue;
            }
            // Paths belonging to another open project simply do not resolve under this root.
            TreeItem<FileObject> item = findUnder(state.item(), fo);
            if (item != null) {
                item.setExpanded(true);
            }
        }
    }

    /**
     * Creates the recursive filesystem listener that reflects external and internal changes
     * (create/delete/rename) live in the Files tree.
     */
    @Override
    protected FileChangeListener createFileChangeListener(RootState state) {
        return new FileChangeListener() {
            @Override
            public void fileFolderCreated(FileEvent fe) {
                refreshParentOf(fe.getFile());
            }

            @Override
            public void fileDataCreated(FileEvent fe) {
                refreshParentOf(fe.getFile());
            }

            @Override
            public void fileChanged(FileEvent fe) {
            }

            @Override
            public void fileDeleted(FileEvent fe) {
                refreshParentOf(fe.getFile());
            }

            @Override
            public void fileRenamed(FileRenameEvent fre) {
                refreshParentOf(fre.getFile());
            }

            @Override
            public void fileAttributeChanged(FileAttributeEvent fae) {
            }
        };
    }

    /** Rebuilds the tree node backing {@code file}'s parent folder, reflecting the change. */
    private void refreshParentOf(FileObject file) {
        if (file == null || view == null) {
            return;
        }
        FileObject parent = file.getParent();
        if (parent == null) {
            return;
        }
        Platform.runLater(() -> {
            for (RootState state : rootStates()) {
                if (state.item() instanceof FileTreeItem root) {
                    FileTreeItem parentItem = root.findBuilt(parent);
                    if (parentItem != null) {
                        parentItem.refreshChildren();
                        return;
                    }
                }
            }
        });
    }

    private TreeView<FileObject> createTreeView() {
        TreeView<FileObject> treeView = new TreeView<>();
        // The root is a hidden holder: its children are the open projects' folders.
        treeView.setShowRoot(false);
        treeView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        treeView.setCellFactory(tv -> new FileTreeCell(this::selectMovedFiles));
        // The tree handles file drops itself (see TreeCellDnD), so the tab pane holding it, which
        // otherwise opens dropped files in an editor, leaves them alone.
        FileDragAndDrop.markDropTarget(treeView);
        treeView.getStylesheets().add(getClass().getResource("fileexplorer.css").toExternalForm());
        // Keep the focus indicator on the selected item's live row (see syncFocusToSelection).
        treeView.getSelectionModel().selectedItemProperty().addListener((_, _, item) -> {
            // Selecting anything inside a project makes that project the selected one - unless the
            // selection is one the session is restoring, which must not steal the selection.
            if (!isRestoringSelection()) {
                Projects.selectOwnerOf(item == null ? null : item.getValue());
            }
            TreeNav.syncFocusToSelection(treeView);
        });
        trackSelectedProject(treeView);
        return treeView;
    }

}
