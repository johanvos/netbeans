package com.gluonhq.netbeans.nbfx.navigator.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.apache.maven.project.MavenProject;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectInformation;
import org.netbeans.api.project.ProjectManager;
import org.netbeans.modules.maven.api.NbMavenProject;
import org.openide.filesystems.FileObject;
import org.openide.util.Exceptions;

/**
 * Detects the build system of a NetBeans {@link Project} (Maven/Gradle/Ant/other), resolves its
 * display name, and discovers its subprojects/modules.
 */
public final class ProjectKinds {

    private static final Logger LOG = Logger.getLogger(ProjectKinds.class.getName());

    public enum ProjectKind {
        MAVEN,
        GRADLE,
        ANT,
        OTHER
    }

    private ProjectKinds() {}

    public static ProjectKind detectProjectKind(Project project) {
        FileObject projectDir = project.getProjectDirectory();
        ProjectManager.Result result = ProjectManager.getDefault().isProject2(projectDir);
        if (result != null) {
            LOG.info("ProjectManager.result: " + result.getProjectType());
        }
        // check Maven-based using lookup, ProjectManager API or presence of pom.xml
        if (project.getLookup().lookup(NbMavenProject.class) != null) {
            return ProjectKind.MAVEN;
        } else if (result != null) {
            if ("org-netbeans-modules-maven".equals(result.getProjectType())) {
                return ProjectKind.MAVEN;
            } else {
                FileObject pom = projectDir.getFileObject("pom.xml");
                if (pom != null && pom.isData()) {
                    return ProjectKind.MAVEN;
                }
            }
        }

        // Check Gradle-based using ProjectManager API
        if (result != null) {
            if ("org-netbeans-modules-gradle".equals(result.getProjectType())) {
                return ProjectKind.GRADLE;
            }
        }
        // Fallback: check for gradle files
        if (projectDir.getFileObject("build.gradle") != null ||
                projectDir.getFileObject("build.gradle.kts") != null ||
                projectDir.getFileObject("settings.gradle") != null ||
                projectDir.getFileObject("settings.gradle.kts") != null) {
            return ProjectKind.GRADLE;
        }

        // Check Ant-based
        FileObject nbproject = projectDir.getFileObject("nbproject");
        if (nbproject != null) {
            FileObject projectXml = nbproject.getFileObject("project.xml");
            if (projectXml != null && projectXml.isData()) {
                return ProjectKind.ANT;
            }
        }

        return ProjectKind.OTHER;
    }

    public static String getProjectName(Project project, ProjectKind kind) {
        String name = null;
        if (kind == ProjectKind.MAVEN) {
            NbMavenProject nbMaven = project.getLookup().lookup(NbMavenProject.class);
            if (nbMaven == null || nbMaven.getMavenProject().getName() == null) {
                name = project.getProjectDirectory().getName();
            } else {
                name = nbMaven.getMavenProject().getName();
            }
        }
        if (name == null) {
            ProjectInformation info = project.getLookup().lookup(ProjectInformation.class);
            name = info != null ? info.getDisplayName() : project.getProjectDirectory().getNameExt();
        }
        return name;
    }

    public static List<Project> getSubprojects(Project project, ProjectKind kind) {
        return switch (kind) {
            case MAVEN -> findMavenSubprojects(project);
            case GRADLE -> findGradleSubprojects(project);
            case ANT -> findAntSubprojects(project);
            case OTHER -> List.of();
        };
    }

    private static List<Project> findMavenSubprojects(Project root) {
        NbMavenProject nbMaven = root.getLookup().lookup(NbMavenProject.class);
        if (nbMaven == null) {
            return List.of();
        }
        MavenProject mp = nbMaven.getMavenProject();
        List<Project> result = new ArrayList<>();
        for (String module : mp.getModules()) {
            String relPath = module.replace("\\", "/");
            FileObject moduleDir = root.getProjectDirectory().getFileObject(relPath);
            if (moduleDir == null) {
                continue;
            }
            try {
                Project sub = ProjectManager.getDefault().findProject(moduleDir);
                if (sub != null) {
                    result.add(sub);
                }
            } catch (IOException ex) {
                Exceptions.printStackTrace(ex);
            }
        }
        return result;
    }

    private static List<Project> findGradleSubprojects(Project root) {
        // Gradle subproject discovery would require gradle-kit or manual parsing
        // For now, just return empty list
        LOG.fine("Gradle subproject discovery not yet implemented");
        return List.of();
    }

    private static List<Project> findAntSubprojects(Project root) {
        List<Project> result = new ArrayList<>();
        findAntSubprojectsRecursive(root.getProjectDirectory(), result);
        return result;
    }

    private static void findAntSubprojectsRecursive(FileObject dir, List<Project> result) {
        for (FileObject child : dir.getChildren()) {
            if (!child.isFolder()) {
                continue;
            }
            FileObject nbProject = child.getFileObject("nbproject/project.xml");
            if (nbProject != null) {
                try {
                    Project sub = ProjectManager.getDefault().findProject(child);
                    if (sub != null) {
                        result.add(sub);
                        // Continue searching in subdirectories for nested subprojects
                        findAntSubprojectsRecursive(child, result);
                    }
                } catch (IOException ex) {
                    Exceptions.printStackTrace(ex);
                }
            }
        }
    }
}
