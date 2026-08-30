package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionContext;
import org.openide.filesystems.FileObject;

import java.util.List;
import java.util.Objects;

import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaModuleInfoCompletionQueries.MODULE_INFO_NAME;

/**
 * Text-based classification of the caret position inside a {@code module-info.java} descriptor.
 *
 * <p>Module descriptors have their own tiny grammar that regular Java completion does not model:</p>
 * <pre>
 * module com.example {
 *     requires [static] [transitive] &lt;module&gt;;
 *     exports  &lt;package&gt; [to &lt;module&gt;[, &lt;module&gt;]*];
 *     opens    &lt;package&gt; [to &lt;module&gt;[, &lt;module&gt;]*];
 *     uses     &lt;service-type&gt;;
 *     provides &lt;service-type&gt; with &lt;impl-type&gt;[, &lt;impl-type&gt;]*;
 * }
 * </pre>
 *
 * <p>{@link #classify(CompletionContext)} inspects only the document text (no AST) and reports which
 * directive slot the caret sits in, so the completion provider can offer the right catalog:
 * module names, project packages or service/implementation types.</p>
 */
public final class JavaModuleInfoContextUtils {

    /** The kind of value expected at the caret inside a module directive. */
    public enum ModuleCompletionKind {
        /** Not a completable module-directive slot (e.g. already past the directive's operands). */
        NONE,
        /** File header, before the module body: the {@code open}/{@code module} lead-in keywords. */
        HEADER,
        /** Start of a directive inside the body: the {@code requires}/{@code exports}/… keywords. */
        DIRECTIVE,
        /** After {@code requires} (and optional {@code static}/{@code transitive}): a module name. */
        REQUIRES_MODULE,
        /** After {@code to} in an {@code exports}/{@code opens} directive: a target module name. */
        TARGET_MODULE,
        /** The package operand of an {@code exports}/{@code opens} directive. */
        PACKAGE,
        /** After the package operand of {@code exports}/{@code opens}, before {@code to}: the {@code to} keyword. */
        TO_CLAUSE,
        /** After the service operand of {@code provides}, before {@code with}: the {@code with} keyword. */
        WITH_CLAUSE,
        /** A service or implementation type in {@code uses}/{@code provides ... with ...}. */
        SERVICE_TYPE
    }

    /**
     * Classification result. For {@link ModuleCompletionKind#REQUIRES_MODULE} the flags report which
     * modifier keywords are still available (not already present before the caret).
     */
    public record ModuleCompletionContext(ModuleCompletionKind kind, boolean allowStatic, boolean allowTransitive) {
        public static final ModuleCompletionContext NONE =
                new ModuleCompletionContext(ModuleCompletionKind.NONE, false, false);
    }

    private static final String REQUIRES_KEYWORD = "requires";
    private static final String EXPORTS_KEYWORD = "exports";
    private static final String OPENS_KEYWORD = "opens";
    private static final String USES_KEYWORD = "uses";
    private static final String PROVIDES_KEYWORD = "provides";
    private static final String OPEN_KEYWORD = "open";
    private static final String MODULE_KEYWORD = "module";
    static final String TO_KEYWORD = "to";
    static final String WITH_KEYWORD = "with";
    static final String STATIC_KEYWORD = "static";
    static final String TRANSITIVE_KEYWORD = "transitive";

    static final List<String> DIRECTIVE_KEYWORDS =
            List.of(REQUIRES_KEYWORD, EXPORTS_KEYWORD, OPENS_KEYWORD, USES_KEYWORD, PROVIDES_KEYWORD);
    static final List<String> HEADER_KEYWORDS = List.of(OPEN_KEYWORD, MODULE_KEYWORD);
    static final List<String> TO_KEYWORDS = List.of(TO_KEYWORD);
    static final List<String> WITH_KEYWORDS = List.of(WITH_KEYWORD);

    private JavaModuleInfoContextUtils() {
    }

    /** True when the edited file is a {@code module-info.java} descriptor. */
    public static boolean isModuleInfoFile(CompletionContext context) {
        FileObject fo = Objects.requireNonNull(context).fileObject();
        return fo != null && MODULE_INFO_NAME.equals(fo.getName());
    }

