package com.gluonhq.netbeans.nbfx.navigator;

import org.openide.filesystems.FileObject;

/**
 *
 * @author johan
 */
public class ProjectEntry {
    public enum Type {
        NAME,
        MODULES,
        GROUP,
        PACKAGE,
        FILE
    }

    public enum BADGE {
        PACKAGE_BADGE,
        MODULES_BADGE,
        LIBRARIES_BADGE,
        OTHERS_BADGE,
        PROJECTFILES_BADGE,
        NO_BADGE
    }

    private final FileObject fileObject;
    private final String name;
    private String iconName;

    private final Type type;
    private final BADGE badge;
    
    public ProjectEntry(FileObject fileObject, Type type) {
        this.fileObject = fileObject;
        this.name = fileObject.getNameExt();
        this.type = type;
        this.badge = BADGE.NO_BADGE;
    }

    public ProjectEntry(FileObject fileObject, String name, Type type) {
        this(fileObject, name, type, BADGE.NO_BADGE);
    }

    public ProjectEntry(FileObject fileObject, String name, Type type, BADGE badge) {
        this(fileObject, name, type, badge, null);
    }

    public ProjectEntry(FileObject fileObject, String name, Type type, BADGE badge, String iconName) {
        this.fileObject = fileObject;
        this.name = name;
        this.type = type;
        this.iconName = iconName;
        this.badge = badge;
    }

    public boolean isFolder() {
        if (fileObject != null) return fileObject.isFolder();
        return true;
    }

    public boolean isFileObject() {
        return (this.fileObject != null);
    }

    public FileObject getFileObject() {
        return this.fileObject;
    }

    public String getName() {
        return this.name;
    }

    public Type getType() {
        return this.type;
    }

    public String getIconName() {
        return this.iconName;
    }

    public BADGE getBadge() {
        return badge;
    }
}
