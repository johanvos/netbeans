package com.gluonhq.netbeans.nbfx.file.actions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;

import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Puts file references on the system clipboard and reads them back, tracking whether the last
 * operation was a copy or a cut.
 *
 * <p>Files are exchanged through the standard {@link DataFormat#FILES} format so the content is
 * interoperable with the host file manager. A private marker format ({@link #CUT_MARKER}) is added
 * for cut operations so a subsequent paste knows it should move (and then clear the clipboard)
 * instead of copying.</p>
 *
 * <p>All methods must be called on the JavaFX Application Thread.</p>
 */
public final class FileClipboard {

    private static final DataFormat CUT_MARKER = cutMarker();

    private FileClipboard() {
    }

    /** Places the given file objects on the clipboard as a copy operation. */
    public static void copy(List<FileObject> files) {
        put(files, false);
    }

    /** Places the given file objects on the clipboard as a cut (move) operation. */
    public static void cut(List<FileObject> files) {
        put(files, true);
    }

    /** Whether the clipboard currently holds one or more files. */
    public static boolean hasFiles() {
        return !Clipboard.getSystemClipboard().getFiles().isEmpty();
    }

    /** Whether the current clipboard content was placed by a cut operation. */
    public static boolean isCut() {
        return Clipboard.getSystemClipboard().hasContent(CUT_MARKER);
    }

    /** The files currently on the clipboard, possibly empty. */
    public static List<File> files() {
        return Clipboard.getSystemClipboard().getFiles();
    }

    /** Clears the clipboard, typically after a cut has been pasted. */
    public static void clear() {
        Clipboard.getSystemClipboard().clear();
    }

    private static void put(List<FileObject> files, boolean cut) {
        if (files == null || files.isEmpty()) {
            return;
        }
        List<File> javaFiles = new ArrayList<>();
        for (FileObject fo : files) {
            File f = fo == null ? null : FileUtil.toFile(fo);
            if (f != null) {
                javaFiles.add(f);
            }
        }
        if (javaFiles.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putFiles(javaFiles);
        if (cut) {
            content.put(CUT_MARKER, Boolean.TRUE);
        }
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static DataFormat cutMarker() {
        String id = "application/x-nbfx-cut";
        DataFormat existing = DataFormat.lookupMimeType(id);
        return existing != null ? existing : new DataFormat(id);
    }
}
