package com.gluonhq.netbeans.nbfx.editor.processor.semantics;


import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;

import com.gluonhq.netbeans.nbfx.editor.decoration.TokenCategory;
import com.gluonhq.netbeans.nbfx.editor.processor.SourceUtils;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.CompilationController;
import org.openide.filesystems.FileObject;

/**
 * Analyses compiler diagnostics produced by a {@link CompilationController} and
 * converts them into {@link TextPosResult}s for error/warning highlighting.
 *
 * <p>Diagnostics that are caused by the in-memory file system (due to missing packages or modules
 * that do exist on the real project classpath) are suppressed.</p>
 */
class JavaDiagnosticAnalyzer {

    private static final Logger LOG = Logger.getLogger(JavaDiagnosticAnalyzer.class.getName());

    /**
     * Diagnostic codes that can be generated when compiling on a memory FS
     * but are not real errors (the compiler can't verify packages/modules that exist
     * only on the real source root).
     */
    private static final Set<String> MEMORY_FS_DIAGNOSTIC_CODES = Set.of(
            "compiler.err.package.empty.or.not.found",
            "compiler.warn.package.empty.or.not.found",
            "compiler.err.module.not.found"
    );

    private static final ClasspathInfo.PathKind[] PATH_KINDS = {
            ClasspathInfo.PathKind.MODULE_BOOT, ClasspathInfo.PathKind.MODULE_COMPILE,
            ClasspathInfo.PathKind.MODULE_CLASS};

    private final FileObject fileObject;
    private final ClasspathInfo cpInfo;

    JavaDiagnosticAnalyzer(FileObject fileObject, ClasspathInfo cpInfo) {
        this.fileObject = fileObject;
        this.cpInfo = cpInfo;
    }

    /**
     * Inspects all diagnostics reported by the compiler and returns
     * {@link TextPosResult}s for those that should be highlighted in
     * the editor.
     *
     * @param controller the compilation controller
     * @param source     the original source text
     * @param lineStarts precomputed line-start offsets
     * @param editLine   0-based line currently being edited ({@code -1} to suppress nothing)
     * @return a list of decoration results for errors and warnings
     */
    public List<TextPosResult> analyze(CompilationController controller, String source, int[] lineStarts, int editLine) {
        List<TextPosResult> results = new ArrayList<>();
        LOG.info("Need to analyze, controller = " + controller);
        LOG.info("Need to analyze, controllerClass = " + controller.getClass());
        List<Diagnostic> diagnostics = controller.getDiagnostics();
        LOG.info("List of diagnostics = "+diagnostics);
        LOG.info("Size of diagostics = " + diagnostics.size());
        if (diagnostics.size() > 0) {
            Diagnostic dc1 = diagnostics.get(0);
            LOG.info("Got first diag of class "+dc1.getClass());
        }
        controller.getDiagnostics().forEach(diagnostic -> {
            String code = diagnostic.getCode();

            // Suppress errors on the line the user is actively typing on
            if (editLine >= 0 && diagnostic.getLineNumber() - 1 == editLine) {
                LOG.fine(() -> "Suppressed (editing line " + editLine + ") [" + code + "] line "
                        + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(null));
                return;
            }

            if (MEMORY_FS_DIAGNOSTIC_CODES.contains(code) && isNotRealError(diagnostic, source)) {
                LOG.fine(() -> "Suppressed (exists in project) [" + code + "] line "
                        + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(null));
                return;
            }
            LOG.info(() -> "Error on line " + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(null));
            LOG.info(() -> "start = " + diagnostic.getStartPosition() + " and pos = " + diagnostic.getPosition() + " and code = " + code);

            long start = diagnostic.getStartPosition();
            long end = diagnostic.getEndPosition();

            // For "cannot find symbol" errors the startPosition may include
            // a qualifier like "this." – use the more precise position instead.
            long pos = diagnostic.getPosition();
            if (pos > start && pos < end && "compiler.err.cant.resolve".equals(code)) {
                int s = (int) pos;
                while (s < end && !Character.isJavaIdentifierStart(source.charAt(s))) {
                    s++;
                }
                start = s;
            }

            if (start >= 0 && end <= start) {
                // Zero-length span: first try to highlight the identifier at start
                int identEnd = (int) start;
                if (identEnd < source.length() && Character.isJavaIdentifierStart(source.charAt(identEnd))) {
                    while (identEnd < source.length() && Character.isJavaIdentifierPart(source.charAt(identEnd))) {
                        identEnd++;
                    }
                    end = identEnd;
                }
            }
            if (start >= 0 && end <= start) {
                // Still zero-length (e.g. premature EOF at end of file):
                // fall back to highlighting the visible content of the diagnostic's line
                int diagLine = (int) diagnostic.getLineNumber() - 1; // 0-based
                if (diagLine >= 0 && diagLine < lineStarts.length) {
                    int lineStart = lineStarts[diagLine];
                    int lineEnd = (diagLine + 1 < lineStarts.length)
                            ? lineStarts[diagLine + 1] - 1   // exclude the '\n'
                            : source.length();
                    // Skip leading whitespace/tabs
                    while (lineStart < lineEnd && Character.isWhitespace(source.charAt(lineStart))) {
                        lineStart++;
                    }
                    // Skip trailing whitespace/tabs
                    while (lineEnd > lineStart && Character.isWhitespace(source.charAt(lineEnd - 1))) {
                        lineEnd--;
                    }
                    start = lineStart;
                    end = lineEnd;
                }
            }
            if (start >= 0 && end > start) {
                // If the range spans multiple lines, try to narrow it to the relevant symbol
                if (source.substring((int) start, (int) end).contains("\n")) {
                    long[] narrowed = findSymbol(source, diagnostic.getMessage(null));
                    if (narrowed != null) {
                        start = narrowed[0];
                        end = narrowed[1];
                    } else {
                        // Narrow to the last non-blank line of the range
                        // (e.g. highlight only the closing '}' for missing-return)
                        int rangeEnd = (int) end;
                        while (rangeEnd > (int) start && Character.isWhitespace(source.charAt(rangeEnd - 1))) {
                            rangeEnd--;
                        }
                        int lastLineStart = rangeEnd;
                        while (lastLineStart > (int) start && source.charAt(lastLineStart - 1) != '\n') {
                            lastLineStart--;
                        }
                        while (lastLineStart < rangeEnd && Character.isWhitespace(source.charAt(lastLineStart))) {
                            lastLineStart++;
                        }
                        if (lastLineStart < rangeEnd) {
                            start = lastLineStart;
                            end = rangeEnd;
                        }
                    }
                }

                TokenCategory cat = diagnostic.getKind() == Diagnostic.Kind.ERROR ?
                        TokenCategory.ERROR : TokenCategory.WARNING;
                results.add(TextPosResult.from((int) start, (int) end, lineStarts, cat.style(), diagnostic.getMessage(null)));
            }
        });

        return results;
    }

