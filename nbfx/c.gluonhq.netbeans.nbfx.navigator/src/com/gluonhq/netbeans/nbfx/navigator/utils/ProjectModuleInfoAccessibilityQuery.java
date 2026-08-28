package com.gluonhq.netbeans.nbfx.navigator.utils;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DirectiveTree;
import com.sun.source.tree.ExportsTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.event.ChangeListener;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.queries.AccessibilityQuery;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.spi.java.queries.AccessibilityQueryImplementation;
import org.netbeans.spi.java.queries.AccessibilityQueryImplementation2;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.ChangeSupport;
import org.openide.util.lookup.ServiceProvider;

/**
 * Global fallback package accessibility provider based on module-info.java exports.
 * Similar to NetBeans' GenericModuleInfoAccessibilityQuery, but simpler.
 * Registered globally; checks for project-specific implementations first.
 */
@ServiceProvider(service = AccessibilityQueryImplementation2.class, position = 100)
public final class ProjectModuleInfoAccessibilityQuery implements AccessibilityQueryImplementation2 {

    private static final Logger LOG = Logger.getLogger(ProjectModuleInfoAccessibilityQuery.class.getName());

    /**
     * Parsed {@code exports} directives, keyed by the URI of the {@code module-info.java} they
     * come from. Several projects are open at once and each has its own {@code module-info.java},
     * so the key must identify the file across filesystems - a path alone would not, as two
     * projects can hold the same relative path. Entries are dropped when their project is closed
     * (see {@link #evictProject}).
     */
    private static final Map<String, CacheEntry> exportsCache = new HashMap<>();

    // ...existing code...
    @Override
    public Result isPubliclyAccessible(FileObject folder) {
        if (folder == null || !folder.isFolder()) {
            return null;
        }

        // Check if the owning project has its own AccessibilityQueryImplementation2
        Project owner = FileOwnerQuery.getOwner(folder);
        if (owner != null) {
            AccessibilityQueryImplementation2 projectImpl =
                    owner.getLookup().lookup(AccessibilityQueryImplementation2.class);
            AccessibilityQueryImplementation projectImpl1 =
                    owner.getLookup().lookup(AccessibilityQueryImplementation.class);
            if (projectImpl != null || projectImpl1 != null) {
                // Project has its own implementation, let it handle it
                return null;
            }
        }

        // Only handle actual package folders (must have a SOURCE classpath)
        ClassPath sourcePath = ClassPath.getClassPath(folder, ClassPath.SOURCE);
        if (sourcePath == null) {
            return null;
        }

        // Look for module-info.java at the SOURCE ROOT
        // Get the source root that contains this folder
        FileObject sourceRoot = sourcePath.findOwnerRoot(folder);
        if (sourceRoot == null) {
            return null;
        }

        FileObject moduleInfo = sourceRoot.getFileObject("module-info.java");
        if (moduleInfo == null) {
            // No module-info.java in this source path - not a modular project
            return null;
        }

        Set<String> exported = getExportedPackages(moduleInfo);
        if (exported == null) {
            return null;
        }

        String packageName = sourcePath.getResourceName(folder);
        if (packageName == null) {
            return null;
        }
        packageName = packageName.replace('/', '.');

        AccessibilityQuery.Accessibility accessibility =
                exported.contains(packageName)
                        ? AccessibilityQuery.Accessibility.EXPORTED
                        : AccessibilityQuery.Accessibility.PRIVATE;

        LOG.info("Module-info accessibility: " + packageName + " -> " + accessibility);
        return new FixedResult(accessibility);
    }


    // ...existing code...

    /**
     * Forgets everything cached for the project rooted at {@code root}, so closing and reopening a
     * project never serves the exports of a stale {@code module-info.java}, and a long IDE session
     * with many projects does not keep growing the cache.
     */
    public static void evictProject(Path root) {
        // Built the same way the cache keys are, from the file rather than from the Path: the two
        // spell the same folder differently ("file:/x" vs "file:///x").
        evictPrefix(root == null ? null : FileUtil.normalizeFile(root.toFile()).toURI().toString());
    }

    public static void evictProject(FileObject root) {
        evictPrefix(root == null ? null : cacheKey(root));
    }

    private static void evictPrefix(String rootKey) {
        if (rootKey == null) {
            return;
        }
        String prefix = rootKey.endsWith("/") ? rootKey : rootKey + "/";
        synchronized (exportsCache) {
            exportsCache.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    /** Identifies a file across filesystems, so two projects never share a cache entry. */
    private static String cacheKey(FileObject file) {
        return file.toURI().toString();
    }

    /** Entries currently cached; for tests. */
    static int cacheSize() {
        synchronized (exportsCache) {
            return exportsCache.size();
        }
    }

    static Set<String> exportedPackages(FileObject moduleInfo) {
        return new ProjectModuleInfoAccessibilityQuery().getExportedPackages(moduleInfo);
    }

    private Set<String> getExportedPackages(FileObject moduleInfo) {
        String cacheKey = cacheKey(moduleInfo);
        long stamp = moduleInfo.lastModified().getTime();

        synchronized (exportsCache) {
            CacheEntry entry = exportsCache.get(cacheKey);
            if (entry != null && entry.timestamp == stamp) {
                return entry.exported;
            }
        }

        Set<String> exported = new HashSet<>();
        try {
            String source = moduleInfo.asText();
            JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(
                    null,
                    null,
                    null,
                    null,
                    null,
                    Collections.singleton(new InMemoryJavaSource(moduleInfo.toURI(), source)));

            CompilationUnitTree cu = task.parse().iterator().next();
            ModuleTree moduleTree = cu.getModule();
            if (moduleTree != null) {
                for (DirectiveTree directive : moduleTree.getDirectives()) {
                    if (directive.getKind() == Tree.Kind.EXPORTS) {
                        ExportsTree exportsTree = (ExportsTree) directive;
                        if (exportsTree.getModuleNames() == null || exportsTree.getModuleNames().isEmpty()) {
                            exported.add(exportsTree.getPackageName().toString());
                        }
                    }
                }
            }
        } catch (IOException ex) {
            LOG.log(Level.FINE, "Cannot parse module-info.java for accessibility", ex);
            return null;
        }

        synchronized (exportsCache) {
            exportsCache.put(cacheKey, new CacheEntry(stamp, exported));
        }
        return exported;
    }

    private static final class CacheEntry {
        final long timestamp;
        final Set<String> exported;

        CacheEntry(long timestamp, Set<String> exported) {
            this.timestamp = timestamp;
            this.exported = exported;
        }
    }

    private static final class FixedResult implements Result {
        private final AccessibilityQuery.Accessibility accessibility;
        private final ChangeSupport changeSupport = new ChangeSupport(this);

        FixedResult(AccessibilityQuery.Accessibility accessibility) {
            this.accessibility = accessibility;
        }

        @Override
        public AccessibilityQuery.Accessibility getAccessibility() {
            return accessibility;
        }

        @Override
        public void addChangeListener(ChangeListener listener) {
            changeSupport.addChangeListener(listener);
        }

        @Override
        public void removeChangeListener(ChangeListener listener) {
            changeSupport.removeChangeListener(listener);
        }
    }

    private static final class InMemoryJavaSource extends SimpleJavaFileObject {
        private final String code;

        InMemoryJavaSource(URI uri, String code) {
            super(uri, Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
