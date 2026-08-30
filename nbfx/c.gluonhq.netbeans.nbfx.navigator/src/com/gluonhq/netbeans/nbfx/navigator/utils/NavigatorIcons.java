package com.gluonhq.netbeans.nbfx.navigator.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.netbeans.api.java.queries.AccessibilityQuery;
import org.netbeans.api.java.queries.AccessibilityQuery.Accessibility;
import org.netbeans.api.project.Project;
import org.openide.filesystems.FileObject;
import org.openide.util.Exceptions;
import org.openide.util.RequestProcessor;
import org.netbeans.modules.maven.api.NbMavenProject;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

/**
 * Loads, caches and names the icons shown in the navigator trees. Owns the icon cache and the
 * background {@link org.openide.util.RequestProcessor} used to load images off the JavaFX thread,
 * and resolves file/project/menu icon names.
 */
public final class NavigatorIcons {

    public static final String FOLDER_OPEN_ICON = "defaultFolderOpen";
    public static final String FOLDER_CLOSE_ICON = "defaultFolder";
    public static final String FILE_CLASS_ICON = "class.png";
    public static final String FILE_MAIN_CLASS_ICON = "main-class.png";

    private static final Logger LOG = Logger.getLogger(NavigatorIcons.class.getName());
    private static final String PACKAGE = NavigatorIcons.class.getResource("package.png").toExternalForm();
    private static final String PACKAGE_EMPTY = NavigatorIcons.class.getResource("packageEmpty.png").toExternalForm();
    private static final String PACKAGE_PRIVATE = NavigatorIcons.class.getResource("packagePrivate.png").toExternalForm();
    private static final String PACKAGE_PUBLIC = NavigatorIcons.class.getResource("packagePublic.png").toExternalForm();
    private static final double ICON_SIZE = 16d;

    private static final Map<String, Image> iconCacheMap = new ConcurrentHashMap<>();
    private static final Map<String, CopyOnWriteArrayList<Consumer<Image>>> iconRequestsMap = new ConcurrentHashMap<>();
    private static final RequestProcessor ICON_RP =
            new RequestProcessor("ProjectViewUtils-icons", 2, true, true);

    private NavigatorIcons() {}

    public static String getProjectIconName(Project project, boolean isMaster, ProjectKinds.ProjectKind kind) {
        if (kind == ProjectKinds.ProjectKind.MAVEN) {
            if (isMaster) {
                return "Maven2Icon.png";
            } else {
                NbMavenProject nbMaven = project.getLookup().lookup(NbMavenProject.class);
                if (nbMaven != null) {
                    String packaging = nbMaven.getMavenProject().getPackaging();
                    if ("nbm".equals(packaging)) {
                        return "nbmicon.png";
                    } else if ("nbm-application".equals(packaging)) {
                        return "suiteicon.png";
                    }
                }
                return "jaricon.png";
            }
        } else if (kind == ProjectKinds.ProjectKind.GRADLE) {
            return "gradle.png";
        } else if (kind == ProjectKinds.ProjectKind.ANT) {
            return "jdk-project.png";
        }
        return null;
    }

    public static String getFileIconName(FileObject fileObject) {
        if (fileObject.isData()) {
            String ext = fileObject.getExt();
            if ("java".equalsIgnoreCase(ext)) {
                return "class.png";
            } else if ("class".equalsIgnoreCase(ext)) {
                return "clazz.png";
            } else if ("xml".equalsIgnoreCase(ext)) {
                if ("pom.xml".equalsIgnoreCase(fileObject.getNameExt()) || "settings.xml".equalsIgnoreCase(fileObject.getNameExt())) {
                    return "Maven2Icon.png";
                }
                return "xmlObject.png";
            } else if ("gradle".equalsIgnoreCase(ext) || "gradle.kts".equalsIgnoreCase(ext)) {
                return "gradle.png";
            } else if ("html".equalsIgnoreCase(ext)) {
                return "html.png";
            } else if ("fxml".equalsIgnoreCase(ext)) {
                return "fxmlObject.png";
            } else if ("json".equalsIgnoreCase(ext)) {
                return "json.png";
            } else if ("md".equalsIgnoreCase(ext)) {
                return "markdown.png";
            } else if ("css".equalsIgnoreCase(ext)) {
                return "style_sheet_16.png";
            } else if ("png".equalsIgnoreCase(ext) || "jpg".equalsIgnoreCase(ext) || "jpeg".equalsIgnoreCase(ext) || "gif".equalsIgnoreCase(ext) || "bmp".equalsIgnoreCase(ext)) {
                return "imageObject.png";
            } else if ("properties".equalsIgnoreCase(ext)) {
                return "propertiesObject.png";
            } else if ("jar".equalsIgnoreCase(ext)) {
                return "DependencyIcon.png";
            } else {
                return "filePlain.png";
            }
        }
        return null;
    }

