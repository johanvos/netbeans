package com.gluonhq.netbeans.nbfx.editor.completion;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionContext;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItemKind;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PopupControl;
import javafx.scene.control.Skin;
import javafx.scene.control.skin.ListViewSkin;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/**
 * JavaFX popup that displays and commits completion proposals.
 *
 * <p>The popup keeps keyboard and mouse interaction stable while the backing items
 * are refreshed after each typed character. Initial selection is prefix-aware:
 * case-sensitive prefix matches are preferred first, then case-insensitive matches,
 * then the first selectable item.
 *
 * <p>Opening triggers:
 * <ul>
 *   <li>explicit completion shortcut in the editor (Ctrl+Space)</li>
 *   <li>automatic member completion trigger after typing {@code .}</li>
 * </ul>
 *
 * <p>The user can keep typing while the popup is open. After each typed character,
 * completion is queried again, items are refreshed, and preferred selection is recomputed.
 *
 * <p>Keyboard handling ({@link #handleKeyPressed(KeyEvent)}):
 * <ul>
 *   <li>{@code LEFT}/{@code RIGHT}: move the cursor by one character in the code editor:
 *       the {@link CompletionContext#prefix()} is updated,
 *       {@link com.gluonhq.netbeans.nbfx.api.completion.CompletionProvider#query} runs again, and the popup content
 *       is updated</li>
 *   <li>typing {@code (}: commit the selected completion item, then insert {@code (}</li>
 *   <li>{@code UP}/{@code DOWN}: move selection in the popup by one row</li>
 *   <li>{@code PAGE_UP}/{@code PAGE_DOWN}: move by a visible page</li>
 *   <li>{@code HOME}/{@code END}: jump to first/last row in the popup</li>
 *   <li>{@code ESCAPE}: close popup without commit</li>
 *   <li>{@code ENTER} or {@code TAB}: commit selected item so the text in the editor gets completed.
 *        If the selection is a package name, a {@code .} is added, so the popup will be shown again.</li>
 * </ul>
 *
 * <p>Mouse handling (via cell click callback):
 * <ul>
 *   <li>single click: select row only</li>
 *   <li>second separated click on same row: commit selected item</li>
 *   <li>double click: commit immediately</li>
 * </ul>
 */
final class CompletionPopup extends PopupControl {

    private static final int MAX_VISIBLE_ROWS = 12;
    private static final double ROW_HEIGHT = 24;
    private static final double MIN_WIDTH = 220;
    private static final double MAX_WIDTH = 800;
    private static final ResourceBundle BUNDLE =
            ResourceBundle.getBundle("com.gluonhq.netbeans.nbfx.editor.completion.CompletionUI");
    private static volatile int cachedFooterWidth = -1;

    private final PopupListView listView;
    private final Label footerLabel;
    private final int footerWidth;
    private Consumer<CompletionItem> commitHandler = _ -> {};

    CompletionPopup() {
        setAutoFix(true);
        setAutoHide(true);
        setHideOnEscape(true);

        listView = new PopupListView();
        listView.setCellFactory(_ -> new CompletionItemCell(this::commitSelection));

        footerLabel = new Label(BUNDLE.getString("completion.popup.instance.members.hint"));
        footerLabel.getStyleClass().add("completion-popup-footer");
        footerLabel.setWrapText(false);
        footerLabel.setMaxWidth(Double.MAX_VALUE);
        footerLabel.setManaged(false);
        footerLabel.setVisible(false);

        VBox root = new VBox(listView, footerLabel);
        root.getStyleClass().add("completion-popup-root");
        root.getStylesheets().add(
                Objects.requireNonNull(CompletionPopup.class.getResource("completion-popup.css")).toExternalForm());

        getStyleClass().add("completion-popup");
        getScene().setRoot(root);

        footerWidth = measureFooterWidth();
    }

    void show(Node owner, double anchorX, double anchorY,
              List<CompletionItem> items, Consumer<CompletionItem> onCommit,
              boolean showAllItems, String prefix, String identifierAtAnchor) {
        commitHandler = onCommit == null ? _ -> {} : onCommit;

        ObservableList<CompletionItem> rows = FXCollections.observableArrayList(items);
        listView.setItems(rows);
        updateFooter(showAllItems);
        configureSize(items, showAllItems);

        if (!rows.isEmpty()) {
            int preferredIndex = findPreferredSelectionIndex(rows, prefix, identifierAtAnchor);
            listView.selectAndScrollTo(preferredIndex);
        }

        if (isShowing()) {
            hide();
        }
        super.show(owner, anchorX, anchorY);
    }

