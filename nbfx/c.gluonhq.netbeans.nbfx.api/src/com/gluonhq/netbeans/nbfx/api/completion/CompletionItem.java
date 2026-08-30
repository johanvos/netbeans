package com.gluonhq.netbeans.nbfx.api.completion;

/**
 * Describes one completion entry. Implementations should define the content for such entry
 * in a way that allows the renderer to display it in a completion list and
 * insert the appropriate text when committed.
 */
public interface CompletionItem {

    /**
     * Primary proposal label shown to users.
     *
     * @return item label
     */
    String label();

    /**
     * Text inserted into the document when this proposal is committed.
     *
     * @return insertion text
     */
    String insertText();

    /**
     * Secondary sort key applied after {@link #sortPriority()}.
     *
     * @return sort text, defaults to {@link #label()}
     */
    default String sortText() {
        return label();
    }

    /**
     * Primary sort priority value, where lower values rank first.
     *
     * @return sort priority
     */
    default int sortPriority() {
        return 0;
    }

    /**
     * Left-side text rendered in completion rows.
     *
     * @return left column text, defaults to {@link #label()}
     */
    default String leftText() {
        return label();
    }

    /**
     * Right-side text rendered in completion rows.
     *
     * @return right column text, defaults to empty
     */
    default String rightText() {
        return "";
    }

    /**
     * General semantic category used for icon/styling.
     *
     * @return item kind, defaults to {@link CompletionItemKind#OTHER}
     */
    default CompletionItemKind kind() {
        return CompletionItemKind.OTHER;
    }

    /**
     * Detailed type hint when {@link #kind()} is {@link CompletionItemKind#TYPE}.
     *
     * @return type kind, defaults to {@link CompletionTypeKind#OTHER}
     */
    default CompletionTypeKind typeKind() {
        return CompletionTypeKind.OTHER;
    }

    /**
     * Modifier flags, compatible with {@link java.lang.reflect.Modifier}.
     *
     * @return modifier bit mask
     */
    default int modifiers() {
        return 0;
    }

    /**
     * Rendering hint for emphasizing members.
     *
     * @return {@code true} to render emphasized text
     */
    default boolean emphasized() {
        return false;
    }

    /**
     * Whether this completion item represents a deprecated member.
     *
     * @return {@code true} when this item should be rendered as deprecated
     */
    default boolean deprecated() {
        return false;
    }

    /**
     * Fully-qualified name of the type this item inserts, or empty if nothing to import.
     *
     * @return fully-qualified type name, defaults to empty
     */
    default String qualifiedName() {
        return "";
    }

    /**
     * Minimal visual row length for width calculations.
     *
     * @return left/right text length
     */
    default double length() {
        return leftText().length() + rightText().length();
    }
}

