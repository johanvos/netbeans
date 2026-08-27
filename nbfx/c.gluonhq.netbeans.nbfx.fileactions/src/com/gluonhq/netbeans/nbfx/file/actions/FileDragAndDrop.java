package com.gluonhq.netbeans.nbfx.file.actions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;

import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Drag-and-drop of files, shared by the navigator trees (drag source and drop target) and the
 * editor tab panes (drop target).
 *
 * <p>An internal drag (from a navigator tree) publishes its files under a private
 * {@link #NBFX_FILES} format holding their absolute paths, rather than under the standard
 * {@link Dragboard#getFiles() files} format, so that dropping onto another application does
 * nothing. External drags (from Finder/Explorer) are read from the standard format, which makes
 * both gestures look the same to a drop target.</p>
 */
public final class FileDragAndDrop {

    /** Private format of an internal file drag: the absolute paths of the dragged files. */
    private static final DataFormat NBFX_FILES = new DataFormat("application/x-nbfx-files");

    private static final String PATH_SEPARATOR = "\n";

    /** Key marking a node that handles file drops itself (so ancestors must not intercept them). */
    private static final Object DROP_TARGET_KEY = new Object();

    /** The last files resolved by {@link #filesFrom}, and their resolution; see there. */
    private static List<File> cachedFiles = List.of();
    private static List<FileObject> cachedFileObjects = List.of();

    private FileDragAndDrop() {}

    /** Publishes {@code files} on {@code content} as an internal file drag. */
    public static void putFiles(ClipboardContent content, List<FileObject> files) {
        StringBuilder paths = new StringBuilder();
        for (FileObject file : files) {
            File local = FileUtil.toFile(file);
            if (local == null) {
                continue;
            }
            if (!paths.isEmpty()) {
                paths.append(PATH_SEPARATOR);
            }
            paths.append(local.getAbsolutePath());
        }
        content.put(NBFX_FILES, paths.toString());
    }

    /** Whether {@code dragboard} carries files, dragged internally or from another application. */
    public static boolean hasFiles(Dragboard dragboard) {
        return dragboard != null && (dragboard.hasContent(NBFX_FILES) || dragboard.hasFiles());
    }

    /**
     * The existing files carried by {@code dragboard}, from an internal drag ({@link #putFiles})
     * or from an external one (Finder/Explorer), or an empty list if it carries no file.
     * <p>
     * The last resolution is cached, since drop targets ask for it on every mouse move of a drag.
     */
    public static List<FileObject> filesFrom(Dragboard dragboard) {
        if (dragboard == null) {
            return List.of();
        }
        List<File> files = new ArrayList<>();
        if (dragboard.getContent(NBFX_FILES) instanceof String paths) {
            for (String path : paths.split(PATH_SEPARATOR)) {
                if (!path.isBlank()) {
                    files.add(new File(path));
                }
            }
        } else if (dragboard.hasFiles()) {
            files.addAll(dragboard.getFiles());
        }
        if (files.equals(cachedFiles) && cachedFileObjects.stream().allMatch(FileObject::isValid)) {
            return cachedFileObjects;
        }
        List<FileObject> fileObjects = new ArrayList<>();
        for (File file : files) {
            FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(file));
            if (fo != null) {
                fileObjects.add(fo);
            }
        }
        cachedFiles = List.copyOf(files);
        cachedFileObjects = List.copyOf(fileObjects);
        return cachedFileObjects;
    }

    /** Whether {@code dragboard} carries files dragged from outside the application. */
    public static boolean isExternal(Dragboard dragboard) {
        return dragboard != null && !dragboard.hasContent(NBFX_FILES) && dragboard.hasFiles();
    }

    /**
     * Marks {@code node} as handling file drops on its own, so that an ancestor intercepting file
     * drags (the editor tab panes) leaves the ones aimed at it alone.
     */
    public static void markDropTarget(Node node) {
        if (node != null) {
            node.getProperties().put(DROP_TARGET_KEY, Boolean.TRUE);
        }
    }

    /** Whether {@code target} is, or sits inside, a node {@link #markDropTarget marked} as a file drop target. */
    public static boolean isWithinDropTarget(EventTarget target) {
        for (Node node = target instanceof Node n ? n : null; node != null; node = node.getParent()) {
            if (node.hasProperties() && node.getProperties().containsKey(DROP_TARGET_KEY)) {
                return true;
            }
        }
        return false;
    }
}