    void updateItems(List<CompletionItem> items, Consumer<CompletionItem> onCommit,
                     boolean showAllItems, String prefix, String identifierAtAnchor) {
        if (!isShowing()) {
            return;
        }

        commitHandler = onCommit == null ? _ -> {} : onCommit;

        ObservableList<CompletionItem> rows = listView.getItems();
        boolean footerVisible = !showAllItems;
        boolean footerChanged = footerLabel.isVisible() != footerVisible;

        boolean itemsChanged = !sameItems(rows, items);
        if (itemsChanged) {
            rows.setAll(items);
        }

        updateFooter(showAllItems);
        if (itemsChanged || footerChanged) {
            configureSize(items, showAllItems);
        }

        if (rows.isEmpty()) {
            listView.getSelectionModel().clearSelection();
            return;
        }

        if (itemsChanged) {
            int preferredIndex = findPreferredSelectionIndex(rows, prefix, identifierAtAnchor);
            listView.selectAndScrollTo(preferredIndex);
        }
    }

    /**
     * Returns true if the key event is handled by this popup. False if it is not,
     * so the caller can still handle it: LEFT/RIGHT to move the caret and update the completion context,
     * or '(' to commit and insert parentheses.
     */
    boolean handleKeyPressed(KeyEvent event) {
        return switch (event.getCode()) {
            case UP -> {
                selectRelative(-1);
                yield true;
            }
            case DOWN -> {
                selectRelative(1);
                yield true;
            }
            case PAGE_UP -> {
                selectRelative(-listView.visibleRowCount());
                yield true;
            }
            case PAGE_DOWN -> {
                selectRelative(listView.visibleRowCount());
                yield true;
            }
            case HOME -> {
                selectAbsolute(0);
                yield true;
            }
            case END -> {
                selectAbsolute(listView.getItems().size() - 1);
                yield true;
            }
            case ENTER, TAB -> {
                commitSelection();
                yield true;
            }
            case ESCAPE -> {
                hide();
                yield true;
            }
            default -> false;
        };
    }

    void commitSelection() {
        CompletionItem selected = selectedItemForCommit();
        if (selected != null) {
            commitHandler.accept(selected);
        }
        hide();
    }

    /**
     * Returns the item that {@link #commitSelection()} would commit: the current selection,
     * or the first row when nothing is selected. Returns {@code null} when there is nothing
     * committable (empty list or a separator).
     */
    CompletionItem selectedItemForCommit() {
        CompletionItem selected = listView.getSelectionModel().getSelectedItem();
        if (selected == null && !listView.getItems().isEmpty()) {
            selected = listView.getItems().getFirst();
        }
        if (selected != null && selected.kind() != CompletionItemKind.SEPARATOR) {
            return selected;
        }
        return null;
    }

    /**
     * Returns the row index that should be selected on a popup refresh, scored so
     * that exact identifier hits (case-sensitive then case-insensitive) win over
     * prefix matches, and falls back to the first selectable (non-separator) row
     * when nothing matches.
     */
    private static int findPreferredSelectionIndex(List<CompletionItem> rows, String prefix, String identifierAtAnchor) {
        String identifier = identifierAtAnchor == null ? "" : identifierAtAnchor;
        String idLower = identifier.toLowerCase(Locale.ROOT);
        String pref = prefix == null ? "" : prefix;
        String prefLower = pref.toLowerCase(Locale.ROOT);

        int bestIndex = -1;
        int bestScore = Integer.MAX_VALUE;
        int firstSelectable = -1;
        // Lower score = better match (5 = no signal, 1 = exact identifier hit).
        for (int i = 0; i < rows.size(); i++) {
            CompletionItem item = rows.get(i);
            if (item.kind() == CompletionItemKind.SEPARATOR) {
                continue;
            }
            if (firstSelectable < 0) {
                firstSelectable = i;
            }
            String left = item.leftText();
            int score;
            if (!identifier.isBlank() && identifier.equals(left)) {
                score = 1;
            } else if (!identifier.isBlank() && idLower.equals(left.toLowerCase(Locale.ROOT))) {
                score = 2;
            } else if (!pref.isBlank() && left.startsWith(pref)) {
                score = 3;
            } else if (!pref.isBlank() && left.toLowerCase(Locale.ROOT).startsWith(prefLower)) {
                score = 4;
            } else {
                continue;
            }
            if (score < bestScore) {
                bestScore = score;
                bestIndex = i;
                if (score == 1) break;
            }
        }
        if (bestIndex >= 0) return bestIndex;
        return Math.max(firstSelectable, 0);
    }

    private void updateFooter(boolean showAllItems) {
        footerLabel.setVisible(!showAllItems);
        footerLabel.setManaged(!showAllItems);
    }

    /** Element-wise equality used to detect a no-op refresh and keep the selection / scroll stable. */
    private static boolean sameItems(List<CompletionItem> previous, List<CompletionItem> next) {
        if (previous.size() != next.size()) {
            return false;
        }
        for (int i = 0; i < previous.size(); i++) {
            if (!Objects.equals(previous.get(i), next.get(i))) {
                return false;
            }
        }
        return true;
    }

    private void selectRelative(int delta) {
        int size = listView.getItems().size();
        if (size == 0) {
            return;
        }
        int current = listView.getSelectionModel().getSelectedIndex();
        if (current < 0) {
            current = 0;
        }
        int next = Math.clamp(current + delta, 0, size - 1);
        listView.selectAndScrollTo(next);
    }

