package com.gluonhq.netbeans.nbfx.file.actions;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.openide.util.NbBundle;

/**
 * Integrations with the host operating system's file manager and terminal.
 */
public final class DesktopActions {

    private static final Logger LOG = Logger.getLogger(DesktopActions.class.getName());

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean MAC = OS.contains("mac");
    private static final boolean WINDOWS = OS.contains("win");

    private DesktopActions() {
    }

    /** The platform-appropriate label for the "reveal in file manager" action. */
    public static String revealLabel() {
        return message(MAC ? "DesktopActions.showInFinder" :
                WINDOWS ? "DesktopActions.showInExplorer" :
                "DesktopActions.showInFiles");
    }

    /** Opens a system terminal at the given directory. */
    public static void openInTerminal(File target) {
        File dir = directoryOf(target);
        if (dir == null) {
            return;
        }
        try {
            String absolutePath = dir.getAbsolutePath();
            if (MAC) {
                run("open", "-a", "Terminal", absolutePath);
            } else if (WINDOWS) {
                run("cmd", "/c", "start", "cmd", "/k", "cd /d \"" + absolutePath + "\"");
            } else {
                if (!tryRun("x-terminal-emulator", "--working-directory=" + absolutePath)
                        && !tryRun("gnome-terminal", "--working-directory=" + absolutePath)
                        && !tryRun("konsole", "--workdir", absolutePath)) {
                    run("xterm");
                }
            }
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Failed to open terminal at " + dir, ex);
        }
    }

    /** Reveals the given file or directory in the system file manager. */
    public static void showInFileManager(File target) {
        if (target == null) {
            return;
        }
        try {
            String absolutePath = target.getAbsolutePath();
            if (MAC) {
                run("open", "-R", absolutePath);
                return;
            }
            if (WINDOWS) {
                run("explorer.exe", "/select,", absolutePath);
                return;
            }
            File dir = directoryOf(target);
            if (dir != null) {
                run("xdg-open", dir.getAbsolutePath());
            }
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Failed to reveal " + target + " in file manager", ex);
        }
    }

    private static File directoryOf(File target) {
        if (target == null) {
            return null;
        }
        return target.isDirectory() ? target : target.getParentFile();
    }

    private static void run(String... command) throws IOException {
        new ProcessBuilder(command).start();
    }

    private static String message(String key) {
        return NbBundle.getMessage(DesktopActions.class, key);
    }

    private static boolean tryRun(String... command) {
        try {
            new ProcessBuilder(command).start();
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
