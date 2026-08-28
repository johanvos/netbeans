package com.gluonhq.netbeans.nbfx.navigator.utils;

import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import com.gluonhq.netbeans.nbfx.api.ContentManager;
import com.gluonhq.netbeans.nbfx.api.FileTypes;
import com.gluonhq.netbeans.nbfx.navigator.ProjectEntry;
import org.netbeans.api.queries.VisibilityQuery;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;

/**
 * Scans the project filesystem for the navigator tree: discovers packages, classifies folders as
 * empty/visible, decides which files are ignored or openable, opens openable files in the editor,
 * and resolves package/module display names.
 */
public final class PackageScanner {

    private static final Logger LOG = Logger.getLogger(PackageScanner.class.getName());
    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("com.gluonhq.netbeans.nbfx.navigator.utils.Bundle");

    private PackageScanner() {}

    /** Returns {@code true} for files that should never be shown in the project tree. */
    public static boolean isIgnored(FileObject fileObject) {
        return fileObject != null && ".DS_Store".equals(fileObject.getNameExt());
    }

    /** Opens the entry's file in the editor. */
    public static void openFile(ProjectEntry entry) {
        if (entry == null || !entry.isFileObject()) {
            return;
        }
        openFile(entry.getFileObject(), entry.getIconName());
    }

    /** Opens the file in the editor. */
    public static void openFile(FileObject fo, String iconName) {
        if (!isOpenable(fo)) {
            return;
        }
        ContentManager contentManager = Lookup.getDefault().lookup(ContentManager.class);
        if (contentManager != null) {
            contentManager.openFile(fo, NavigatorIcons.createIconView(fo, iconName));
        }
    }

    /**
     * Whether {@code fo} is a file that can be opened in the code editor. Folders and known
     * binary/archive files are not openable.
     *
     * @see FileTypes#isOpenable(FileObject)
     */
    public static boolean isOpenable(FileObject fo) {
        return FileTypes.isOpenable(fo);
    }

    public static List<FileObject> getPackages(FileObject sourceRoot) {
        return scanFolder(sourceRoot);
    }

    private static List<FileObject> scanFolder(FileObject folder) {
        List<FileObject> answer = new LinkedList<>();
        for (FileObject child: folder.getChildren()) {
            if (child.isFolder()) {
                answer.addAll(scanFolder(child));
                if (hasVisibleFiles(child)) {
                    answer.add(child);
                } else if (isEmptyLeafPackage(child)) {
                    // An empty package with no sub-packages: shown with the empty-package icon,
                    // mirroring the NetBeans IDE (e.g. after the last file is moved out of it).
                    answer.add(child);
                }
            }
        }
        return answer;
    }

    /** Whether {@code folder} has no sub-folders and no visible files (an empty leaf package). */
    private static boolean isEmptyLeafPackage(FileObject folder) {
        for (FileObject kid : folder.getChildren()) {
            if (kid.isFolder()) {
                return false;
            }
            if (VisibilityQuery.getDefault().isVisible(kid)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasVisibleFiles(FileObject folder) {
        for (FileObject child : folder.getChildren()) {
            if (!child.isFolder() && VisibilityQuery.getDefault().isVisible(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calculate the package name for a folder relative to a source root
     */
    public static String getPackageNameRelativeTo(FileObject root, FileObject pkg) {
        try {
            Path relative = FileUtil.toPath(root).relativize(FileUtil.toPath(pkg));
            return relative.toString().replace(relative.getFileSystem().getSeparator(), ".");
        } catch (Exception e) {
            LOG.fine("Exception calculating package name: " + e.getMessage());
            return null;
        }
    }

    /**
     * Check whether a package is empty (devoid of files except for subpackages).
     */
    static boolean isEmpty(FileObject fo) {
        FileObject[] kids = fo.getChildren();
        for (FileObject kid : kids) {
            if (!kid.isFolder() && VisibilityQuery.getDefault().isVisible(kid)) {
                return false;
            } else if (VisibilityQuery.getDefault().isVisible(kid) && !isEmpty(kid)) {
                return false;
            }
        }
        return true;
    }

    /** Public wrapper of {@link #isEmpty(FileObject)}: whether a package folder has no visible files. */
    public static boolean isPackageEmpty(FileObject fo) {
        return fo != null && isEmpty(fo);
    }

    public static String getDefaultPackageName() {
        return BUNDLE.getString("DefaultPackageName");
    }

    public static String getDefaultModulesName(boolean isMaven) {
        return BUNDLE.getString(isMaven ? "DefaultModulesName" : "DefaultSubprojectsName");
    }
}
