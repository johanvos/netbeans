package com.gluonhq.netbeans.nbfx.launcher;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import org.openide.util.NbBundle;

/**
 * A persistent status bar shown at the bottom of the main window: it names the selected project -
 * the one the project-scoped actions apply to - on the left, and shows the progress of the project
 * being opened in the centre.
 */
public class StatusBar extends StackPane {

    private final Label projectName = new Label();
    private final Label legend = new Label();
    private final ProgressBar progressBar = new ProgressBar();
    private final Button cancelButton = new Button();
    private final HBox progressGroup;

    private Runnable onCancel;

    public StatusBar() {
        getStyleClass().add("status-bar");

        projectName.getStyleClass().add("status-bar-project");
        setProject(null);

        legend.getStyleClass().add("status-bar-legend");

        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.getStyleClass().add("status-bar-progress");

        cancelButton.getStyleClass().add("status-bar-cancel");
        cancelButton.setText("\u2715"); // ✕
        cancelButton.setFocusTraversable(false);
        cancelButton.setTooltip(new Tooltip(NbBundle.getMessage(StatusBar.class, "StatusBar.cancel.tooltip")));
        cancelButton.setOnAction(_ -> {
            if (onCancel != null) {
                onCancel.run();
            }
        });

        progressGroup = new HBox(legend, progressBar, cancelButton);
        progressGroup.getStyleClass().add("status-bar-progress-group");
        progressGroup.setAlignment(Pos.CENTER);
        setProgressVisible(false);

        getChildren().addAll(projectName, progressGroup);
        StackPane.setAlignment(projectName, Pos.CENTER_LEFT);
    }

    /**
     * Shows the progress area with the given legend, running {@code onCancel} when the user presses
     * the cancel button. Safe to call from any thread.
     *
     * @param legendText the text shown to the left of the progress bar
     * @param onCancel   the action to run when cancel is pressed (may be {@code null})
     */
    public void showProgress(String legendText, Runnable onCancel) {
        runOnFxThread(() -> {
            this.onCancel = onCancel;
            legend.setText(legendText);
            setProgressVisible(true);
        });
    }

    /**
     * Names the selected project, or clears the area when {@code name} is {@code null} (no project
     * is open). Safe to call from any thread.
     */
    public void setProject(String name) {
        runOnFxThread(() -> {
            projectName.setText(name == null ? "" : name);
            projectName.setVisible(name != null);
            projectName.setManaged(name != null);
        });
    }

    /**
     * Hides the progress area. Safe to call from any thread.
     */
    public void hideProgress() {
        runOnFxThread(() -> {
            this.onCancel = null;
            setProgressVisible(false);
        });
    }

    private void setProgressVisible(boolean visible) {
        progressGroup.setVisible(visible);
        progressGroup.setManaged(visible);
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
