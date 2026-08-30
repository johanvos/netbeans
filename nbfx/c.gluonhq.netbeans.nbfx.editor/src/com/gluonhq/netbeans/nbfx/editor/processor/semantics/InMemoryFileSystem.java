package com.gluonhq.netbeans.nbfx.editor.processor.semantics;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.netbeans.api.java.classpath.ClassPath;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileSystem;
import org.openide.filesystems.FileUtil;

/**
 * Creates an in-memory {@link FileObject} with live source content coming from the code editor,
 * so that javac can resolve module context.
 *
 * <p>For that, the module-info descriptor is added to the file system, and updated accordingly.</p>
 */
final class InMemoryFileSystem {

    private InMemoryFileSystem() {}

    /**
     * Creates an in-memory {@link FileObject} for the given {@code source}
     * that mirrors the project's source-root layout around {@code fileObject}.
     *
     * @param fileObject the original file whose project layout is mirrored
     * @param source     the live source content to write
     * @return a temp {@link FileObject} on a memory filesystem
     * @throws IOException if any filesystem operation fails
     */
    static FileObject createFileObject(FileObject fileObject, String source) throws IOException {
        FileSystem memoryFileSystem = FileUtil.createMemoryFileSystem();
        FileObject memoryRoot = memoryFileSystem.getRoot();
        if ("module-info".equals(fileObject.getName())) {
            // create, write and return in-memory module-info
            FileObject tempFileObject = memoryRoot.createData("module-info.java");
            writeSource(tempFileObject, source);
            return tempFileObject;
        }

        ClassPath srcPath = ClassPath.getClassPath(fileObject, ClassPath.SOURCE);
        FileObject srcRoot = srcPath != null ? srcPath.findOwnerRoot(fileObject) : null;
        if (srcRoot != null) {
            FileObject realModuleInfo = srcRoot.getFileObject("module-info.java");
            if (realModuleInfo != null) {
                // create and write to in-memory module-info the real module-info source,
                // so javac knows about the module context
                FileObject tempFileObject = memoryRoot.createData("module-info.java");
                writeSource(tempFileObject, realModuleInfo.asText());
            }
        }
        // create, write and return in-memory .java file at the correct package-relative path
        FileObject parentDir = resolvePackageDir(memoryRoot, srcRoot, fileObject);
        FileObject tempFo = parentDir.createData(fileObject.getNameExt());
        writeSource(tempFo, source);
        return tempFo;
    }

    /**
     * Creates the package directory hierarchy on the memory FS that matches
     * the original file's location relative to its source root.
     */
    private static FileObject resolvePackageDir(FileObject memRoot, FileObject srcRoot, FileObject fileObject)
            throws IOException {
        String relPath = srcRoot != null ? FileUtil.getRelativePath(srcRoot, fileObject) : fileObject.getNameExt();
        if (relPath != null && relPath.contains("/")) {
            String dir = relPath.substring(0, relPath.lastIndexOf('/'));
            return FileUtil.createFolder(memRoot, dir);
        }
        return memRoot;
    }

    /**
     * Writes the given source content to the provided FileObject using UTF-8 encoding.
     * @param fo The fileObject to write to
     * @param content The source content to be written
     * @throws IOException if any filesystem operation fails
     */
    private static void writeSource(FileObject fo, String content) throws IOException {
        try (OutputStream os = fo.getOutputStream()) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
