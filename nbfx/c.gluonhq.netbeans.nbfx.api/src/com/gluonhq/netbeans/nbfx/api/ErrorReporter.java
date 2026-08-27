package com.gluonhq.netbeans.nbfx.api;

import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Platform;
import javafx.scene.control.Alert;

/**
 * Shared, FX-thread-safe and null-safe error reporter. Surfaces a failure to the user through a
 * modal {@link Alert} while also logging it, so nothing fails silently and headless/test
 * environments still record the failure.
 */
public final class ErrorReporter {

    private static final Logger LOG = Logger.getLogger(ErrorReporter.class.getName());
    private static final String UNKNOWN_ERROR = "Unknown error";

    private ErrorReporter() {
    }

    /**
     * Reports a failure with the given title, header and message.
     *
     * @param title   the dialog window title
     * @param header  the dialog header text (may be {@code null})
     * @param message the message to show; if blank a generic message is used
     */
    public static void report(String title, String header, String message) {
        report(title, header, message, null);
    }

    /**
     * Reports a failure, deriving a message from {@code cause} when {@code message} is blank.
     *
     * @param title   the dialog window title
     * @param header  the dialog header text (may be {@code null})
     * @param message the message to show; if blank, falls back to the cause
     * @param cause   the underlying failure, used both for logging and message fallback
     */
    public static void report(String title, String header, String message, Throwable cause) {
        String content = resolveMessage(message, cause);
        if (cause != null) {
            LOG.log(Level.WARNING, header == null ? content : header + ": " + content, cause);
        } else {
            LOG.log(Level.WARNING, header == null ? content : header + ": " + content);
        }
        showDialog(title, header, content);
    }

    private static String resolveMessage(String message, Throwable cause) {
        if (message != null && !message.isBlank()) {
            return message;
        }
        if (cause != null) {
            String causeMessage = cause.getMessage();
            if (causeMessage != null && !causeMessage.isBlank()) {
                return causeMessage;
            }
            return cause.getClass().getSimpleName();
        }
        return UNKNOWN_ERROR;
    }

    private static void showDialog(String title, String header, String content) {
        Runnable dialog = () -> {
            try {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle(title);
                alert.setHeaderText(header);
                alert.setContentText(content);
                alert.showAndWait();
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "Could not show error dialog: " + content, t);
            }
        };
        try {
            if (Platform.isFxApplicationThread()) {
                dialog.run();
            } else {
                Platform.runLater(dialog);
            }
        } catch (IllegalStateException ex) {
            // No stack trace: a missing toolkit (headless runs and unit tests) is a known state,
            // and what matters is that the error itself was already logged by the caller.
            LOG.log(Level.WARNING, "JavaFX toolkit not available; error not shown: {0}", content);
        }
    }
}
