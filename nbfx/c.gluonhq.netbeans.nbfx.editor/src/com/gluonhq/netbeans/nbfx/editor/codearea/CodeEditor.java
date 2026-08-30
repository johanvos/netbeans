package com.gluonhq.netbeans.nbfx.editor.codearea;

import com.gluonhq.netbeans.nbfx.api.EditorDocument;
import com.gluonhq.netbeans.nbfx.api.EditorSettings;
import com.gluonhq.netbeans.nbfx.editor.completion.CompletionController;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Skin;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Window;
import javafx.util.Duration;
import jfx.incubator.scene.control.richtext.CodeArea;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.CodeTextModel;
import org.netbeans.api.queries.FileEncodingQuery;
import org.openide.filesystems.FileObject;
import org.openide.util.Lookup;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A fully configured code-editor component backed by a {@link CodeArea}.
 * <p>
 * Combines lex-based syntax highlighting and Java semantic analysis,
 * and includes error-aware line numbers and diagnostic tooltips
 * </p>
 */
public class CodeEditor implements EditorDocument {

    private static final Logger LOG = Logger.getLogger(CodeEditor.class.getName());

    private final StackPane rootPane;
    // Package-private for testing
    final CodeArea codeArea;

    private final FileObject fileObject;
    /**
     * The project this document belongs to. Resolved on demand and then kept, so that the document
     * keeps naming its project while it is being closed with it. It cannot simply be resolved when
     * the editor is created: an editor restored (or opened) before its project is registered would
     * be left belonging to no project for the rest of the session.
     */
    private volatile String projectPath;
    private final ReadOnlyBooleanWrapper modified = new ReadOnlyBooleanWrapper(this, "modified", false);
    private final ReadOnlyBooleanWrapper hasSelection = new ReadOnlyBooleanWrapper(this, "hasSelection", false);

    private final MarkLineNumberDecorator lineDecorator;
    private EditorSettings editorSettings;
    private ChangeListener<Boolean> lineNumbersListener;

    /**
     * The editor content as of the last load or successful save. The document is considered
     * modified whenever the current content differs from this baseline, so that undoing edits
     * back to the saved state clears the dirty flag.
     */
    private String savedText;

    /**
     * Last known modification time of the file at the moment its content was loaded
     * (or last successfully saved). Used to detect external modifications (write conflicts).
     */
    private long lastSyncedModifiedTime;

