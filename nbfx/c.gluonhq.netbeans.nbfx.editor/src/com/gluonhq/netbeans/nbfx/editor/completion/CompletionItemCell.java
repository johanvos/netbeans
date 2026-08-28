package com.gluonhq.netbeans.nbfx.editor.completion;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItemKind;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionTypeKind;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.css.PseudoClass;

import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders one completion item with icon, primary label to the left, and right-side text.
 * It maps completion kinds to icons and caches images.
 * <p>The cell also manages selection, where two clicks are needed to commit the insert
 * text.</p>
 */
final class CompletionItemCell extends ListCell<CompletionItem> {

    private static final double ICON_SIZE = 16;
    private static final PseudoClass EMPHASIZED = PseudoClass.getPseudoClass("emphasized");
    private static final PseudoClass SEPARATOR = PseudoClass.getPseudoClass("separator");
    private static final Map<String, Image> ICON_CACHE = new ConcurrentHashMap<>();

    private static final int MAX_MEASURED_WIDTH_CACHE_SIZE = 4096;
    private static final Map<CompletionItem, Integer> MEASURED_WIDTH_CACHE = new ConcurrentHashMap<>();

    private final ImageView iconView;
    private final Text leftText, rightText;
    private final Region separatorLine;
    private final HBox row;

    private boolean wasSelected = false;

    CompletionItemCell() {
        this(null);
    }

    CompletionItemCell(Runnable onCommit) {
        iconView = new ImageView();
        iconView.getStyleClass().add("completion-popup-icon");
        iconView.setFitWidth(ICON_SIZE);
        iconView.setFitHeight(ICON_SIZE);
        iconView.setPreserveRatio(true);
        leftText = new Text();
        leftText.getStyleClass().add("completion-popup-text");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        rightText = new Text();
        rightText.getStyleClass().add("completion-popup-detail");
        row = new HBox(8, iconView, leftText, spacer, rightText);
        row.getStyleClass().add("completion-popup-row");

        separatorLine = new Region();
        separatorLine.getStyleClass().add("completion-popup-separator");

        getStyleClass().add("completion-popup-cell");
        addEventFilter(MouseEvent.MOUSE_PRESSED,
                _ -> wasSelected = getListView().getSelectionModel().isSelected(getIndex()));
        // One click just selects, two clicks (either double-click or separated second click) commits.
        setOnMouseClicked(event -> {
            if (!isEmpty() && getItem() != null && onCommit != null) {
                if (event.getClickCount() > 1 || wasSelected) {
                    onCommit.run();
                }
            }
        });
    }

    @Override
    protected void updateItem(CompletionItem item, boolean empty) {
        super.updateItem(item, empty);
        setText(null);
        setDisable(false);
        pseudoClassStateChanged(SEPARATOR, item != null && item.kind() == CompletionItemKind.SEPARATOR);
        if (empty || item == null) {
            setGraphic(null);
            return;
        }

        if (item.kind() == CompletionItemKind.SEPARATOR) {
            setGraphic(separatorLine);
            setDisable(true);
            return;
        }

        updateGlyph(item);
        leftText.setText(item.leftText());
        leftText.pseudoClassStateChanged(EMPHASIZED, item.emphasized());
        leftText.setStrikethrough(item.deprecated());
        rightText.setText(item.rightText());
        rightText.setManaged(!item.rightText().isBlank());
        rightText.setVisible(!item.rightText().isBlank());

        setGraphic(row);
    }

    private void updateGlyph(CompletionItem item) {
        String iconName = iconNameFor(item);
        iconView.setImage(iconName == null ? null : ICON_CACHE.computeIfAbsent(iconName, CompletionItemCell::loadIcon));
    }

    /** Maps a completion item kind / type-kind to the icon file shipped in this package. */
    private static String iconNameFor(CompletionItem item) {
        return switch (item.kind()) {
            case METHOD -> item.typeKind() == CompletionTypeKind.CONSTRUCTOR
                    ? memberIcon("constructor", item.modifiers())
                    : memberIcon("method", item.modifiers());
            case FIELD -> memberIcon("field", item.modifiers());
            case PACKAGE -> "package.png";
            case MODULE -> "module.png";
            case TYPE -> switch (item.typeKind()) {
                case INTERFACE -> "interface.png";
                case ENUM -> "enum.png";
                case RECORD -> "record.png";
                case ANNOTATION, CLASS, OTHER, CONSTRUCTOR -> "class_16.png";
            };
            case VARIABLE, KEYWORD -> "localVariable.png";
            case SEPARATOR, OTHER -> null;
        };
    }

    /** Builds the field / method / constructor icon name from {@code modifiers} (visibility + static flag). */
    private static String memberIcon(String base, int modifiers) {
        String accessSuffix = accessSuffix(modifiers);
        if ("constructor".equals(base)) {
            return "constructor" + accessSuffix + "_16.png";
        }
        if (Modifier.isStatic(modifiers)) {
            return base + "_static" + accessSuffix + "_16.png";
        }
        return base + accessSuffix + "_16.png";
    }

    /** Maps Java reflection access bits onto the icon-name suffix (private / protected / package-private). */
    private static String accessSuffix(int modifiers) {
        if (Modifier.isPrivate(modifiers)) {
            return "_private";
        }
        if (Modifier.isProtected(modifiers)) {
            return "_protected";
        }
        if (Modifier.isPublic(modifiers)) {
            return "";
        }
        return "_package_private";
    }

    private static Image loadIcon(String iconName) {
        try {
            return new Image(Objects.requireNonNull(CompletionItemCell.class.getResource(iconName)).toExternalForm());
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Returns the off-screen rendered width of a cell laid out with {@code item}, used
     * to size the popup. Results are cached by item as the same item can occur across queries.
     */
    static int measureCellLength(CompletionItem item) {
        Integer cached = MEASURED_WIDTH_CACHE.get(item);
        if (cached != null) {
            return cached;
        }
        CompletionItemCell cell = new CompletionItemCell();
        cell.updateItem(item, false);

        Group group = new Group(cell);
        Scene scene = new Scene(group);
        scene.getStylesheets().add(
                Objects.requireNonNull(CompletionItemCell.class.getResource("completion-popup.css")).toExternalForm());
        group.applyCss();
        group.layout();
        int width = (int) Math.ceil(group.getLayoutBounds().getWidth());
        if (MEASURED_WIDTH_CACHE.size() >= MAX_MEASURED_WIDTH_CACHE_SIZE) {
            MEASURED_WIDTH_CACHE.clear();
        }
        MEASURED_WIDTH_CACHE.put(item, width);
        return width;
    }
}

