package com.gluonhq.netbeans.nbfx.editor.completion;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionContext;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItemKind;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionProvider;
import com.gluonhq.netbeans.nbfx.editor.processor.SourceUtils;
import com.gluonhq.netbeans.nbfx.editor.completion.support.JavaImportUtils;
import com.sun.jfx.incubator.scene.control.richtext.CaretInfo;
import com.sun.jfx.incubator.scene.control.richtext.RichTextAreaSkinHelper;
import com.sun.jfx.incubator.scene.control.richtext.VFlow;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import jfx.incubator.scene.control.richtext.CodeArea;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.CodeTextModel;
import org.openide.filesystems.FileObject;
import org.openide.util.Lookup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates code-completion requests and {@link CompletionPopup popup} lifecycle for
 * a single {@link CodeArea} editor.
 *
 * <h2>Triggers</h2>
 * <ul>
 *   <li><b>{@code Ctrl+Space}</b> — manual completion. A second press at the same
 *       caret offset switches the query type to
 *       {@link CompletionProvider#COMPLETION_ALL_QUERY_TYPE COMPLETION_ALL_QUERY_TYPE}
 *       (e.g. include private members, full type/package catalog).</li>
 *   <li><b>{@code .}</b> typed — automatically opens the popup once the dot is added
 *       in the model.</li>
 *   <li><b>{@code (}</b> typed while the popup is showing — commits the current
 *       selection instead of inserting the character.</li>
 *   <li><b>Model edits</b> while the popup is open — re-run the query so items track
 *       the typed prefix; an edit with no popup just cancels the existing request.</li>
 *   <li><b>Caret moves</b> outside the original identifier — close the popup.</li>
 *   <li><b>{@code Esc}</b> / arrow navigation — handled by {@link CompletionPopup}; the
 *       controller forwards key events to it first.</li>
 * </ul>
 *
 * <h2>Request lifecycle</h2>
 * <p>Each request is tagged with an id from {@link #requestSequence}. All valid providers are
 * passed a cancellation token comparing their id against the current one, so the later changes
 * supersede any earlier work started by an outdated edit.
 * <p>Providers are queried in parallel via {@link CompletableFuture}, their results merged
 * and {@linkplain #removeDuplicates deduplicated} (semantic items win over lexical ones with same labels),
 * then sorted by {@link CompletionItem#sortPriority sortPriority} /
 * {@link CompletionItem#sortText sortText} / {@link CompletionItem#label label}.</p>
 *
 * <h2>Commit</h2>
 * <p>{@link #commit} replaces {@code [anchorOffset, caret)} with the item's
 * {@link CompletionItem#insertText insertText} and moves the caret accordingly. If the
 * inserted text ends with {@code .} (e.g. a package or qualified-type item) the
 * popup is reopened automatically so the user can start selecting the next qualifier without
 * pressing {@code Ctrl+Space} again.</p>
 *
 * <h2>Threading</h2>
 * <p>The controller runs entirely on the JavaFX application thread; provider queries
 * execute on the {@link CompletableFuture#supplyAsync default async pool} and re-enter
 * the FX thread via {@link Platform#runLater} before touching the popup or model.</p>
 */
public final class CompletionController {

    private static final Logger LOG = Logger.getLogger(CompletionController.class.getName());

    /** Ctrl+Space is the key combination that shows the popup */
    private static final KeyCombination POPUP_SHORTCUT =
            new KeyCodeCombination(KeyCode.SPACE, KeyCombination.CONTROL_DOWN);

    private static final double POPUP_CARET_GAP = 2.0;

    private final FileObject fileObject;
    private final CodeArea codeArea;
    private final CodeTextModel model;
    private final CompletionPopup popup;

    private final AtomicLong requestSequence = new AtomicLong();
    private int lastCompletionAnchorOffset = -1;
    private int lastShortcutCaretOffset = -1;
    private int currentCompletionQueryType = CompletionProvider.COMPLETION_QUERY_TYPE;
    private VFlow vFlow;

    /**
     * Creates a controller bound to the given editor. Call {@link #install()} to register
     * the handlers and start listening for keystrokes.
     */
    public CompletionController(FileObject fileObject, CodeArea codeArea, CodeTextModel model) {
        this.fileObject = fileObject;
        this.codeArea = codeArea;
        this.model = model;
        popup = new CompletionPopup();
    }

    /**
     * Wires the controller into the {@link CodeArea}: installs the key handlers that
     * trigger completion, listens for model edits to refresh or cancel the popup, and
     * closes the popup when the caret leaves the current identifier.
     */
    public void install() {
        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        codeArea.addEventFilter(KeyEvent.KEY_TYPED, this::onKeyTyped);
        model.addListener(ch -> {
            if (ch.isEdit() && popup.isShowing()) {
                // Run on next pulse so caret/text reflect the applied edit.
                Platform.runLater(() -> {
                    if (popup.isShowing()) {
                        runCompletionProcess(currentCompletionQueryType);
                    }
                });
            } else if (ch.isEdit()) {
                // Popup not open: just cancel any pending request
                requestSequence.incrementAndGet();
            }
        });
        codeArea.caretPositionProperty().subscribe((_, _) -> {
            if (popup.isShowing()) {
                // Close popup if caret moved to a different identifier
                TextPos caret = codeArea.getCaretPosition();
                int anchorOffset = SourceUtils.findIdentifierAnchor(model, caret);

                // If caret moved outside the original identifier context, close popup
                if (lastCompletionAnchorOffset != anchorOffset) {
                    cancelActiveRequestAndHidePopup();
                }
            }
        });
    }

    /**
     * KEY_TYPED handler for the popup triggers.
     *
     * <p>When the popup is showing, typing {@code (} or {@code .} commits the current selection
     * (like Enter) instead of inserting the raw character:</p>
     * <ul>
     *   <li>{@code (} — commits the selection as-is (a method item already inserts its own
     *       {@code (}).</li>
     *   <li>{@code .} — commits the selection and, per {@link #insertionForTypedDot}, re-adds a
     *       trailing {@code .} for fields, variables, types, packages and no-arg value-returning
     *       methods so the popup chains into the next segment (e.g. {@code stag|} + {@code .} →
     *       {@code stage.|}); methods with parameters keep their {@code (} and the typed {@code .}
     *       is consumed. Module items are the exception: their names embed {@code .}
     *       ({@code javafx.controls}), so a typed {@code .} narrows the open popup instead of
     *       committing.</li>
     * </ul>
     *
     * <p>When the popup is not visible, typing a {@code .} opens completion after the dot has been
     * inserted into the model.</p>
     */
    private void onKeyTyped(KeyEvent event) {
        if (event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
            return;
        }
        if (popup.isShowing() && "(".equals(event.getCharacter())) {
            event.consume();
            popup.commitSelection();
            return;
        }
        if (!".".equals(event.getCharacter())) {
            return;
        }
        if (popup.isShowing()) {
            // Treat `.` like Enter: commit the selection, then let the item's kind decide whether a
            // trailing `.` chains into the next segment. The raw `.` is consumed either way.
            // Exception: a module name (javafx.controls) embeds `.` as part of the name, so a typed
            // `.` must narrow the still-open popup instead of committing a partial name — fall through
            // to let the `.` be inserted and completion refreshed for the longer prefix.
            CompletionItem selected = popup.selectedItemForCommit();
            if (commitsOnTypedDot(selected)) {
                event.consume();
                popup.hide();
                commit(insertionForTypedDot(selected), lastCompletionAnchorOffset);
                return;
            }
        }
        // Run after the typed character has been inserted into the model.
        Platform.runLater(() -> {
            if (!popup.isShowing()) {
                currentCompletionQueryType = CompletionProvider.COMPLETION_QUERY_TYPE;
                runCompletionProcess(currentCompletionQueryType);
            }
        });
    }

    /**
     * Whether a {@code .} typed while the popup is showing should commit {@code selected} (like Enter)
     * rather than being inserted as a literal character. Returns {@code false} when there is no
     * selection, or for {@link CompletionItemKind#MODULE} items as module names embed {@code .} as part
     * of the name.
     */
    static boolean commitsOnTypedDot(CompletionItem selected) {
        return selected != null && selected.kind() != CompletionItemKind.MODULE;
    }

    /**
     * Insertion text for committing {@code item} when {@code .} was typed while the popup was
     * showing. The item's kind decides whether a trailing {@code .} is re-added so the popup can
     * immediately follow up with the next segment:
     * <ul>
     *   <li>field, variable → append {@code .} (e.g. {@code stag|} + {@code .} → {@code stage.|},
     *       or {@code System.e|} + {@code .} → {@code System.err.|}).</li>
     *   <li>type, package → append {@code .} (e.g. {@code Syst|} + {@code .} →
     *       {@code System.|}).</li>
     *   <li>no-argument method returning a value → complete the call and append {@code .} so the
     *       result can be chained (e.g. {@code stage.s|} → {@code stage.sceneProperty().|}).</li>
     *   <li>method with parameters → keep its own {@code (} so arguments can be typed; the typed
     *       {@code .} is consumed.</li>
     *   <li>everything else → inserted verbatim (Enter behavior).</li>
     * </ul>
     */
    static String insertionForTypedDot(CompletionItem item) {
        String insert = item.insertText();
        return switch (item.kind()) {
            case FIELD, VARIABLE, TYPE, PACKAGE -> appendDot(insert);
            case METHOD -> insert.endsWith("()") && isNonVoidReturn(item) ? appendDot(insert) : insert;
            default -> insert;
        };
    }

    /** True when the method item reports a non-{@code void} return type (rendered in {@code rightText}). */
    private static boolean isNonVoidReturn(CompletionItem item) {
        String returnType = item.rightText();
        return returnType != null && !returnType.isBlank() && !"void".equals(returnType);
    }

    private static String appendDot(String insert) {
        return insert.endsWith(".") ? insert : insert + ".";
    }

    /**
     * KEY_PRESSED handler: forwards navigation to the popup when it is showing,
     * refreshes results on caret movement, toggles the manual {@code Ctrl+Space}
     * (a second press at the same offset switches to "all items"), and dismisses
     * the popup on {@code Esc}.
     */
    private void onKeyPressed(KeyEvent event) {
        if (popup.isShowing() && popup.handleKeyPressed(event)) {
            event.consume();
            return;
        }
        if (popup.isShowing() && (event.getCode() == KeyCode.LEFT || event.getCode() == KeyCode.RIGHT)) {
            // Let the caret move first, then refresh completion for the updated prefix.
            Platform.runLater(() -> {
                if (popup.isShowing()) {
                    runCompletionProcess(currentCompletionQueryType);
                }
            });
            return;
        }
        if (POPUP_SHORTCUT.match(event)) {
            event.consume();
            int caretOffset = SourceUtils.toGlobalOffset(model, codeArea.getCaretPosition());
            currentCompletionQueryType = popup.isShowing() && caretOffset == lastShortcutCaretOffset
                    ? CompletionProvider.COMPLETION_ALL_QUERY_TYPE
                    : CompletionProvider.COMPLETION_QUERY_TYPE;
            lastShortcutCaretOffset = caretOffset;
            runCompletionProcess(currentCompletionQueryType, true);
        } else if (event.getCode() == KeyCode.ESCAPE) {
            cancelActiveRequestAndHidePopup();
        }
    }

    /**
     * Builds a {@link CompletionContext} from the current caret state, retrieves every
     * registered {@link CompletionProvider} that supports the file in parallel, runs the async query on them, and
     * applies the result to the popup after merging and sorting the prioritized results.
     */
    private void runCompletionProcess(int queryType) {
        runCompletionProcess(queryType, false);
    }

    /**
     * Runs the completion query. When {@code allowWidenOnEmpty} is set and a focused
     * ({@link CompletionProvider#COMPLETION_QUERY_TYPE}) query yields no items while no popup is showing,
     * the query is automatically re-run as {@link CompletionProvider#COMPLETION_ALL_QUERY_TYPE} so classpath-wide
     * proposals can still be offered without the user having to press {@code Ctrl+Space} a second time.
     */
    private void runCompletionProcess(int queryType, boolean allowWidenOnEmpty) {
        // 1. Create context
        TextPos caret = codeArea.getCaretPosition();
        int caretOffset = SourceUtils.toGlobalOffset(model, caret);
        String source = SourceUtils.getFullText(model);
        int anchorOffset = SourceUtils.findIdentifierAnchor(source, caretOffset);
        String identifierAtAnchor = SourceUtils.identifierAtAnchor(source, anchorOffset);

        CompletionContext context = new CompletionContext(fileObject, source, caretOffset, anchorOffset,
                queryType);

        // 2. Find providers
        List<? extends CompletionProvider> providers = Lookup.getDefault().lookupAll(CompletionProvider.class)
                .stream()
                .filter(p -> p.supports(context))
                .toList();

        if (providers.isEmpty()) {
            cancelActiveRequestAndHidePopup();
            return;
        }

        // 3. Run queries for each provider, retrieving a list of futures, each with a list of completion suggestions
        long requestId = requestSequence.incrementAndGet();
        lastCompletionAnchorOffset = anchorOffset;
        boolean showAllItems = queryType == CompletionProvider.COMPLETION_ALL_QUERY_TYPE;
        List<CompletableFuture<List<CompletionItem>>> futures = providers.stream()
                .map(provider -> provider
                        .query(context, () -> requestId != requestSequence.get())
                        .exceptionally(ex -> {
                            LOG.log(Level.WARNING,"Completion provider failed: " + ex.getMessage(), ex);
                            return List.of();
                        }))
                .toList();

        // 4. When all the queries are ready, merge then, remove duplicates, sort by priority, and then
        // show the popup or update it, if it was already showing.
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(_ -> futures.stream()
                        .flatMap(f -> f.join().stream()).toList())
                .thenAccept(items -> {
                    if (requestId != requestSequence.get()) {
                        return;
                    }
                    List<CompletionItem> sorted = new ArrayList<>(removeDuplicates(items));
                    sorted.sort(Comparator.comparingInt(CompletionItem::sortPriority)
                            .thenComparing(CompletionItem::sortText)
                            .thenComparing(CompletionItem::label));
                    Platform.runLater(() -> {
                        if (requestId == requestSequence.get()) {
                            if (popup.isShowing()) {
                                // Update existing popup with new items
                                popup.updateItems(sorted, item -> commit(item, anchorOffset),
                                        showAllItems, context.prefix(), identifierAtAnchor);
                            } else if (sorted.isEmpty() && allowWidenOnEmpty
                                    && queryType == CompletionProvider.COMPLETION_QUERY_TYPE) {
                                // Focused query found nothing: widen to the full classpath catalog so an
                                // un-imported type can still be offered on this first Ctrl+Space.
                                currentCompletionQueryType = CompletionProvider.COMPLETION_ALL_QUERY_TYPE;
                                lastShortcutCaretOffset = caretOffset;
                                runCompletionProcess(CompletionProvider.COMPLETION_ALL_QUERY_TYPE);
                            } else {
                                // Show new popup
                                showPopup(sorted, anchorOffset, showAllItems, context.prefix(), identifierAtAnchor);
                            }
                        }
                    });
                });
    }

    /**
     * Removes duplicated items across providers, keeping the entry with the lower
     * {@link CompletionItem#sortPriority()} (semantic items win over lexical token-scan items).
     */
    private static List<CompletionItem> removeDuplicates(List<CompletionItem> items) {
        Map<String, CompletionItem> priorityMap = new LinkedHashMap<>();
        for (CompletionItem item : items) {
            String key = item.label() + "\u0000" + item.leftText();
            CompletionItem existing = priorityMap.get(key);
            if (existing == null || item.sortPriority() < existing.sortPriority()) {
                priorityMap.put(key, item);
            }
        }
        return List.copyOf(priorityMap.values());
    }

    /**
     * Shows the popup anchored under the caret; closes it instead when {@code items} is empty or
     * the caret has no screen position.
     */
    private void showPopup(List<CompletionItem> items, int anchorOffset,
                           boolean showAllItems, String prefix, String identifierAtAnchor) {
        if (items == null || items.isEmpty()) {
            cancelActiveRequestAndHidePopup();
            return;
        }

        Bounds areaScreen = codeArea.localToScreen(codeArea.getBoundsInLocal());
        if (areaScreen == null) {
            return;
        }

        Point2D caretAnchor = resolveAnchorFromVFlowCaretInfo(areaScreen);
        if (caretAnchor == null) {
            return;
        }
        popup.show(codeArea, caretAnchor.getX(), caretAnchor.getY(), items,
                    item -> commit(item, anchorOffset), showAllItems, prefix, identifierAtAnchor);
    }

    /**
     * Cancels any provider active work (by bumping {@link #requestSequence}), resets
     * the manual-shortcut toggle, and hides the popup.
     */
    private void cancelActiveRequestAndHidePopup() {
        requestSequence.incrementAndGet();
        currentCompletionQueryType = CompletionProvider.COMPLETION_QUERY_TYPE;
        lastShortcutCaretOffset = -1;
        popup.hide();
    }


    /**
     * Resolves the point right under the caret to anchor the popup, accessing the
     * {@code RichTextArea} skin's {@link VFlow}, for the caret rectangle, that
     * is translated to screen coordinates.
     */
    private Point2D resolveAnchorFromVFlowCaretInfo(Bounds areaScreen) {
        try {
            if (vFlow == null) {
                vFlow = RichTextAreaSkinHelper.getVFlow(codeArea);
            }
            if (vFlow == null) {
                return null;
            }

            CaretInfo caretInfo = vFlow.getCaretInfo();
            if (caretInfo == null) {
                return null;
            }
            Point2D screenPoint = vFlow.getContentPane().localToScreen(caretInfo.getMinX(),
                    caretInfo.getMaxY() + POPUP_CARET_GAP);
            if (screenPoint == null) {
                return null;
            }
            if (screenPoint.getX() < areaScreen.getMinX() || screenPoint.getX() > areaScreen.getMaxX()) {
                return null;
            }
            if (screenPoint.getY() < areaScreen.getMinY() || screenPoint.getY() > areaScreen.getMaxY() + POPUP_CARET_GAP) {
                return null;
            }
            return screenPoint;
        } catch (RuntimeException ex) {
            LOG.fine(() -> "Falling back from VFlow caret info anchor: " + ex.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Replaces {@code [anchorOffset, caret)} with {@code item.insertText()}, repositions
     * the caret after the inserted text, and hides the popup. When the inserted text
     * ends with {@code .} (e.g. a package or qualified-type item) the completion process
     * starts all over again, and the popup shows up without requiring a new gesture.
     */
    private void commit(CompletionItem item, int anchorOffset) {
        commit(Objects.requireNonNull(item).insertText(), anchorOffset);
        addMissingImport(item);
    }

    /**
     * Adds an {@code import} for a committed type whose insert text was only its simple name, when the
     * type is not already visible. The type name has just been inserted, so the caret sits after it; the
     * new import is placed above (in the import block), so the caret is shifted right by the inserted
     * length to keep it on the same text. Does nothing for non-type items, items without a
     * {@linkplain CompletionItem#qualifiedName() fully-qualified name}, or when no import is required.
     */
    private void addMissingImport(CompletionItem item) {
        if (item == null || item.kind() != CompletionItemKind.TYPE || item.qualifiedName().isBlank()) {
            return;
        }
        String source = SourceUtils.getFullText(model);
        JavaImportUtils.computeImport(source, item.qualifiedName()).ifPresent(edit -> {
            int caretOffset = SourceUtils.toGlobalOffset(model, codeArea.getCaretPosition());
            TextPos at = SourceUtils.toTextPos(model, edit.offset());
            codeArea.replaceText(at, at, edit.text());
            int shifted = edit.offset() <= caretOffset ? caretOffset + edit.text().length() : caretOffset;
            codeArea.select(SourceUtils.toTextPos(model, shifted));
        });
    }

    /**
     * Replaces {@code [anchorOffset, caret)} with {@code insertedText} and closes the popup.
     * When {@code insertedText} ends with {@code .} (e.g. a package, qualified type, or a static
     * field committed via a typed {@code .}) the completion process starts all over again, and the
     * popup shows up without requiring a new gesture.
     */
    private void commit(String insertedText, int anchorOffset) {
        TextPos start = SourceUtils.toTextPos(model, anchorOffset);
        TextPos end = codeArea.getCaretPosition();
        TextPos caretAfterInsert = codeArea.replaceText(start, end, insertedText);
        codeArea.select(caretAfterInsert);
        popup.hide();
        // Any item whose insertion ends with '.' should re-open the popup without a manual Ctrl+Space.
        if (insertedText != null && insertedText.endsWith(".")) {
            Platform.runLater(() -> {
                currentCompletionQueryType = CompletionProvider.COMPLETION_QUERY_TYPE;
                runCompletionProcess(currentCompletionQueryType);
            });
        }
    }

}