    /**
     * Creates a new code editor for the given file.
     *
     * @param fo the file object to open in the editor
     */
    public CodeEditor(FileObject fo) {
        this.fileObject = Objects.requireNonNull(fo);
        codeArea = new CodeArea();
        codeArea.setTabSize(4);
        codeArea.setWrapText(false);
        codeArea.setHighlightCurrentParagraph(true);

        rootPane = new StackPane(codeArea);
        rootPane.getStyleClass().add("code-editor");
        rootPane.getStylesheets().add(
                Objects.requireNonNull(CodeEditor.class.getResource("codeeditor.css")).toExternalForm());

        // Set up the syntax decorator that combines lex + java analysis
        JavaSyntaxDecorator decorator = new JavaSyntaxDecorator(Objects.requireNonNull(fo));
        codeArea.setSyntaxDecorator(decorator);

        // Custom left decorator that shows line numbers + error indicators with tooltips.
        lineDecorator = new MarkLineNumberDecorator(decorator, codeArea.fontProperty());
        bindLineNumbers();

        // Loading the file is not a user edit. Disable undo/redo while setting the initial
        // content so no undo entry is created for the load
        codeArea.setUndoRedoEnabled(false);
        try {
            // Capture the initial text for later content comparison
            savedText = fo.asText();
            codeArea.setText(savedText);
        } catch (IOException ex) {
            LOG.log(Level.SEVERE, "Failed to read file content: " + fo.getNameExt(), ex);
        } finally {
            codeArea.setUndoRedoEnabled(true);
        }
        codeArea.select(TextPos.ZERO);
        lastSyncedModifiedTime = fo.lastModified().getTime();

        // Trigger initial background Java analysis after content is loaded
        CodeTextModel model = (CodeTextModel) codeArea.getModel();
        try {
            decorator.analyzeInBackground(model);
        } catch (RuntimeException ex) {
            LOG.warning("Initial Java analysis failed");
        }

        // Re-run Java analysis on subsequent edits via a model listener
        model.addListener(ch -> {
            if (ch.isEdit()) {
                // ContentChange ofEdit: dirty only when the content differs from the saved
                // baseline, so undoing back to the saved state clears the flag.
                modified.set(!codeArea.getText().equals(savedText));
                try {
                    decorator.analyzeInBackground(model);
                } catch (RuntimeException ex) {
                    LOG.warning("Java analysis on edit failed");
                }
            } else {
                // ContentChange ofStyleChange
                refreshLineDecorator();
            }
        });

        // Highlight matching braces and mark occurrences as the caret moves, only in the next pulse,
        // after edit events, in order to prevent concurrent modification exceptions.
        // Clear highlight if there is a selection
        Runnable refreshCaretHighlights = () -> Platform.runLater(() -> {
            if (codeArea.hasNonEmptySelection()) {
                decorator.clearBraceMatch(model);
                decorator.clearOccurrences(model);
            } else {
                TextPos caret = codeArea.getCaretPosition();
                decorator.updateBraceMatch(model, caret);
                decorator.updateOccurrencesInBackground(model, caret);
            }
        });
        codeArea.caretPositionProperty().subscribe((_, _) -> refreshCaretHighlights.run());
        codeArea.anchorPositionProperty().subscribe((_, _) -> refreshCaretHighlights.run());

        // Track the selection state so Copy / Cut enablement can bind to it
        hasSelection.set(codeArea.hasNonEmptySelection());
        codeArea.selectionProperty().subscribe(_ -> hasSelection.set(codeArea.hasNonEmptySelection()));

        // Tooltip for squiggly error/warning diagnostics on mouse hover
        installDiagnosticTooltip(codeArea, decorator, model);

        // Install completion controller (manual trigger: Ctrl + Space)
        new CompletionController(fo, codeArea, model).install();
    }

    /** Binds the line-number gutter to the shared {@link EditorSettings#showLineNumbers()} setting. */
    private void bindLineNumbers() {
        editorSettings = Lookup.getDefault().lookup(EditorSettings.class);
        refreshLineDecorator();
        if (editorSettings != null) {
            lineNumbersListener = (_, _, _) -> refreshLineDecorator();
            editorSettings.showLineNumbers().addListener(new WeakChangeListener<>(lineNumbersListener));
        }
    }

    /** Refreshes the line-number gutter for the current setting. */
    private void refreshLineDecorator() {
        lineDecorator.setShowLineNumbers(editorSettings == null || editorSettings.showLineNumbers().get());
        codeArea.setLeftDecorator(null);
        codeArea.setLeftDecorator(lineDecorator);
    }

    /**
     * Returns the root node of this editor, suitable for adding to a scene graph.
     *
     * @return the editor's root node
     */
    @Override
    public Node getNode() {
        return rootPane;
    }

    @Override
    public FileObject getFileObject() {
        return fileObject;
    }

    @Override
    public String getProjectPath() {
        String resolved = projectPath;
        if (resolved == null) {
            resolved = EditorDocument.super.getProjectPath();
            projectPath = resolved;
        }
        return resolved;
    }

    @Override
    public String getTitle() {
        return fileObject.getNameExt();
    }

    @Override
    public boolean isModified() {
        return modified.get();
    }

    @Override
    public ReadOnlyBooleanProperty modifiedProperty() {
        return modified.getReadOnlyProperty();
    }

    @Override
    public void undo() {
        codeArea.undo();
    }

    @Override
    public void redo() {
        codeArea.redo();
    }

    @Override
    public void copy() {
        codeArea.copy();
    }

