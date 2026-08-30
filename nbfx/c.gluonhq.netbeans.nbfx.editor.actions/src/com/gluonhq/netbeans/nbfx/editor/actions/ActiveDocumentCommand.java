package com.gluonhq.netbeans.nbfx.editor.actions;

import com.gluonhq.netbeans.nbfx.api.AbstractCommand;
import com.gluonhq.netbeans.nbfx.api.EditorDocument;

import java.util.function.Consumer;
import java.util.function.Function;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.input.KeyCombination;
import javafx.util.Subscription;

/**
 * Base class for editor commands that act on an active {@link EditorDocument} and whose
 * enablement is driven by a per-document observable state (for example, "can undo" or "has
 * selection"). The command re-observes that state whenever the active document changes, so
 * enablement always follows the current context. The active document is taken from the supplied
 * observable, so the same command works globally (the shared
 * {@link com.gluonhq.netbeans.nbfx.api.EditorContext#activeDocumentProperty() active document})
 * or scoped to a single window (that window's selected editor).
 */
abstract class ActiveDocumentCommand extends AbstractCommand {

    private final ObservableValue<EditorDocument> activeDocument;
    private final Function<EditorDocument, ReadOnlyBooleanProperty> enablement;
    private final Consumer<EditorDocument> action;
    private final ChangeListener<Boolean> enablementListener = (obs, was, now) -> updateDisabled();
    private final Subscription activeDocumentSubscription;
    private ReadOnlyBooleanProperty observed;

    ActiveDocumentCommand(String id, String text, KeyCombination accelerator,
                          ObservableValue<EditorDocument> activeDocument,
                          Function<EditorDocument, ReadOnlyBooleanProperty> enablement,
                          Consumer<EditorDocument> action) {
        super(id, text, accelerator);
        this.activeDocument = activeDocument;
        this.enablement = enablement;
        this.action = action;
        this.activeDocumentSubscription = activeDocument.subscribe(this::onActiveDocumentChanged);
    }

    @Override
    public void run() {
        EditorDocument active = activeDocument.getValue();
        if (active == null) {
            return;
        }
        ReadOnlyBooleanProperty state = enablement.apply(active);
        if (state != null && state.get()) {
            action.accept(active);
        }
    }

    /**
     * Detaches from the active-document property and from the document currently observed. Window-scoped
     * instances observe documents that outlive their window, so this must run when that window closes.
     */
    @Override
    public void dispose() {
        activeDocumentSubscription.unsubscribe();
        if (observed != null) {
            observed.removeListener(enablementListener);
            observed = null;
        }
    }

    private void onActiveDocumentChanged(EditorDocument active) {
        if (observed != null) {
            observed.removeListener(enablementListener);
        }
        observed = active == null ? null : enablement.apply(active);
        if (observed != null) {
            observed.addListener(enablementListener);
        }
        updateDisabled();
    }

    private void updateDisabled() {
        setDisabled(observed == null || !observed.get());
    }

}
