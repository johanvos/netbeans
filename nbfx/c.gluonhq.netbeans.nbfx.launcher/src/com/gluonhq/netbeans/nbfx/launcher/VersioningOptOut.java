package com.gluonhq.netbeans.nbfx.launcher;

import java.io.File;
import java.util.Arrays;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Keeps the platform's version control systems out of the way, as NetBeansFX offers no VCS feature.
 * <p>
 * The application depends on whole platform clusters, so the git, mercurial and subversion modules
 * are all present and start working the moment a project is opened - scanning for repositories,
 * building a status cache, resolving ignored files - for an IDE that shows none of it. Beyond the
 * wasted work, that scanning asks who owns each file, which loads projects nobody opened: when a
 * project folder disappears, git resolves the deleted paths against the enclosing Maven aggregator,
 * whose constructor refreshes the filesystem and re-enters the project system
 * ({@code IllegalStateException: Attempt to call ProjectManager.findProject within the body of
 * ProjectFactory.loadProject}).
 * <p>
 * Every versioning system is required to leave a folder alone when
 * {@code VersioningSupport.isExcluded} says so, and that is driven by the
 * {@code versioning.unversionedFolders} system property, so naming the filesystem roots excludes
 * everything. To be removed when NetBeansFX gains VCS support.
 */
final class VersioningOptOut {

    private static final Logger LOG = Logger.getLogger(VersioningOptOut.class.getName());

    static final String UNVERSIONED_FOLDERS = "versioning.unversionedFolders";

    private VersioningOptOut() {
    }

    /** Excludes every filesystem root from version control, unless the property is already set. */
    static void apply() {
        apply(File.listRoots());
    }

    static void apply(File[] roots) {
        if (System.getProperty(UNVERSIONED_FOLDERS) != null) {
            // Started with an explicit list: whoever set it knows what they want scanned.
            return;
        }
        if (roots == null || roots.length == 0) {
            return;
        }
        // The platform splits this list on ';', on every operating system.
        String excluded = Arrays.stream(roots)
                .map(File::getAbsolutePath)
                .collect(Collectors.joining(";"));
        System.setProperty(UNVERSIONED_FOLDERS, excluded);
        LOG.fine(() -> "Version control disabled for " + excluded);
    }
}