    @Override
    public void cut() {
        codeArea.cut();
    }

    @Override
    public void paste() {
        codeArea.paste();
    }

    @Override
    public ReadOnlyBooleanProperty canUndoProperty() {
        return codeArea.undoableProperty();
    }

    @Override
    public ReadOnlyBooleanProperty canRedoProperty() {
        return codeArea.redoableProperty();
    }

    @Override
    public ReadOnlyBooleanProperty hasSelectionProperty() {
        return hasSelection.getReadOnlyProperty();
    }

    @Override
    public ReadOnlyBooleanProperty editableProperty() {
        return codeArea.editableProperty();
    }

    @Override
    public int getCaretParagraph() {
        TextPos caret = codeArea.getCaretPosition();
        return caret == null ? 0 : caret.index();
    }

    @Override
    public int getCaretColumn() {
        TextPos caret = codeArea.getCaretPosition();
        return caret == null ? 0 : caret.offset();
    }

    @Override
    public int getTopParagraph() {
        // Map the top-left of the visible text area (in screen coordinates) to a paragraph index.
        if (codeArea.getScene() == null || codeArea.getScene().getWindow() == null) {
            return getCaretParagraph();
        }
        Point2D top = codeArea.localToScreen(codeArea.getWidth() / 2, 2);
        if (top == null) {
            return getCaretParagraph();
        }
        TextPos pos = codeArea.getTextPosition(top.getX(), top.getY());
        return pos == null ? getCaretParagraph() : pos.index();
    }

    @Override
    public void restoreView(int topParagraph, int caretParagraph, int caretColumn) {
        if (topParagraph <= 0 && caretParagraph <= 0 && caretColumn <= 0) {
            return;
        }
        if (codeArea.getSkin() != null) {
            applyView(topParagraph, caretParagraph, caretColumn);
        } else {
            codeArea.skinProperty().addListener(new ChangeListener<>() {
                @Override
                public void changed(ObservableValue<? extends Skin<?>> obs, Skin<?> old, Skin<?> skin) {
                    if (skin != null) {
                        codeArea.skinProperty().removeListener(this);
                        Platform.runLater(() -> applyView(topParagraph, caretParagraph, caretColumn));
                    }
                }
            });
        }
    }

    @Override
    public void requestFocus() {
        focusWhenReady();
    }

    /** Moves keyboard focus to the code area so its caret starts blinking. */
    private void focusWhenReady() {
        if (codeArea.getSkin() == null) {
            onceReady(codeArea.skinProperty());
            return;
        }
        Scene scene = codeArea.getScene();
        if (scene == null) {
            onceReady(codeArea.sceneProperty());
            return;
        }
        Window window = scene.getWindow();
        if (window == null) {
            onceReady(scene.windowProperty());
            return;
        }
        if (!window.isFocused()) {
            // Focus the editor as soon as its window gains focus.
            window.focusedProperty().addListener(new ChangeListener<>() {
                @Override
                public void changed(ObservableValue<? extends Boolean> obs, Boolean ov, Boolean focused) {
                    if (Boolean.TRUE.equals(focused)) {
                        window.focusedProperty().removeListener(this);
                        Platform.runLater(codeArea::requestFocus);
                    }
                }
            });
            return;
        }
        Platform.runLater(codeArea::requestFocus);
    }

    /** Re-runs {@link #focusWhenReady()} once {@code property} becomes non-null. */
    private void onceReady(ObservableValue<?> property) {
        property.addListener(new InvalidationListener() {
            @Override
            public void invalidated(Observable observable) {
                if (property.getValue() != null) {
                    property.removeListener(this);
                    Platform.runLater(CodeEditor.this::focusWhenReady);
                }
            }
        });
    }