    /**
     * Classifies the caret position within a module descriptor. Returns
     * {@link ModuleCompletionContext#NONE} when the caret is not in a completable directive value slot
     * (outside the module body, on a blank statement, still typing the directive keyword, or already
     * past the directive's operands).
     */
    public static ModuleCompletionContext classify(CompletionContext context) {
        if (!isModuleInfoFile(context)) {
            return ModuleCompletionContext.NONE;
        }
        String source = context.documentText();
        int caret = context.caretOffset();
        if (!insideModuleBody(source, caret)) {
            return classifyHeader(source, caret);
        }
        int statementStart = statementStart(source, caret);
        String statement = source.substring(statementStart, caret).stripLeading();
        // Tokens with -1 limit keep a trailing "" element when the statement ends with whitespace,
        // which signals that the caret sits at the start of a fresh (empty) operand.
        String[] tokens = statement.isEmpty() ? new String[0] : statement.split("\\s+", -1);
        if (tokens.length < 2) {
            // Blank statement or still typing the leading word: offer the directive keywords.
            return kind(ModuleCompletionKind.DIRECTIVE);
        }
        return switch (tokens[0]) {
            case REQUIRES_KEYWORD -> classifyRequires(tokens);
            case EXPORTS_KEYWORD, OPENS_KEYWORD -> classifyExportsOpens(tokens);
            case USES_KEYWORD -> classifySingleType(tokens);
            case PROVIDES_KEYWORD -> classifyProvides(tokens);
            default -> ModuleCompletionContext.NONE;
        };
    }

    /**
     * Classifies the caret on the current header line (before the module body): the {@code open}/
     * {@code module} lead-in. Returns {@link ModuleCompletionKind#NONE} once {@code module} is present
     * (the caret is then in the module name, which has no completion).
     */
    private static ModuleCompletionContext classifyHeader(String source, int caret) {
        int lineStart = source.lastIndexOf('\n', caret - 1) + 1;
        String line = source.substring(lineStart, caret).stripLeading();
        String[] tokens = line.isEmpty() ? new String[0] : line.split("\\s+", -1);
        for (int i = 0; i < tokens.length - 1; i++) {
            if (MODULE_KEYWORD.equals(tokens[i])) {
                return ModuleCompletionContext.NONE;
            }
        }
        return kind(ModuleCompletionKind.HEADER);
    }

    private static ModuleCompletionContext classifyRequires(String[] tokens) {
        boolean hasStatic = false;
        boolean hasTransitive = false;
        // Every token between the directive and the partial being typed must be a modifier;
        // a non-modifier token means the single module name is already given -> nothing to complete.
        for (int i = 1; i < tokens.length - 1; i++) {
            switch (tokens[i]) {
                case STATIC_KEYWORD -> hasStatic = true;
                case TRANSITIVE_KEYWORD -> hasTransitive = true;
                default -> {
                    return ModuleCompletionContext.NONE;
                }
            }
        }
        return new ModuleCompletionContext(ModuleCompletionKind.REQUIRES_MODULE, !hasStatic, !hasTransitive);
    }

    private static ModuleCompletionContext classifyExportsOpens(String[] tokens) {
        if (containsToken(tokens, TO_KEYWORD)) {
            return kind(ModuleCompletionKind.TARGET_MODULE);
        }
        if (tokens.length == 2) {
            // Still on the first operand: the package being exported/opened.
            return kind(ModuleCompletionKind.PACKAGE);
        }
        // Package operand given, `to` not yet present: offer the `to` keyword.
        return tokens.length == 3 ? kind(ModuleCompletionKind.TO_CLAUSE) : ModuleCompletionContext.NONE;
    }

    private static ModuleCompletionContext classifyProvides(String[] tokens) {
        if (containsToken(tokens, WITH_KEYWORD)) {
            return kind(ModuleCompletionKind.SERVICE_TYPE);
        }
        if (tokens.length == 2) {
            // Still on the first operand: the service type being provided.
            return kind(ModuleCompletionKind.SERVICE_TYPE);
        }
        // Service operand given, `with` not yet present: offer the `with` keyword.
        return tokens.length == 3 ? kind(ModuleCompletionKind.WITH_CLAUSE) : ModuleCompletionContext.NONE;
    }

    private static ModuleCompletionContext classifySingleType(String[] tokens) {
        return tokens.length == 2 ? kind(ModuleCompletionKind.SERVICE_TYPE) : ModuleCompletionContext.NONE;
    }

    /** True when a structural keyword ({@code to}/{@code with}) appears before the partial being typed. */
    private static boolean containsToken(String[] tokens, String keyword) {
        for (int i = 1; i < tokens.length - 1; i++) {
            if (keyword.equals(tokens[i])) {
                return true;
            }
        }
        return false;
    }

    private static ModuleCompletionContext kind(ModuleCompletionKind kind) {
        return new ModuleCompletionContext(kind, false, false);
    }

    /** Start of the current directive: just after the last {@code '{'}, {@code ';'} or {@code '}'}. */
    private static int statementStart(String source, int caret) {
        int limit = caret - 1;
        int start = Math.max(source.lastIndexOf('{', limit), source.lastIndexOf(';', limit));
        start = Math.max(start, source.lastIndexOf('}', limit));
        return start + 1;
    }

    /** True when the caret sits inside an open brace block (the module body). */
    private static boolean insideModuleBody(String source, int caret) {
        int depth = 0;
        for (int i = 0; i < caret && i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return depth > 0;
    }
}
