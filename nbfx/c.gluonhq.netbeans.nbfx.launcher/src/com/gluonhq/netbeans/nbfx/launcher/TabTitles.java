package com.gluonhq.netbeans.nbfx.launcher;

import com.gluonhq.netbeans.nbfx.api.EditorDocument;
import com.gluonhq.netbeans.nbfx.api.OpenProject;
import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * Computes the text shown on an editor tab and in its tooltip.
 * <p>
 * With several projects open, two of them may hold files with the same name; such tabs are
 * disambiguated with the name of the project they belong to. The tooltip always names the owning
 * project and the file's path within it.
 */
final class TabTitles {

    /** Separates the project name from the file path in a tab tooltip. */
    private static final String TOOLTIP_SEPARATOR = " \u2014 ";

    private TabTitles() {
    }

    /**
     * The title for {@code document}'s tab: its {@link EditorDocument#getTitle() plain title},
     * suffixed with the owning project's name when another open document of a different project has
     * the same title.
     *
     * @param document     the document whose tab is being labelled
     * @param openDocuments every open document, including {@code document}
     */
    static String titleFor(EditorDocument document, List<? extends EditorDocument> openDocuments) {
        String title = document.getTitle();
        String projectPath = document.getProjectPath();
        String projectName = projectNameOf(projectPath);
        if (projectName == null || openDocuments == null) {
            return title;
        }
        boolean ambiguous = openDocuments.stream().anyMatch(other -> other != document
                && title.equals(other.getTitle())
                && !Objects.equals(projectPath, other.getProjectPath()));
        return ambiguous ? title + " [" + projectName + "]" : title;
    }

    /**
     * The tooltip for {@code document}'s tab: {@code <project> — <path within the project>}, or the
     * file's full path when it belongs to no open project.
     */
    static String tooltipFor(EditorDocument document) {
        String filePath = OpenProject.pathOf(document.getFileObject());
        String projectPath = document.getProjectPath();
        String projectName = projectNameOf(projectPath);
        if (projectName == null || filePath == null) {
            return filePath != null ? filePath : document.getTitle();
        }
        String relative = relativize(filePath, projectPath);
        return relative == null ? filePath : projectName + TOOLTIP_SEPARATOR + relative;
    }

    /** The name of the project rooted at {@code projectPath}, or {@code null} if there is none. */
    private static String projectNameOf(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return null;
        }
        String name = new File(projectPath).getName();
        return name.isEmpty() ? null : name;
    }

    /** {@code filePath} relative to {@code projectPath}, or {@code null} when it is not below it. */
    private static String relativize(String filePath, String projectPath) {
        String prefix = projectPath.endsWith(File.separator) ? projectPath : projectPath + File.separator;
        return filePath.startsWith(prefix) ? filePath.substring(prefix.length()) : null;
    }
}
