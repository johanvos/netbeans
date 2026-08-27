package com.gluonhq.netbeans.nbfx.launcher;

import java.awt.AWTEvent;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.util.logging.Logger;
import javafx.application.Application;
import org.openide.modules.ModuleInstall;
import org.openide.windows.WindowManager;

public class JavaFXLauncher extends ModuleInstall {

    private static final Logger LOG = Logger.getLogger(JavaFXLauncher.class.getName());

    private final AWTEventListener windowSuppressor = this::onAwtEvent;

    @Override
    public void validate() {
        // Before anything can ask a versioning system about a file.
        VersioningOptOut.apply();
    }

    @Override
    public void restored() {
        System.out.println("JAVAFXLAUNCHER 0");
        System.err.println("JAVAFXLAUNCHER 0-err");
        LOG.info("NetBeans main window, AWT listener added");
        Toolkit.getDefaultToolkit().addAWTEventListener(windowSuppressor, AWTEvent.COMPONENT_EVENT_MASK);

        LOG.info("NetBeans Platform loaded, launching JavaFX...");
        Thread thread = new Thread(() -> Application.launch(JavaFXLaunchApp.class), "nbfx-javafx-launcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void onAwtEvent(AWTEvent event) {
        Frame mainWindow;
        try {
            mainWindow = WindowManager.getDefault().getMainWindow();
        } catch (Exception e) {
            return; // too early, window manager not yet initialized
        }
        if (mainWindow == null) {
            return;
        }

        // Component events (SHOWN / RESIZED)
        if (event instanceof ComponentEvent ce && ce.getComponent() == mainWindow && !mainWindow.isDisplayable()) {
            try {
                mainWindow.setUndecorated(true);
                mainWindow.setOpacity(0f);
                mainWindow.setFocusable(false);
            } catch (Exception e) {
                // ignore and hide the window on next events instead
            }
            LOG.info("mainWindow suppressed (opacity=0)");
        } else if (mainWindow.isVisible()) {
            mainWindow.setVisible(false);
            mainWindow.setFocusable(false);
            Toolkit.getDefaultToolkit().removeAWTEventListener(windowSuppressor);
            LOG.info("mainWindow hidden");
        }
    }

}