    private void selectAbsolute(int index) {
        int size = listView.getItems().size();
        if (size == 0) {
            return;
        }
        int safe = Math.clamp(index, 0, size - 1);
        listView.selectAndScrollTo(safe);
    }

    private void configureSize(List<CompletionItem> items, boolean showAllItems) {
        boolean hasSeparator = items.stream().anyMatch(item -> item.kind() == CompletionItemKind.SEPARATOR);
        int rowCount = Math.clamp(items.size() - (hasSeparator ? 1 : 0), 1, MAX_VISIBLE_ROWS);
        listView.setPrefHeight(rowCount * ROW_HEIGHT + (hasSeparator ? 3 : 0));

        if (items.isEmpty()) {
            listView.setPrefWidth(MIN_WIDTH);
            return;
        }

        CompletionItem maxCompletionItem = items.stream().max(Comparator.comparing(CompletionItem::length)).orElseThrow();
        int footerWidth = showAllItems ? 0 : this.footerWidth;
        // prevent horizontal scrollbar from appearing
        double width = Math.max(CompletionItemCell.measureCellLength(maxCompletionItem), footerWidth) + 16;
        // Ensure footer label is fully visible
        double minWidth = showAllItems ? MIN_WIDTH : Math.max(MIN_WIDTH, footerWidth);
        listView.setPrefWidth(Math.clamp(width, minWidth, MAX_WIDTH));
        footerLabel.setPrefWidth(listView.getPrefWidth());
    }

    private static int measureFooterWidth() {
        if (cachedFooterWidth > 0) {
            return cachedFooterWidth;
        }
        Label label = new Label(BUNDLE.getString("completion.popup.instance.members.hint"));
        label.getStyleClass().add("completion-popup-footer");
        label.setWrapText(false);
        Group group = new Group(label);
        Scene scene = new Scene(group);
        scene.getStylesheets().add(
                Objects.requireNonNull(CompletionPopup.class.getResource("completion-popup.css")).toExternalForm());
        group.applyCss();
        group.layout();
        cachedFooterWidth = (int) Math.ceil(group.getLayoutBounds().getWidth());
        return cachedFooterWidth;
    }

    private static class PopupListView extends ListView<CompletionItem> {

        private PopupListViewSkin popupListViewSkin;

        PopupListView() {
            setFocusTraversable(false);
            getStyleClass().add("completion-popup-list");
            Label emptyLabel = new Label(BUNDLE.getString("completion.popup.no.suggestions"));
            emptyLabel.getStyleClass().add("completion-popup-placeholder");
            setPlaceholder(emptyLabel);
        }

        @Override
        protected Skin<?> createDefaultSkin() {
            popupListViewSkin = new PopupListViewSkin(this);
            return popupListViewSkin;
        }

        void selectAndScrollTo(int next) {
            getSelectionModel().select(next);
            if (popupListViewSkin != null) {
                popupListViewSkin.ensureVisible(next);
            }
        }

        /**
         * Number of rows currently spanned by the viewport, falling back to {@link #MAX_VISIBLE_ROWS}
         * before the skin has laid out its cells. Used to page the selection by the true viewport
         * height rather than a fixed guess.
         */
        int visibleRowCount() {
            int count = popupListViewSkin == null ? 0 : popupListViewSkin.visibleRowCount();
            return count > 0 ? count : MAX_VISIBLE_ROWS;
        }
    }

    private static class PopupListViewSkin extends ListViewSkin<CompletionItem> {

        private final VirtualFlow<ListCell<CompletionItem>> virtualFlow;

        PopupListViewSkin(ListView<CompletionItem> control) {
            super(control);
            virtualFlow = getVirtualFlow();
        }

        /** Number of rows currently spanned by the viewport, or {@code 0} before the cells are laid out. */
        int visibleRowCount() {
            if (virtualFlow == null) {
                return 0;
            }
            IndexedCell<?> first = virtualFlow.getFirstVisibleCell();
            IndexedCell<?> last = virtualFlow.getLastVisibleCell();
            if (first == null || last == null) {
                return 0;
            }
            return last.getIndex() - first.getIndex() + 1;
        }

        /** Scrolls the list only when {@code index} is not already fully visible. */
        void ensureVisible(int index) {
            if (index < 0 || virtualFlow == null) {
                return;
            }
            IndexedCell<?> first = virtualFlow.getFirstVisibleCell();
            if (first != null) {
                Bounds firstBounds = virtualFlow.sceneToLocal(first.localToScene(first.getLayoutBounds()));
                double cellHeight = firstBounds.getHeight() > 0 ? firstBounds.getHeight() : ROW_HEIGHT;
                double cellMinY = firstBounds.getMinY() + (index - first.getIndex()) * cellHeight;
                double cellMaxY = cellMinY + cellHeight;
                if (cellMinY >= -0.5 && cellMaxY <= virtualFlow.getHeight() + 0.5) {
                    return;
                }
            }
            virtualFlow.scrollTo(index);
        }

    }
}
