package com.gluonhq.netbeans.nbfx.editor.decoration;

import java.util.HashMap;
import java.util.Map;

/**
 * Token categories recognized by the parsers, each mapped to a
 * style that defines how matching tokens are styled.
 */
public enum TokenCategory {

    DEFAULT("default", null),
    KEYWORD("keyword", "-fx-fill: -code-keyword-color;"),
    KEYWORD_DIRECTIVE("keyword-directive", "-fx-fill: -code-keyword-directive-color;"),
    STRING("string", "-fx-fill: -code-string-color;"),
    COMMENT("comment", "-fx-fill: -code-comment-color;"),
    IDENTIFIER("identifier", null),

    FIELD("field", "-fx-fill: -code-field-color;"),
    METHOD("method", "-fx-fill: -code-method-color; -fx-font-weight: bold;"),
    CLASS("class", "-fx-fill: -code-class-color; -fx-font-weight: bold;"),

    WARNING("warning", "squiggly-warning"),
    ERROR("error", "squiggly-error"),

    BRACE_MATCH("brace-match", "brace"),
    BRACE_MISMATCH("brace-mismatch", "brace-error"),

    OCCURRENCE("occurrence", "occurrence");

    /** Prefix that marks a style as a squiggly underline rather than an inline text style. */
    public static final String SQUIGGLY_PREFIX = "squiggly";

    /** Prefix that marks a style as a background highlight (used for brace matching). */
    public static final String BRACE_PREFIX = "brace";

    /** Prefix that marks a style as a background highlight for mark-occurrences. */
    public static final String OCCURRENCE_PREFIX = "occurrence";

    /** @return {@code true} if the style is an overlay (squiggly, brace or occurrence) rather than a text style. */
    public static boolean isOverlayStyle(String style) {
        return style != null && (style.startsWith(SQUIGGLY_PREFIX) || style.startsWith(BRACE_PREFIX) ||
                    style.startsWith(OCCURRENCE_PREFIX));
    }

    private final String category;
    private final String style;

    private static final Map<String, TokenCategory> BY_CATEGORY;

    static {
        Map<String, TokenCategory> map = new HashMap<>();
        for (TokenCategory tc : values()) {
            map.put(tc.category, tc);
        }
        BY_CATEGORY = Map.copyOf(map);
    }

    TokenCategory(String category, String style) {
        this.category = category;
        this.style = style;
    }

    public String category() {
        return category;
    }

    public String style() {
        return style;
    }

    public static TokenCategory fromCategory(String category) {
        return category == null ? DEFAULT : BY_CATEGORY.get(category);
    }
}