    /**
     * Checks whether a diagnostic from {@link #MEMORY_FS_DIAGNOSTIC_CODES}
     * is not a real error, by verifying that the referenced package or module
     * actually exists in the real project.
     */
    private boolean isNotRealError(Diagnostic<?> diag, String source) {
        long start = diag.getStartPosition();
        long end = diag.getEndPosition();
        if (start < 0 || end <= start || end > source.length()) {
            return false;
        }
        String name = source.substring((int) start, (int) end).trim();
        if (name.isEmpty()) {
            return false;
        }
        String code = diag.getCode();
        if (code.contains("package")) {
            return packageExistsInProject(name);
        }
        if (code.contains("module")) {
            return moduleExistsOnClasspath(name);
        }
        return false;
    }

    /**
     * Returns {@code true} if the given package name corresponds to a
     * directory on the real source root that contains at least one
     * {@code .java} file.
     */
    private boolean packageExistsInProject(String packageName) {
        ClassPath srcPath = ClassPath.getClassPath(fileObject, ClassPath.SOURCE);
        if (srcPath == null) {
            return false;
        }
        FileObject srcRoot = srcPath.findOwnerRoot(fileObject);
        if (srcRoot == null) {
            return false;
        }
        String relPath = packageName.replace('.', '/');
        FileObject pkgDir = srcRoot.getFileObject(relPath);
        if (pkgDir == null || !pkgDir.isFolder()) {
            return false;
        }
        for (FileObject child : pkgDir.getChildren()) {
            if ("java".equals(child.getExt())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if the given module name can be found on the
     * project's compile or boot module path.
     */
    private boolean moduleExistsOnClasspath(String moduleName) {
        for (ClasspathInfo.PathKind pk : PATH_KINDS) {
            try {
                ClassPath cp = cpInfo.getClassPath(pk);
                if (cp != null) {
                    for (FileObject root : cp.getRoots()) {
                        if (root.getPath().contains(moduleName.replace('.', '-'))
                                || root.getPath().contains(moduleName.replace('.', '/'))) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ex) {
                LOG.fine("Error checking module path " + pk + ": " + ex.getMessage());
            }
        }
        return false;
    }

    /**
     * Patterns that extract a Java identifier from common javac diagnostic messages.
     */
    private static final Pattern[] SYMBOL_PATTERNS = {
            Pattern.compile("\\bvariable\\s+(\\w+)"),
            Pattern.compile("\\bmethod\\s+(\\w+)"),
            Pattern.compile("\\bclass\\s+(\\w+)"),
            Pattern.compile("\\bsymbol:\\s+\\w+\\s+(\\w+)")
    };

    /**
     * Tries to narrow a multi-line diagnostic range to just the relevant symbol
     * mentioned in the error message.
     *
     * @param source  the full source text
     * @param message the diagnostic message
     * @return a {@code [start, end]} array, or {@code null} if narrowing failed
     */
    private static long[] findSymbol(String source, String message) {
        if (message == null) {
            return null;
        }
        for (Pattern p : SYMBOL_PATTERNS) {
            Matcher m = p.matcher(message);
            if (m.find()) {
                String symbol = m.group(1);
                int idx = SourceUtils.findWholeWord(source, symbol);
                if (idx >= 0) {
                    return new long[]{ idx, idx + symbol.length() };
                }
            }
        }
        return null;
    }
}
