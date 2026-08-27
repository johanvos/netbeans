package com.gluonhq.netbeans.nbfx.api;

import java.util.Locale;
import java.util.Set;

import org.openide.filesystems.FileObject;

/**
 * File classification shared by the navigator, the launcher and the editor: whether a file can be
 * opened in the code editor.
 */
public final class FileTypes {

    /** Binary/archive file extensions that cannot be opened in the code editor. */
    private static final Set<String> NON_OPENABLE_EXTENSIONS = Set.of(
            "jar", "nbm", "zip", "war", "ear", "tar", "gz", "tgz", "bz2", "xz", "rar", "7z",
            "class", "so", "dll", "dylib", "exe", "bin", "o", "a", "lib",
            "jpg", "jpeg", "png", "gif", "bmp", "ico", "icns",
            "pdf", "keystore", "jks");

    private FileTypes() {}

    /**
     * Whether {@code fo} is a file that can be opened in the code editor. Folders and known
     * binary/archive files (see {@link #NON_OPENABLE_EXTENSIONS}) are not openable.
     */
    public static boolean isOpenable(FileObject fo) {
        if (fo == null || !fo.isData()) {
            return false;
        }
        String ext = fo.getExt();
        return ext == null || !NON_OPENABLE_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }
}
