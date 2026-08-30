package com.gluonhq.netbeans.nbfx.editor.codearea;

import java.text.DecimalFormat;
import java.util.Arrays;

import javafx.beans.property.ObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import jfx.incubator.scene.control.richtext.SideDecorator;

/**
 * A {@link SideDecorator} that shows line numbers (like the built-in
 * {@code LineNumberDecorator}) but also decorates lines that have
 * error or warning diagnostics with a mark and a tooltip.
 */
public class MarkLineNumberDecorator implements SideDecorator {

    private static final DecimalFormat FORMAT = new DecimalFormat("###0");

    /** Gutter width kept when line numbers are hidden, so error/warning indicators still have room. */
    private static final double INDICATOR_ONLY_WIDTH = 10;

    private final JavaSyntaxDecorator syntaxDecorator;
    private final ObjectProperty<Font> fontProperty;

    private boolean showLineNumbers = true;

    /**
     * @param syntaxDecorator the decorator that tracks error/warning diagnostics
     * @param fontProperty    the CodeArea font property to bind label fonts to
     */
    public MarkLineNumberDecorator(JavaSyntaxDecorator syntaxDecorator, ObjectProperty<Font> fontProperty) {
        this.syntaxDecorator = syntaxDecorator;
        this.fontProperty = fontProperty;
    }

    /** Sets whether line numbers are shown.*/
    public void setShowLineNumbers(boolean showLineNumbers) {
        this.showLineNumbers = showLineNumbers;
    }

    @Override
    public double getPrefWidth(double viewWidth) {
        return 0;
    }

    @Override
    public Node getMeasurementNode(int index) {
        String s = FORMAT.format(index + 300);
        char[] cs = new char[s.length()];
        Arrays.fill(cs, '8');
        return createNode(new String(cs), -1, null);
    }

    @Override
    public Node getNode(int index) {
        String severity = syntaxDecorator.getErrorSeverityOnLine(index);
        return createNode(FORMAT.format(index + 1), index, severity);
    }

    private Node createNode(String text, int index, String severity) {
        if (severity == null && !showLineNumbers) {
            Region spacer = new Region();
            spacer.getStyleClass().add("line-number-decorator");
            spacer.setMinSize(INDICATOR_ONLY_WIDTH, 1);
            spacer.setPrefSize(INDICATOR_ONLY_WIDTH, 1);
            return spacer;
        }

        // Error/warning indicator: a small colored dot
        Color color = "error".equals(severity) ? Color.RED : Color.ORANGE;
        Circle indicator = new Circle(4, color);
        indicator.setManaged(false);
        indicator.setVisible(severity != null);

        HBox container = new HBox(2, indicator) {
            @Override
            protected void layoutChildren() {
                super.layoutChildren();
                indicator.relocate(2, (getHeight() - indicator.getRadius() * 2) / 2);
            }
        };
        container.getStyleClass().add("line-number-decorator");
        container.setAlignment(Pos.CENTER_RIGHT);
        container.setMinSize(INDICATOR_ONLY_WIDTH, 1);
        container.setPrefSize(INDICATOR_ONLY_WIDTH, 1);
        container.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        if (showLineNumbers) {
            Label numberLabel = new Label(text);
            numberLabel.getStyleClass().add("line-number-decorator-label");
            numberLabel.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            numberLabel.setMinHeight(1);
            numberLabel.setPrefHeight(1);
            numberLabel.setAlignment(Pos.CENTER_RIGHT);
            numberLabel.setOpacity(1.0);
            if (fontProperty != null) {
                numberLabel.fontProperty().bind(fontProperty);
            }
            HBox.setHgrow(numberLabel, Priority.ALWAYS);
            container.getChildren().add(numberLabel);
            container.setPrefWidth(-1);
        }

        // Tooltip with the diagnostic message(s)
        String messages = syntaxDecorator.getErrorMessagesForLine(index);
        if (messages != null) {
            Tooltip tooltip = new Tooltip(messages);
            tooltip.setWrapText(true);
            tooltip.setMaxWidth(500);
            Tooltip.install(container, tooltip);
        }

        return container;
    }
}
