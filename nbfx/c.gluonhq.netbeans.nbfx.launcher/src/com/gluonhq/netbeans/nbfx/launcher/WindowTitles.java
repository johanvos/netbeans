package com.gluonhq.netbeans.nbfx.launcher;

import com.gluonhq.netbeans.nbfx.api.OpenProject;

/**
 * The main window's title. With several projects open it names the selected one - the project that
 * project-scoped actions apply to - so the window always says which context the user is in.
 */
final class WindowTitles {

    static final String APP_NAME = "NetBeansFX";

    private static final String SEPARATOR = " \u2014 "; // em dash

    private WindowTitles() {
    }

    /**
     * The window title for {@code project}: {@code "<project> — NetBeansFX"}, falling back to the
     * plain application name when no project is selected or it has no name.
     */
    static String of(OpenProject project) {
        String name = project == null ? null : project.getDisplayName();
        return name == null || name.isBlank() ? APP_NAME : name + SEPARATOR + APP_NAME;
    }
}
