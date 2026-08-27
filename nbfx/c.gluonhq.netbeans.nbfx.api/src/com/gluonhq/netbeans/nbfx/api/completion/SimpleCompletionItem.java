package com.gluonhq.netbeans.nbfx.api.completion;

import java.util.Objects;

/**
 * Lightweight immutable {@link CompletionItem} implementation.
 */
public record SimpleCompletionItem(String label, String insertText, String sortText,
                                   int sortPriority,
                                   String leftText, String rightText,
                                   CompletionItemKind kind, CompletionTypeKind typeKind,
                                   int modifiers,
                                   boolean emphasized, boolean deprecated,
                                   String qualifiedName) implements CompletionItem {

    public SimpleCompletionItem {
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(insertText, "insertText must not be null");
        sortText = sortText == null ? label : sortText;
        leftText = leftText == null ? label : leftText;
        rightText = rightText == null ? "" : rightText;
        kind = kind == null ? CompletionItemKind.OTHER : kind;
        typeKind = typeKind == null ? CompletionTypeKind.OTHER : typeKind;
        qualifiedName = qualifiedName == null ? "" : qualifiedName;
    }

    /**
     * Convenience constructor for items that carry no importable {@linkplain #qualifiedName()
     * fully-qualified name}.
     */
    public SimpleCompletionItem(String label, String insertText, String sortText,
                                int sortPriority,
                                String leftText, String rightText,
                                CompletionItemKind kind, CompletionTypeKind typeKind,
                                int modifiers,
                                boolean emphasized, boolean deprecated) {
        this(label, insertText, sortText, sortPriority, leftText, rightText,
                kind, typeKind, modifiers, emphasized, deprecated, "");
    }

    /**
     * @return weighted left/right text length estimate, based on different font sizes
     */
    @Override
    public double length() {
        return (13d * leftText().length() + 11d * rightText().length()) / (13d * 11d);
    }
}

