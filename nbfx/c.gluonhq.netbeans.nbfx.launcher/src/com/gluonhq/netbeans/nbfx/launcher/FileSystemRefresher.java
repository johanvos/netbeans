package com.gluonhq.netbeans.nbfx.launcher;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openide.util.NbPreferences;

/**
 * Brings the open projects back in sync with what is on disk.
 * <p>
 * The platform's filesystem layer only notices changes made through its own API; changes made
 * behind its back - in a terminal, in the Finder, by a build tool - are picked up when the
 * filesystem is refreshed. The IDE therefore refreshes its project roots whenever its window is
 * activated, which is when the user is most likely to have just done something elsewhere: the
 * navigator then shows what is really there, and a project whose root folder is gone is noticed by
 * {@link ProjectRootWatcher}.
 * <p>
 * The IDE does this in {@code org.netbeans.core.ui.warmup.MenuWarmUpTask}, which listens on the
 * Swing main window - a window a JavaFX application never creates, so the trigger has to be our
 * own. The rest follows the IDE: the refresh is delayed, so that passing through the window on the
 * way somewhere else costs nothing, and it can be turned off by the same two settings the IDE
 * honours, {@code -Dnetbeans.indexing.noFileRefresh=true} and the {@code FileSystemRefreshAction}
 * {@code manual} preference.
 * <p>
 * Only the open project roots are refreshed (recursively), never the whole filesystem, and the work
 * happens on a background thread: a refresh walks the projects on disk and must never block the FX
 * thread. Requests that arrive while one is running are collapsed into a single follow-up run.
 */
final class FileSystemRefresher {

    private static final Logger LOG = Logger.getLogger(FileSystemRefresher.class.getName());

    /** As in the IDE: activating the window is not by itself a reason to go to disk right away. */
    private static final long DELAY_MILLIS = 1_500;

    private final Supplier<List<File>> roots;
    private final Consumer<File[]> refresh;
    private final BooleanSupplier disabled;
    private final long delayMillis;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "project-root-refresh");
        thread.setDaemon(true);
        return thread;
    });

    /** The roots to refresh, captured by the caller; {@code null} once a run has taken them. */
    private final AtomicReference<List<File>> pending = new AtomicReference<>();
    /** Whether a run is scheduled or in flight, so a burst of requests costs one extra run at most. */
    private boolean running;
    /** Whether the user has already been told that refreshing is off; it is worth saying once. */
    private boolean warnedDisabled;

    FileSystemRefresher(Supplier<List<File>> roots, Consumer<File[]> refresh) {
        this(roots, refresh, FileSystemRefresher::refreshTurnedOff, DELAY_MILLIS);
    }

    FileSystemRefresher(Supplier<List<File>> roots, Consumer<File[]> refresh, BooleanSupplier disabled, long delayMillis) {
        this.roots = Objects.requireNonNull(roots, "roots");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.disabled = Objects.requireNonNull(disabled, "disabled");
        this.delayMillis = delayMillis;
    }

    /**
     * Whether the user has asked not to be refreshed behind their back: the same system property and
     * preference the IDE reads, so that a userdir shared with the IDE behaves the same in both.
     */
    private static boolean refreshTurnedOff() {
        if (Boolean.getBoolean("netbeans.indexing.noFileRefresh")) {
            return true;
        }
        return NbPreferences.root().node("org/openide/actions/FileSystemRefreshAction").getBoolean("manual", false);
    }

    /**
     * Refreshes the open projects in the background. Called from the FX thread on window activation,
     * so it must return immediately - only the roots are read here, on the caller's thread, as they
     * live in an observable list the FX thread owns.
     */
    void requestRefresh() {
        if (isDisabled()) {
            return;
        }
        List<File> targets = roots.get();
        if (targets == null || targets.isEmpty()) {
            return;
        }
        pending.set(targets);
        synchronized (this) {
            if (running) {
                return;
            }
            running = true;
        }
        submit();
    }

    private void submit() {
        try {
            executor.schedule(this::runRefresh, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            synchronized (this) {
                running = false;
            }
            LOG.log(Level.FINE, "Could not schedule a project refresh", e);
        }
    }

    /** Whether refreshing is turned off, said out loud once so it does not look like a bug. */
    private boolean isDisabled() {
        boolean off;
        try {
            off = disabled.getAsBoolean();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Could not read the refresh preference", e);
            return false;
        }
        if (off && !warnedDisabled) {
            warnedDisabled = true;
            LOG.info("Refreshing the open projects on window activation is disabled");
        }
        return off;
    }

    private void runRefresh() {
        try {
            refresh(pending.getAndSet(null));
        } catch (RuntimeException e) {
            LOG.log(Level.INFO, "Refreshing the open projects failed", e);
        } finally {
            boolean again;
            synchronized (this) {
                // Something was requested while this run was already walking the projects, which it
                // may have missed: one more run picks it up.
                again = pending.get() != null;
                running = again;
            }
            if (again) {
                submit();
            }
        }
    }

    /** Refreshes the open project roots on the calling thread; for tests. */
    void refreshNow() {
        refresh(roots.get());
    }

    private void refresh(List<File> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        // A project whose folder is gone is closed by ProjectRootWatcher, which only has to look at
        // the folder itself. Refreshing it instead would walk everything the platform still has
        // cached under it and report it all as deleted, waking the project system and the VCS for a
        // project that is on its way out - noisy, slow and, in the Maven project system, buggy.
        List<File> existing = targets.stream().filter(File::isDirectory).toList();
        if (existing.isEmpty()) {
            return;
        }
        refresh.accept(existing.toArray(File[]::new));
    }

    /** Stops accepting refreshes; the IDE is shutting down. */
    void shutdown() {
        executor.shutdownNow();
    }
}
