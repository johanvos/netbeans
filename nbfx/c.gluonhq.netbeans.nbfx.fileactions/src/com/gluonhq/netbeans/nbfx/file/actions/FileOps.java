package com.gluonhq.netbeans.nbfx.file.actions;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

import org.openide.filesystems.FileUtil;

/**
 * Low-level, reusable filesystem helpers shared by the file clipboard actions and their
 * reversible edits: recursive copy/move/delete, collision-free target resolution and refreshing
 * the NetBeans filesystem so the project tree reacts to the change.
 */
final class FileOps {

    private FileOps() {
    }

    /** Recursively copies {@code src} to {@code dest}. */
    static void copyRecursively(Path src, Path dest) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dest.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dest.resolve(src.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Recursively deletes {@code path}. */
    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Moves {@code src} to {@code dest}, falling back to copy+delete across filesystems. */
    static void move(Path src, Path dest) throws IOException {
        try {
            Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            copyRecursively(src, dest);
            deleteRecursively(src);
        }
    }

    /** Returns a destination path in {@code dir} for {@code name}, adding a suffix if it exists. */
    static Path uniqueTarget(Path dir, String name) {
        Path candidate = dir.resolve(name);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; ; i++) {
            String suffix = i == 1 ? " copy" : " copy " + i;
            candidate = dir.resolve(base + suffix + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    /** Refreshes the NetBeans filesystem for the parent directories of the given paths. */
    static void refreshParents(Path... paths) {
        for (Path path : paths) {
            if (path == null) {
                continue;
            }
            Path parent = path.getParent();
            if (parent != null) {
                FileUtil.refreshFor(new File(parent.toString()));
            }
        }
    }
}
