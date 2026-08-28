package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionContext;

import java.util.Objects;

/**
 * Classifies the {@code import} declaration situation at the completion caret, identifying whether the line
 * is a static import, a qualified path, or a bare top-level identifier. Use {@link #from(CompletionContext)}
 * to parse a context in a single pass.
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>{@code import |}, {@code import javafx|} - {@link Kind#TOP_LEVEL}</li>
 *   <li>{@code import static |}, {@code import static javafx|} - {@link Kind#TOP_LEVEL_STATIC}</li>
 *   <li>{@code import javafx.scene.|} - {@link Kind#QUALIFIED_PACKAGE}</li>
 *   <li>{@code import static javafx.animation.Animation.|} - {@link Kind#QUALIFIED_STATIC}</li>
 * </ul>
 */
public record JavaImportContext(Kind kind) {

    public enum Kind {
        /** Not inside an {@code import} declaration. */
        NONE,
        /** A bare identifier is being typed after {@code import }. No {@code static}, no {@code .}. */
        TOP_LEVEL,
        /** Same as {@link #TOP_LEVEL} but {@code static} has already been typed as a full token. */
        TOP_LEVEL_STATIC,
        /** A dotted path is being typed after {@code import }. No {@code static}. */
        QUALIFIED_PACKAGE,
        /** A dotted path is being typed after {@code import static }. */
        QUALIFIED_STATIC
    }

    private static final String IMPORT_KEYWORD = "import ";
    private static final String STATIC_KEYWORD = "static";

    /** Shared sentinel for "no import at the caret". */
    public static final JavaImportContext NONE = new JavaImportContext(Kind.NONE);

    /** True when the caret is somewhere inside an {@code import} declaration. */
    public boolean isImport() {
        return kind != Kind.NONE;
    }

    /** True when {@code static} has already been typed (qualified or top-level flavor). */
    public boolean isStatic() {
        return kind == Kind.TOP_LEVEL_STATIC || kind == Kind.QUALIFIED_STATIC;
    }

    /** True when the line already contains a {@code .} (dotted-path completion). */
    public boolean isQualified() {
        return kind == Kind.QUALIFIED_PACKAGE || kind == Kind.QUALIFIED_STATIC;
    }

    /** True when the user is still typing the first identifier (no dot yet). */
    public boolean isTopLevel() {
        return kind == Kind.TOP_LEVEL || kind == Kind.TOP_LEVEL_STATIC;
    }

    /**
     * Parses {@link CompletionContext#linePrefixBeforeAnchor()} once and classifies it
     * according to the types defined by {@link Kind}.
     */
    public static JavaImportContext from(CompletionContext context) {
        Objects.requireNonNull(context);
        String linePrefix = context.linePrefixBeforeAnchor().stripLeading();
        if (!linePrefix.startsWith(IMPORT_KEYWORD)) {
            return NONE;
        }
        boolean hasDot = context.hasCharBeforeAnchor('.');
        String tail = linePrefix.substring(IMPORT_KEYWORD.length()).stripLeading();
        boolean staticTyped = false;
        if (tail.startsWith(STATIC_KEYWORD)) {
            int after = STATIC_KEYWORD.length();
            if (after == tail.length()) {
                // `import static|` — `static` is still being typed; treat as no import yet.
                return NONE;
            }
            if (Character.isWhitespace(tail.charAt(after))) {
                staticTyped = true;
                tail = tail.substring(after).stripLeading();
            }
        }
        if (hasDot) {
            return new JavaImportContext(staticTyped ? Kind.QUALIFIED_STATIC : Kind.QUALIFIED_PACKAGE);
        }
        if (!tailIsSingleIdentifier(tail)) {
            return NONE;
        }
        return new JavaImportContext(staticTyped ? Kind.TOP_LEVEL_STATIC : Kind.TOP_LEVEL);
    }

    private static boolean tailIsSingleIdentifier(String tail) {
        for (int i = 0; i < tail.length(); i++) {
            if (!Character.isJavaIdentifierPart(tail.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}

