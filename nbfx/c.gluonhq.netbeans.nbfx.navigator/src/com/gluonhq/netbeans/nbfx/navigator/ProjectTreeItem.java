package com.gluonhq.netbeans.nbfx.navigator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import com.gluonhq.netbeans.nbfx.navigator.utils.NavigatorIcons;
import com.gluonhq.netbeans.nbfx.navigator.utils.PackageScanner;
import com.gluonhq.netbeans.nbfx.navigator.utils.TreeNav;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.RequestProcessor;

/**
 *
 * @author johan
 */
public class ProjectTreeItem extends TreeItem<ProjectEntry> {

    private static final Logger LOG = Logger.getLogger(ProjectTreeItem.class.getName());

    /** Single-threaded so lazily expanded nodes are built in the order they were expanded. */
    private static final RequestProcessor NODE_LOADER =
            new RequestProcessor("ProjectTreeItem-lazy-nodes", 1, true, true);

    private final boolean isFolder;

    public ProjectTreeItem(ProjectEntry projectEntry) {
        super(projectEntry);
        this.isFolder = projectEntry.isFolder();
        FileObject fileObject = projectEntry.getFileObject();
        if (fileObject != null) {
            if (projectEntry.getType() == ProjectEntry.Type.GROUP || projectEntry.getType() == ProjectEntry.Type.PACKAGE) {
                // Building the children scans the source root on disk (recursively, for a group), so
                // it runs off the FX thread; only attaching the built nodes goes back to it. Marking
                // main classes is scheduled after the children are attached, since it walks them.
                TreeNav.setExpandedInvalidationListener(this, () ->
                    NODE_LOADER.post(() -> {
                        ObservableList<ProjectTreeItem> children = buildChildren(this);
                        children.sort(treeItemComparator);
                        Platform.runLater(() -> {
                            getChildren().setAll(children);
                            TreeNav.checkMainClasses(this);
                        });
                    }));
            }
            if (projectEntry.getType() == ProjectEntry.Type.MODULES || projectEntry.getType() == ProjectEntry.Type.GROUP) {
                expandedProperty().subscribe(expanded -> {
                    String iconName = expanded ? NavigatorIcons.FOLDER_OPEN_ICON : NavigatorIcons.FOLDER_CLOSE_ICON;
                    if (projectEntry.getBadge() != ProjectEntry.BADGE.NO_BADGE) {
                        iconName += "-" + projectEntry.getBadge().name().toLowerCase().replace("_", "-");
                    }
                    setGraphic(NavigatorIcons.createIconView(fileObject, iconName + ".png"));
                });
            } else {
                this.setGraphic(NavigatorIcons.createIconView(fileObject, projectEntry.getIconName()));
            }
        }
    }

    @Override
    public boolean isLeaf() {
        if (!isFolder) {
            return true;
        }
        ProjectEntry entry = getValue();
        if (entry != null && entry.getType() == ProjectEntry.Type.PACKAGE) {
            FileObject fo = entry.getFileObject();
            if (fo != null) {
                for (FileObject kid : fo.getChildren()) {
                    if (!kid.isFolder() && !PackageScanner.isIgnored(kid)) {
                        return false;
                    }
                }
                // An empty package (its sub-folders are shown as separate flat packages) is a leaf.
                return true;
            }
        }
        return false;
    }

    /** Recomputes and reapplies this node's icon (e.g. after a package becomes empty or non-empty). */
    void refreshIcon() {
        ProjectEntry entry = getValue();
        FileObject fo = entry == null ? null : entry.getFileObject();
        if (fo == null) {
            return;
        }
        NavigatorIcons.invalidateIconCache(fo, entry.getIconName());
        setGraphic(NavigatorIcons.createIconView(fo, entry.getIconName()));
    }

    /**
     * Reconciles this source-group node's package children with the current on-disk layout: adds
     * nodes for packages that newly appeared and removes nodes for packages that no longer contain sources.
     * <p>
     * Called from file-change events, so the scan of the source root - which walks every folder under
     * it - runs off the FX thread and only the reconciliation it feeds is posted back.
     */
    public void syncPackages() {
        ProjectEntry entry = getValue();
        if (entry == null || entry.getType() != ProjectEntry.Type.GROUP || !entry.isFileObject()) {
            return;
        }
        FileObject root = entry.getFileObject();
        NODE_LOADER.post(() -> {
            List<FileObject> expected = PackageScanner.getPackages(root);
            boolean hasModuleInfo = Arrays.stream(root.getChildren())
                    .anyMatch(fo -> fo.getPath().endsWith("module-info.java"));
            Platform.runLater(() -> applyPackages(root, expected, hasModuleInfo));
        });
    }

