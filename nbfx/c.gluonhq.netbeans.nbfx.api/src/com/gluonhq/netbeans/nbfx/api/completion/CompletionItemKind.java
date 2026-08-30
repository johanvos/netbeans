package com.gluonhq.netbeans.nbfx.api.completion;

/**
 * Primary category of a completion item.
 *
 * <p>Renderers use this kind to choose icons and row treatment.
 */
public enum CompletionItemKind {
    /** Callable member, including ordinary methods and constructors. */
    METHOD,
    /** Field-like member (field or enum constant). */
    FIELD,
    /** Type proposal such as class, interface, enum, or record. Its subcategories are defined in {@link CompletionTypeKind} */
    TYPE,
    /** Java package segment proposal. */
    PACKAGE,
    /** Java module name proposal (module-descriptor {@code requires}/{@code to} operands). */
    MODULE,
    /** Java keyword or literal keyword proposal. */
    KEYWORD,
    /** Local variable/parameter-like symbol proposal. */
    VARIABLE,
    /** Item with no specific semantic category. */
    OTHER,

    /** Visual separator item between completion groups. Not an actual Java semantic type */
    SEPARATOR
}