    public static ImageView createIconView(FileObject fileObj, String iconName) {
        ImageView view = new ImageView();
        view.setFitWidth(ICON_SIZE);
        view.setFitHeight(ICON_SIZE);
        view.setPreserveRatio(true);
        try {
            getIcon(fileObj, iconName, view::setImage);
        } catch (Exception e) {
            LOG.warning("Error creating icon for " + fileObj.getName() + ": " + e);
        }
        return view;
    }

    /**
     * Maps a base icon resource name to its small "-16" menu variant (e.g. {@code foo.png} to
     * {@code foo-16.png}). Returns {@code null} for a {@code null} input. Used to render project
     * icons in the macOS system menu bar, which draws menu graphics at their intrinsic pixel size.
     */
    public static String toMenuIconName(String iconName) {
        if (iconName == null) {
            return null;
        }
        int dot = iconName.lastIndexOf('.');
        return dot < 0 ? iconName + "-16" : iconName.substring(0, dot) + "-16" + iconName.substring(dot);
    }

    /**
     * Load an icon for a given file object and icon name, using caching to avoid redundant loads.
     * The loaded icon will be provided to the onIcon consumer once ready, which may be called immediately
     * if the icon is already cached.
     * The icon loading is done in a background thread, but the onIcon consumer is always called
     * on the JavaFX Application Thread.
     * The cache key is based on the file object's path and the icon name, so different icons
     * for the same file can be cached separately.
     * The method ensures that if multiple requests for the same icon come in while it's still loading,
     * only one load is performed and all waiting consumers are notified when ready.
     * @param fileObj the file object for which to load the icon
     * @param iconName the name of the icon to load, or null to load a package icon based on the file object
     * @param onIcon a consumer that will be called with the loaded icon once ready
     */
    private static void getIcon(FileObject fileObj, String iconName, Consumer<Image> onIcon) {
        String cacheKey = fileObj.getPath() + (iconName != null ? "::" + iconName : "");
        Image cached = iconCacheMap.get(cacheKey);
        if (cached != null) {
            // If the image is already cached, use it immediately
            onIcon.accept(cached);
            return;
        }

        AtomicBoolean iconLoadRequired = new AtomicBoolean(false);
        CopyOnWriteArrayList<Consumer<Image>> consumers = iconRequestsMap.compute(cacheKey, (_, consumerList) -> {
            if (consumerList == null) {
                // No load for this icon yet, start one
                iconLoadRequired.set(true);
                return new CopyOnWriteArrayList<>();
            }
            return consumerList;
        });
        consumers.add(onIcon);
        if (!iconLoadRequired.get()) {
            // Load is already in progress for this icon, don't schedule another one.
            return;
        }

        // Schedule the icon loading in a background thread using the RequestProcessor,
        // to avoid blocking the JavaFX Application Thread.
        ICON_RP.post(() -> {
            // Load the icon.
            Image image = null;
            try {
                if (iconName != null) {
                    image = new Image(NavigatorIcons.class.getResource(iconName).toExternalForm());
                } else {
                    // If no iconName is provided, a package icon is used, for which the package accessibility
                    // of the file object is determined, which requires more time.
                    image = getPackageIcon(fileObj);
                }
            } catch (Exception e) {
                Exceptions.printStackTrace(e);
            }

            if (image != null) {
                // Once ready, cache the loaded image
                iconCacheMap.put(cacheKey, image);
                // and notify all waiting consumers on the JavaFX Application Thread
                CopyOnWriteArrayList<Consumer<Image>> consumerList = iconRequestsMap.remove(cacheKey);
                if (consumerList != null) {
                    Image finalImage = image;
                    Platform.runLater(() -> consumerList.forEach(c -> c.accept(finalImage)));
                }
            } else {
                iconRequestsMap.remove(cacheKey);
            }
        });
    }

    /**
     * Find the proper display icon for a package.
     * Uses AccessibilityQuery to determine if a package is public or private.
     * The external project's AccessibilityQueryImplementation2 (registered in its lookup)
     * will handle reading module-info.java exports if the project has one.
     * @param pkg the actual folder representing a package
     * @return an appropriate display icon for it
     */
    private static Image getPackageIcon(FileObject pkg) {
        if (PackageScanner.isEmpty(pkg)) {
            return new Image(PACKAGE_EMPTY);
        } else {
            Accessibility a = Accessibility.UNKNOWN;
            try {
                AccessibilityQuery.Result result = AccessibilityQuery.isPubliclyAccessible2(pkg);
                a = result.getAccessibility();
            } catch (Exception e) {
                LOG.fine("Exception getting accessibility: " + e.getMessage());
            }

            return switch (a) {
                case EXPORTED -> new Image(PACKAGE_PUBLIC);
                case PRIVATE -> new Image(PACKAGE_PRIVATE);
                case UNKNOWN -> new Image(PACKAGE);
            };
        }
    }

    /** Drops the cached icon for {@code fileObject}/{@code iconName} so it is recomputed on next request. */
    public static void invalidateIconCache(FileObject fileObject, String iconName) {
        if (fileObject == null) {
            return;
        }
        String cacheKey = fileObject.getPath() + (iconName != null ? "::" + iconName : "");
        iconCacheMap.remove(cacheKey);
    }
}
