package com.gluonhq.netbeans.nbfx.api;

import java.util.Objects;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.value.ObservableValue;
import javafx.scene.input.KeyCombination;

/**
 * Base class for {@link Command} implementations that manages the id, display text, accelerator
 * and the observable {@code disabled} state, so concrete commands only have to supply their
 * enablement logic and their {@link #run()} behavior.
 */
public abstract class AbstractCommand implements Command {

    private final String id;
    private final String text;
    private final KeyCombination accelerator;
    private final ReadOnlyBooleanWrapper disabled;

    /**
     * Creates a command that starts disabled ({@code disabled == true}). Suitable for
     * context-dependent commands that remain disabled until their context is evaluated.
     *
     * @param id          the stable command id, never {@code null}
     * @param text        the display text, never {@code null}
     * @param accelerator the preferred keyboard accelerator, or {@code null} if none
     */
    protected AbstractCommand(String id, String text, KeyCombination accelerator) {
        this(id, text, accelerator, true);
    }

    /**
     * Creates a command with an explicit initial {@code disabled} state.
     *
     * @param id                the stable command id, never {@code null}
     * @param text              the display text, never {@code null}
     * @param accelerator       the preferred keyboard accelerator, or {@code null} if none
     * @param initiallyDisabled the initial value of the {@code disabled} property
     */
    protected AbstractCommand(String id, String text, KeyCombination accelerator, boolean initiallyDisabled) {
        this.id = Objects.requireNonNull(id);
        this.text = Objects.requireNonNull(text);
        this.accelerator = accelerator;
        this.disabled = new ReadOnlyBooleanWrapper(this, "disabled", initiallyDisabled);
    }

    @Override
    public final String getId() {
        return id;
    }

    @Override
    public final String getText() {
        return text;
    }

    @Override
    public final KeyCombination getAccelerator() {
        return accelerator;
    }

    @Override
    public final boolean isDisabled() {
        return disabled.get();
    }

    @Override
    public final ReadOnlyBooleanProperty disabledProperty() {
        return disabled.getReadOnlyProperty();
    }

    /**
     * Sets the {@code disabled} state imperatively. Must not be used together with
     * {@link #bindDisabled(ObservableValue)} on the same command.
     *
     * @param value the new disabled state
     */
    protected void setDisabled(boolean value) {
        disabled.set(value);
    }

    /**
     * Binds the {@code disabled} state to the given observable enablement source. Must not be
     * used together with {@link #setDisabled(boolean)} on the same command.
     *
     * @param source the observable that drives the disabled state
     */
    protected final void bindDisabled(ObservableValue<Boolean> source) {
        disabled.bind(source);
    }
}
