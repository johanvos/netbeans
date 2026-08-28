package com.gluonhq.netbeans.nbfx.editor.processor.semantics;

import com.gluonhq.netbeans.nbfx.editor.processor.SourceUtils;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.api.java.lexer.JavaTokenId;
import org.netbeans.api.java.queries.SourceLevelQuery;
import org.netbeans.api.java.source.CancellableTask;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.lexer.InputAttributes;
import org.netbeans.api.lexer.TokenHierarchy;
import org.openide.filesystems.FileObject;

/**
 * For a given {@link FileObject}, the SourceContext keeps an up-to-date cache of source and related artifacts.
 * A single instance is owned by the {@code JavaSyntaxDecorator} and passed to every processor (lex, semantic)
 * so they all reuse the same {@link TokenHierarchy}, line-start / line-length arrays,
 * in-memory {@link FileObject} and {@link JavaSource} artifacts.
 * <p>
 * Whenever the source text changes, invalidation clears the cache, and the artifacts are recomputed.
 * </p>
 * <p>
 * A {@link Snapshot} is an immutable bundle of the source and artifacts at a given instant,  useful for background
 * tasks that require an immutable context.
 * </p>
 */
public final class SourceContext {

    private static final Logger LOG = Logger.getLogger(SourceContext.class.getName());

    /**
     * Immutable bundle of the source artifacts at a point in time, intended
     * for background tasks
     */
    public record Snapshot(String source, JavaSource javaSource, int[] lineStarts, int[] lineLengths) {}

    private final FileObject fileObject;
    private final ClasspathInfo cpInfo;
    private final InputAttributes lexerAttributes;

    private String source;
    private int[] lineStarts;
    private int[] lineLengths;
    private TokenHierarchy<?> hierarchy;
    private FileObject inMemoryFo;
    private JavaSource javaSource;

    public SourceContext(FileObject fileObject) {
        this.fileObject = Objects.requireNonNull(fileObject);
        this.cpInfo = ClasspathInfo.create(fileObject);
        this.lexerAttributes = createLexerAttributes(fileObject);
    }

    public FileObject fileObject() {
        return fileObject;
    }

    public ClasspathInfo classpathInfo() {
        return cpInfo;
    }

    /**
     * Updates the cached source. When the new text differs from the currently
     * cached one, every derived artifact is nullified and will be rebuilt on next access.
     *
     * @param newSource the new source text, or {@code null} to invalidate
     */
    public synchronized void setSource(String newSource) {
        if (Objects.equals(this.source, newSource)) {
            return;
        }
        this.source = newSource;
        this.lineStarts = null;
        this.lineLengths = null;
        this.hierarchy = null;
        this.inMemoryFo = null;
        this.javaSource = null;
    }

    /** Clears the cached source and every derived artifact. */
    public void invalidate() {
        setSource(null);
    }

    /**
     * Gets the source of the current context
     */
    public synchronized String source() {
        return source;
    }

    /**
     * Returns a {@link JavaSource} for the current source text.
     *
     * @return the cached {@link JavaSource}, or {@code null} when the source
     *         is empty or the in-memory FS could not be built
     */
    public synchronized JavaSource javaSource() {
        if (source == null || source.isEmpty()) {
            return null;
        }
        if (javaSource == null) {
            try {
                inMemoryFo = InMemoryFileSystem.createFileObject(fileObject, source);
                javaSource = JavaSource.create(cpInfo, inMemoryFo);
            } catch (IOException ex) {
                LOG.log(Level.WARNING, "Could not create in-memory JavaSource", ex);
            }
        }
        return javaSource;
    }

    /**
     * Returns an array with the offsets of the first position of each line
     */
    public synchronized int[] lineStarts() {
        requireSource();
        if (lineStarts == null) {
            lineStarts = SourceUtils.computeLineStarts(source);
        }
        return lineStarts;
    }

    /**
     * Returns an array with the length of each line
     */
    public synchronized int[] lineLengths() {
        requireSource();
        if (lineLengths == null) {
            int[] starts = lineStarts();
            int n = starts.length;
            int[] lens = new int[n];
            for (int i = 0; i < n - 1; i++) {
                lens[i] = starts[i + 1] - 1 - starts[i];
            }
            lens[n - 1] = source.length() - starts[n - 1];
            lineLengths = lens;
        }
        return lineLengths;
    }

    /**
     * Create an immutable snapshot with the current source and artifacts, so background tasks
     * operate on a consistent set of values, given that other threads could invalidate the context at any time.
     */
    public synchronized Snapshot snapshot() {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return new Snapshot(source, javaSource(), lineStarts(), lineLengths());
    }

    /**
     * Return the {@link TokenHierarchy} for the current source.
     */
    public synchronized TokenHierarchy<?> hierarchy() {
        requireSource();
        if (hierarchy == null) {
            hierarchy = TokenHierarchy.create(
                    source, false, JavaTokenId.language(), null, lexerAttributes);
        }
        return hierarchy;
    }

    private void requireSource() {
        if (source == null) {
            throw new IllegalStateException("SourceContext has no source; call setSource() first.");
        }
    }

    public static InputAttributes createLexerAttributes(FileObject fo) {
        InputAttributes attrs = new InputAttributes();
        // enable module keywords if the file is module-info
        attrs.setValue(JavaTokenId.language(), "fileName",
                (Supplier<String>) fo::getNameExt, false);
        // enable all language features up to the file source level
        String sourceLevel = SourceLevelQuery.getSourceLevel(fo);
        attrs.setValue(JavaTokenId.language(), "version",
                (Supplier<String>) () -> sourceLevel != null ? sourceLevel : String.valueOf(Runtime.version().feature()),
                false);
        return attrs;
    }

    /**
     * Convenience method to update source and run a semantic JavaSource task only when a snapshot is available.
     *
     * @return {@code true} when the task was executed, {@code false} when source/snapshot JavaSource is unavailable
     */
    public boolean runSemanticTask(String newSource,
                                   CancellableTask<CompilationController> task,
                                   boolean shared) throws IOException {
        Snapshot snapshot;
        synchronized (this) {
            setSource(newSource);
            snapshot = snapshot();
        }
        return runSemanticTask(snapshot, task, shared);
    }

    /**
     * Convenience method to run a semantic JavaSource task for an existing snapshot.
     *
     * @return {@code true} when the task was executed, {@code false} when snapshot JavaSource is unavailable
     */
    public static boolean runSemanticTask(Snapshot snapshot,
                                          CancellableTask<CompilationController> task,
                                          boolean shared) throws IOException {
        if (snapshot == null || snapshot.javaSource() == null) {
            return false;
        }
        SilentJavacLogs.installOnce();
        try {
            snapshot.javaSource().runUserActionTask(task, shared);
        } catch (IOException | RuntimeException ex) {
            if (SilentJavacLogs.isKnownTransientJavacBug(ex)) {
                // Mid-edit state triggers a known javac NPE (e.g. `@SuppressWarnings`
                // without parentheses crashes Lint.suppressionsFrom). Demote to FINE
                // so it doesn't spam the console.
                LOG.log(Level.FINE, () -> "Skipping transient javac failure on incomplete source: "
                        + SilentJavacLogs.rootCauseSummary(ex));
                return false;
            }
            LOG.log(Level.SEVERE, "Exception while running semantic task: " + ex.getMessage(), ex);
            throw new IOException("Exception while running semantic task: ", ex);
        }
        return true;
    }

}


