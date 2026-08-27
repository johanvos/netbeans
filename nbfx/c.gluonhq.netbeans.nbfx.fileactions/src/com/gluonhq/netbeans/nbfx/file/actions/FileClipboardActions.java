package com.gluonhq.netbeans.nbfx.file.actions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.gluonhq.netbeans.nbfx.api.ErrorReporter;

import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.NbBundle;

/**
 * High-level cut/copy/paste actions that operate on {@link FileObject}s through the system
 * clipboard, convenient for wiring context menu items.
 *
 * <p>All methods must be called on the JavaFX Application Thread.</p>
 */
public final class FileClipboardActions {

    private static final Logger LOG = Logger.getLogger(FileClipboardActions.class.getName());

    private FileClipboardActions() {
    }

    public static void copy(List<FileObject> files) {
        FileClipboard.copy(files);
    }

    public static void cut(List<FileObject> files) {
        FileClipboard.cut(files);
    }

    /** Whether there is file content on the clipboard available to paste. */
    public static boolean canPaste() {
        return FileClipboard.hasFiles();
    }

    /**
     * Moves {@code sources} into {@code targetFolder}, resolving name collisions and skipping entries
     * that already live in the target. The operation is recorded with the {@link FileUndoManager}
     * history of the project owning the target folder so it can be undone, and failures are reported through a visible error dialog.
     *
     * @param sources      the files or folders to move
     * @param targetFolder the destination folder
     * @return the destination {@link FileObject}s that were created, in move order
     */
    public static List<FileObject> move(List<FileObject> sources, FileObject targetFolder) {
        if (!canMoveInto(targetFolder, sources)) {
            return List.of();
        }
        File targetDir = FileUtil.toFile(targetFolder);
        if (targetDir == null) {
            return List.of();
        }
        List<PasteEdit.Entry> moved = new ArrayList<>();
        List<Path> refresh = new ArrayList<>();
        try {
            for (FileObject source : sources) {
                File sourceFile = FileUtil.toFile(source);
                if (sourceFile == null) {
                    continue;
                }
                Path src = sourceFile.toPath();
                if (!Files.exists(src) || !movable(source, targetFolder)) {
                    continue;
                }
                Path dest = FileOps.uniqueTarget(targetDir.toPath(), src.getFileName().toString());
                FileOps.move(src, dest);
                moved.add(new PasteEdit.Entry(src, dest));
                refresh.add(src);
                refresh.add(dest);
            }
            if (!moved.isEmpty()) {
                FileUndoManager.getDefault().push(new PasteEdit(moved, true), ProjectScope.pathOf(targetFolder));
            }
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Failed to move into " + targetFolder.getPath(), ex);
            showError("Move.title", "Move.error", targetFolder.getNameExt(), ex);
        } finally {
            FileOps.refreshParents(refresh.toArray(Path[]::new));
            FileUtil.refreshFor(targetDir);
        }
        List<FileObject> destinations = new ArrayList<>();
        for (PasteEdit.Entry entry : moved) {
            FileObject fo = FileUtil.toFileObject(entry.destination().toFile());
            if (fo != null) {
                destinations.add(fo);
            }
        }
        return destinations;
    }

    /**
     * Copies {@code sources} into {@code targetFolder} recursively, resolving name collisions. The
     * operation is recorded with the {@link FileUndoManager} history of the project owning the
     * target folder so it can be undone, and failures are reported through a visible error dialog.
     * <p>
     * This backs the drop of files coming from outside the application (Finder/Explorer), which
     * leaves the originals in place.
     *
     * @param sources      the files or folders to copy
     * @param targetFolder the destination folder
     * @return the destination {@link FileObject}s that were created, in copy order
     */
    public static List<FileObject> copyInto(List<FileObject> sources, FileObject targetFolder) {
        if (!canCopyInto(targetFolder, sources)) {
            return List.of();
        }
        File targetDir = FileUtil.toFile(targetFolder);
        if (targetDir == null) {
            return List.of();
        }
        List<PasteEdit.Entry> copied = new ArrayList<>();
        try {
            for (FileObject source : sources) {
                File sourceFile = FileUtil.toFile(source);
                if (sourceFile == null) {
                    continue;
                }
                Path src = sourceFile.toPath();
                if (!Files.exists(src)) {
                    continue;
                }
                Path dest = FileOps.uniqueTarget(targetDir.toPath(), src.getFileName().toString());
                FileOps.copyRecursively(src, dest);
                copied.add(new PasteEdit.Entry(src, dest));
            }
            if (!copied.isEmpty()) {
                FileUndoManager.getDefault().push(new PasteEdit(copied, false), ProjectScope.pathOf(targetFolder));
            }
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Failed to copy into " + targetFolder.getPath(), ex);
            showError("Paste.title", "Paste.error", targetFolder.getNameExt(), ex);
        } finally {
            FileUtil.refreshFor(targetDir);
        }
        List<FileObject> destinations = new ArrayList<>();
        for (PasteEdit.Entry entry : copied) {
            FileObject fo = FileUtil.toFileObject(entry.destination().toFile());
            if (fo != null) {
                destinations.add(fo);
            }
        }
        return destinations;
    }

