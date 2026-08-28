package com.gluonhq.netbeans.nbfx.editor.processor.semantics;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Silences the noisy "Please report a bug against java/source …" dump emitted by
 * NetBeans (via {@code org.openide.util.Exceptions}) when {@code javac} NPEs while
 * attributing incomplete source — a well-known transient state during typing,
 * e.g. {@code @SuppressWarnings} without parentheses crashes
 * {@code com.sun.tools.javac.code.Lint.suppressionsFrom}.
 *
 * <p>Two coordinated mechanisms are exposed:</p>
 * <ul>
 *   <li>{@link #isKnownTransientJavacBug(Throwable)} — exception-chain probe used
 *       by callers (e.g. {@code SourceContext.runSemanticTask}) to decide whether
 *       a thrown failure should be quietly retried on the next snapshot or
 *       propagated as a real error.</li>
 *   <li>{@link #installOnce()} — lazily wires a {@link Filter} into the small set
 *       of NetBeans loggers and root handlers most likely to emit the bug-report
 *       dump. The filter drops <em>only</em> the records that match this transient
 *       state; everything else keeps its normal path.</li>
 * </ul>
 *
 * <p>Installed lazily on first use rather than from a static initializer so that
 * the unit-test logging configuration is not perturbed at class-load time.</p>
 */
final class SilentJavacLogs {

    /**
     * Loggers known (or suspected) to emit the bug-report dump for the transient
     * javac NPE. The order does not matter — every entry is wrapped with the same
     * filter on first install.
     */
    private static final String[] NOISY_PARSER_LOGGERS = {
            // The dump-report message "An error occurred during parsing of '…'. Please
            // report a bug against java/source …" is funneled through NetBeans'
            // `org.openide.util.Exceptions` utility — that's where the SEVERE record
            // actually surfaces, even though the throwable originates inside javac.
            "org.openide.util.Exceptions",
            // JavacParser additionally re-logs the same error via its own logger when
            // moveToPhase fails. Cover it too so neither call path slips through.
            "org.netbeans.modules.java.source.parsing.JavacParser",
            "org.netbeans.modules.java.source.parsing",
            "org.netbeans.modules.java.source",
            "org.netbeans.api.java.source.JavaSource",
            // The "ALL [null]" line at the bottom of the dump is emitted with the
            // root logger as origin; cover that too.
            "",
    };

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private SilentJavacLogs() {
    }

    /**
     * Recognises NPEs (possibly wrapped in {@code IllegalStateException}) thrown by
     * {@code com.sun.tools.javac.*} while attributing incomplete source — typically
     * annotations whose required elements have not been typed yet, e.g.
     * {@code @SuppressWarnings} (no parens) crashes {@code Lint.suppressionsFrom}.
     */
    static boolean isKnownTransientJavacBug(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof NullPointerException) {
                for (StackTraceElement frame : t.getStackTrace()) {
                    String cls = frame.getClassName();
                    if (cls != null && cls.startsWith("com.sun.tools.javac.")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Compact summary of the root cause of {@code ex}, used in FINE-level
     * transient-failure messages so the log line stays single-line.
     */
    static String rootCauseSummary(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        StackTraceElement top = root.getStackTrace().length > 0 ? root.getStackTrace()[0] : null;
        return root.getClass().getSimpleName() + (top == null ? "" : " at " + top);
    }

    /**
     * Installs {@link TransientJavacBugFilter} on every logger in
     * {@link #NOISY_PARSER_LOGGERS} and on every {@link Handler} currently attached
     * to the root logger.
     */
    static void installOnce() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        for (String name : NOISY_PARSER_LOGGERS) {
            Logger logger = Logger.getLogger(name);
            logger.setFilter(new TransientJavacBugFilter(logger.getFilter()));
        }
        Handler[] rootHandlers = Logger.getLogger("").getHandlers();
        if (rootHandlers != null) {
            for (Handler handler : rootHandlers) {
                handler.setFilter(new TransientJavacBugFilter(handler.getFilter()));
            }
        }
    }

    /**
     * {@link Filter} that drops {@link LogRecord}s either (a) whose attached
     * throwable matches {@link #isKnownTransientJavacBug}, or (b) whose message
     * contains the bug-report marker NetBeans emits via
     * {@code Exceptions.attachMessage} / {@code Exceptions.printStackTrace} for
     * that same javac state.
     */
    static final class TransientJavacBugFilter implements Filter {

        private static final String BUG_REPORT_MARKER = "Please report a bug against java/source";

        private final Filter delegate;

        TransientJavacBugFilter(Filter delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isLoggable(LogRecord record) {
            Throwable thrown = record.getThrown();
            if (thrown != null && isKnownTransientJavacBug(thrown)) {
                return false;
            }
            String message = record.getMessage();
            if (message != null && message.contains(BUG_REPORT_MARKER)) {
                return false;
            }
            return delegate == null || delegate.isLoggable(record);
        }
    }
}
