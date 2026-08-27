package com.gluonhq.netbeans.nbfx.api;

import javafx.beans.property.BooleanProperty;

/**
 * Shared, observable view/editor preferences that holds global user-facing display settings that apply to
 * every editor.
 */
public interface EditorSettings {

    /**
     * Whether line numbers are shown in the gutter of every code editor.
     *
     * @return the observable, writable "show line numbers" property
     */
    BooleanProperty showLineNumbers();

}