    private void applyView(int topParagraph, int caretParagraph, int caretColumn) {
        int paragraphCount = codeArea.getParagraphCount();
        if (caretParagraph < 0 || caretParagraph >= paragraphCount) {
            return;
        }
        // Clamp the column to the paragraph length, in case the file changed since it was persisted.
        TextPos caretEnd = codeArea.getParagraphEnd(caretParagraph);
        int maxColumn = caretEnd != null ? caretEnd.offset() : 0;
        int col = Math.clamp(caretColumn, 0, maxColumn);
        TextPos caret = TextPos.ofLeading(caretParagraph, col);

        int top = Math.clamp(topParagraph, 0, paragraphCount - 1);
        if (top <= 0) {
            codeArea.select(caret);
            return;
        }
        // scroll to the end first, bring it back to the top, then place the caret
        codeArea.select(codeArea.getDocumentEnd());
        Platform.runLater(() -> {
            codeArea.select(TextPos.ofLeading(top, 0));
            Platform.runLater(() -> codeArea.select(caret));
        });
    }

    /**
     * Persists the current editor content back to the {@link FileObject}.
     * <p>
     * Must be invoked on the JavaFX Application Thread, since it reads the editor content.
     * If the file was modified externally since it was opened (or last saved), the save is
     * aborted with an {@link IOException} to avoid overwriting those changes.
     *
     * @throws IOException if a write conflict is detected or writing the content fails
     */
    @Override
    public void save() throws IOException {
        if (!modified.get()) {
            return;
        }
        String text = codeArea.getText();

        long currentModifiedTime = fileObject.lastModified().getTime();
        if (currentModifiedTime != lastSyncedModifiedTime) {
            throw new IOException("File \"" + fileObject.getNameExt()
                    + "\" was modified externally since it was opened; save aborted to avoid"
                    + " overwriting those changes.");
        }

        // Write with the same encoding FileObject.asText() used to read
        Charset charset = encodingOf(fileObject);
        try (OutputStream out = fileObject.getOutputStream();
             Writer writer = new OutputStreamWriter(out, charset)) {
            writer.write(text);
        }

        savedText = text;
        lastSyncedModifiedTime = fileObject.lastModified().getTime();
        modified.set(false);
    }

    /**
     * Returns the charset {@link FileObject#asText()} uses for this file, falling back to the
     * platform default when the encoding cannot be resolved (e.g. outside the NetBeans Platform).
     */
    private static Charset encodingOf(FileObject fileObject) {
        try {
            Charset charset = FileEncodingQuery.getEncoding(fileObject);
            if (charset != null) {
                return charset;
            }
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "Could not resolve the encoding of " + fileObject.getNameExt()
                    + ", falling back to the default charset", ex);
        }
        return Charset.defaultCharset();
    }

    private static void installDiagnosticTooltip(CodeArea codeArea,
                                                 JavaSyntaxDecorator decorator,
                                                 CodeTextModel model) {
        Tooltip errorTooltip = new Tooltip();
        errorTooltip.setShowDelay(Duration.millis(300));
        errorTooltip.setHideDelay(Duration.millis(200));
        errorTooltip.setWrapText(true);
        errorTooltip.setMaxWidth(500);

        codeArea.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            TextPos pos = codeArea.getTextPosition(e.getScreenX(), e.getScreenY());
            if (pos != null) {
                String message = decorator.getErrorMessageAt(pos.index(), pos.charIndex());
                if (message != null) {
                    if (!message.equals(errorTooltip.getText()) || !errorTooltip.isShowing()) {
                        errorTooltip.setText(message);
                        errorTooltip.show(codeArea, e.getScreenX() + 10, e.getScreenY() + 15);
                    }
                    return;
                }
            }
            errorTooltip.hide();
        });

        codeArea.addEventHandler(MouseEvent.MOUSE_EXITED, e -> errorTooltip.hide());

        // Hide tooltip when content scrolls (squiggly moves but mouse hasn't)
        codeArea.addEventFilter(ScrollEvent.SCROLL, e -> errorTooltip.hide());

        // Hide tooltip when the user types (error may be fixed after re-analysis)
        model.addListener(ch -> {
            if (ch.isEdit()) {
                errorTooltip.hide();
            }
        });
    }

}