    private void applyPackages(FileObject root, List<FileObject> expected, boolean hasModuleInfo) {
        String defaultName = PackageScanner.getDefaultPackageName();

        getChildren().removeIf(child -> {
            ProjectEntry ce = child.getValue();
            if (ce == null || ce.getType() != ProjectEntry.Type.PACKAGE) {
                return false;
            }
            if (defaultName.equals(ce.getName())) {
                return !hasModuleInfo;
            }
            FileObject fo = ce.getFileObject();
            return fo == null || !fo.isValid() || !expected.contains(fo);
        });

        Set<FileObject> present = new HashSet<>();
        boolean hasDefault = false;
        for (TreeItem<ProjectEntry> child : getChildren()) {
            ProjectEntry ce = child.getValue();
            if (ce != null && ce.getType() == ProjectEntry.Type.PACKAGE) {
                if (defaultName.equals(ce.getName())) {
                    hasDefault = true;
                } else if (ce.getFileObject() != null) {
                    present.add(ce.getFileObject());
                }
            }
        }

        List<ProjectTreeItem> toAdd = new ArrayList<>();
        for (FileObject pkg : expected) {
            if (!present.contains(pkg)) {
                String packageName = PackageScanner.getPackageNameRelativeTo(root, pkg);
                toAdd.add(new ProjectTreeItem(new ProjectEntry(pkg, packageName, ProjectEntry.Type.PACKAGE)));
            }
        }
        if (hasModuleInfo && !hasDefault) {
            toAdd.add(new ProjectTreeItem(new ProjectEntry(root, defaultName, ProjectEntry.Type.PACKAGE)));
        }
        if (!toAdd.isEmpty()) {
            getChildren().addAll(toAdd);
            getChildren().sort(treeItemComparator);
        }
        TreeNav.checkMainClasses(this);
    }

    /** Whether this node represents a package (its children are the package's files). */
    boolean isPackage() {
        ProjectEntry entry = getValue();
        return entry != null && entry.getType() == ProjectEntry.Type.PACKAGE;
    }

    private ObservableList<ProjectTreeItem> buildChildren(ProjectTreeItem parentItem) {
        ProjectEntry projectEntry = parentItem.getValue();
        if (!projectEntry.isFileObject()) {
            return FXCollections.emptyObservableList();
        }
        FileObject parent = projectEntry.getFileObject();
        if (projectEntry.getType() == ProjectEntry.Type.GROUP) {
            // NetBeans uses a flat structure for the Project view, where the source groups (Java/Tests) are shown as top-level nodes,
            // and the packages are shown as children of the source group.
            // Each package is represented as a folder, and the files are shown as children of the package, which doesn't
            // show inner packages.
            LOG.info("Building children for " + projectEntry.getName() + " of type " + projectEntry.getType() + " with source group");
            List<FileObject> packages = PackageScanner.getPackages(parent);
            List<ProjectTreeItem> childList = new ArrayList<>();
            for (FileObject fileObject : packages) {
                String packageName = PackageScanner.getPackageNameRelativeTo(parent, fileObject);
                ProjectEntry packageEntry = new ProjectEntry(fileObject, packageName, ProjectEntry.Type.PACKAGE);
                childList.add(new ProjectTreeItem(packageEntry));
            }
            if (Arrays.stream(parent.getChildren()).anyMatch(fo -> fo.getPath().endsWith("module-info.java"))) {
                ProjectEntry moduleInfoPackageEntry = new ProjectEntry(parent, PackageScanner.getDefaultPackageName(), ProjectEntry.Type.PACKAGE);
                ProjectTreeItem moduleItem = new ProjectTreeItem(moduleInfoPackageEntry);
                childList.add(moduleItem);
            }
            ObservableList<ProjectTreeItem> projectTreeItems = FXCollections.observableArrayList(childList);
            projectTreeItems.sort(treeItemComparator);
            return projectTreeItems;
        } else if (projectEntry.getType() == ProjectEntry.Type.PACKAGE) {
            LOG.info("Building children for " + projectEntry.getName() + " of type " + projectEntry.getType() + " with file object");
            FileObject[] children = parent.getChildren();
            if (children != null) {
                ObservableList<ProjectTreeItem> answer = FXCollections.observableArrayList();
                for (FileObject child : children) {
                    Path path = FileUtil.toPath(child);
                    if (!child.isFolder() && !PackageScanner.isIgnored(child)) {
                        ProjectEntry classFileEntry = new ProjectEntry(child, path.getFileName().toString(), ProjectEntry.Type.FILE,
                                ProjectEntry.BADGE.NO_BADGE, NavigatorIcons.getFileIconName(child));
                        answer.add(new ProjectTreeItem(classFileEntry));
                    }
                }
                return answer;
            }
        }
        return FXCollections.emptyObservableList();
    }

    // Siblings in this flat view always share a type (a group's children are all packages, a
    // package's children are all files), so order by type first and then name. Leaf-ness is not
    // used as a key: an empty package is a leaf but must still sort alphabetically among packages.
    private static final Comparator<TreeItem<ProjectEntry>> treeItemComparator =
            TreeNav.comparator(item -> item.getValue().getType().ordinal(), item -> item.getValue().getName());
}