    /**
     * Whether {@code sources} can be copied into {@code targetFolder}: the target must be a folder,
     * and no source may be the target itself or an ancestor of it. Unlike a move, a source already
     * inside the target is allowed (it is copied next to itself under a unique name).
     */
    public static boolean canCopyInto(FileObject targetFolder, List<FileObject> sources) {
        if (targetFolder == null || !targetFolder.isFolder() || sources == null || sources.isEmpty()) {
            return false;
        }
        for (FileObject source : sources) {
            if (source == null) {
                continue;
            }
            if (source.equals(targetFolder) || FileUtil.isParentOf(source, targetFolder)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether at least one of {@code sources} can be moved into {@code targetFolder}: the target must
     * be a folder, and no source may be the target itself or an ancestor of it. Sources already inside
     * the target are ignored (no-op moves).
     */    public static boolean canMoveInto(FileObject targetFolder, List<FileObject> sources) {
        if (targetFolder == null || !targetFolder.isFolder() || sources == null || sources.isEmpty()) {
            return false;
        }
        boolean anyMovable = false;
        for (FileObject source : sources) {
            if (source == null) {
                continue;
            }
            if (source.equals(targetFolder) || FileUtil.isParentOf(source, targetFolder)) {
                return false;
            }
            if (movable(source, targetFolder)) {
                anyMovable = true;
            }
        }
        return anyMovable;
    }

    /** Whether {@code source} would actually move into {@code targetFolder}. */
    private static boolean movable(FileObject source, FileObject targetFolder) {
        FileObject parent = source.getParent();
        return parent == null || !parent.equals(targetFolder);
    }

    /**
     * Pastes the clipboard's files into {@code targetFolder}, copying (or moving, for a cut) each
     * entry recursively and resolving name collisions. The operation is recorded with the
     * {@link FileUndoManager} history of the project owning the target folder so it can be undone. Failures are reported through a visible error
     * dialog; nothing fails silently.
     *
     * @param targetFolder the destination folder
     */
    public static void paste(FileObject targetFolder) {
        if (targetFolder == null || !targetFolder.isFolder() || !FileClipboard.hasFiles()) {
            return;
        }
        File targetDir = FileUtil.toFile(targetFolder);
        if (targetDir == null) {
            return;
        }
        boolean cut = FileClipboard.isCut();
        List<File> sources = FileClipboard.files();
        List<PasteEdit.Entry> pasted = new ArrayList<>();
        IOException failure = null;
        try {
            for (File source : sources) {
                Path src = source.toPath();
                if (!Files.exists(src)) {
                    continue;
                }
                Path dest = FileOps.uniqueTarget(targetDir.toPath(), src.getFileName().toString());
                if (cut) {
                    FileOps.move(src, dest);
                } else {
                    FileOps.copyRecursively(src, dest);
                }
                pasted.add(new PasteEdit.Entry(src, dest));
            }
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Failed to paste into " + targetFolder.getPath(), ex);
            failure = ex;
        } finally {
            // Only a fully successful cut consumes the clipboard; on failure the selection is kept
            // so the user can retry. Whatever did get pasted is still recorded for undo.
            if (cut && failure == null) {
                FileClipboard.clear();
            }
            if (!pasted.isEmpty()) {
                FileUndoManager.getDefault().push(new PasteEdit(pasted, cut), ProjectScope.pathOf(targetFolder));
            }
            FileUtil.refreshFor(targetDir);
        }
        if (failure != null) {
            showError("Paste.title", "Paste.error", targetFolder.getNameExt(), failure);
        }
    }

    private static void showError(String titleKey, String messageKey, String name, Throwable cause) {
        String message = NbBundle.getMessage(FileClipboardActions.class, messageKey, name, safeMessage(cause));
        ErrorReporter.report(NbBundle.getMessage(FileClipboardActions.class, titleKey), null, message, cause);
    }

    private static String safeMessage(Throwable cause) {
        if (cause == null) {
            return "";
        }
        String message = cause.getMessage();
        return (message == null || message.isBlank()) ? cause.getClass().getSimpleName() : message;
    }
}
