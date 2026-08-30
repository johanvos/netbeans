package com.gluonhq.netbeans.nbfx.editor.completion;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionCancellation;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionContext;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItemKind;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionProvider;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionTypeKind;
import com.gluonhq.netbeans.nbfx.api.completion.SimpleCompletionItem;
import com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItems;
import com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionTypeUtils;
import com.gluonhq.netbeans.nbfx.editor.completion.support.JavaImportContext;
import com.gluonhq.netbeans.nbfx.editor.completion.support.JavaModuleInfoContextUtils;
import com.gluonhq.netbeans.nbfx.editor.processor.semantics.SourceContext;
import org.netbeans.api.java.lexer.JavaTokenId;
import org.netbeans.api.lexer.TokenHierarchy;
import org.netbeans.api.lexer.TokenSequence;
import org.openide.util.lookup.ServiceProvider;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionContextUtils.isNewKeywordContext;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionContextUtils.isSemanticOnlyContext;

/**
 * Provides fast, purely lexical completion for {@code .java} code. It scans the
 * document with the NetBeans Java lexer and proposes:
 *
 * <ol>
 *     <li>Source identifiers and top-level type names, marked as {@code source},</li>
 *     <li>non-wildcard imports, marked as {@code import},</li>
 *     <li>Java literals ({@code true}/{@code false}/{@code null}), marked as {@code literal},</li>
 *     <li>and the full Java keyword set, marked as {@code keyword},</li>
 * </ol>
 *
 * without ever resolving symbols or invoking {@code javac}.
 *
 * <p>It is registered as a {@link CompletionProvider} via {@link ServiceProvider} and runs
 * in parallel with the semantic provider; results are merged by the controller.
 * Faster than semantics, it is the first to deliver suggestions, especially on incomplete or broken sources
 * where the semantic provider might come empty. When semantic results become available, these take precedence
 * over lexical ones.</p>
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>Classify the caret with {@link JavaImportContext} — on {@code import} lines only
 *       the {@code static} keyword is offered (qualified/static imports are deferred to
 *       the semantic provider).</li>
 *   <li>Tokenize the whole document once and collect: declared top-level type names,
 *       explicit imports, every {@code IDENTIFIER} token (as a source candidate), and the
 *       tokens at/around the caret to detect blocked contexts.</li>
 *   <li>Merge candidates with {@link #preferItem} (type beats variable on label clash),
 *       append keywords + literals via {@link #keywordItems}, and filter by the active
 *       prefix.</li>
 * </ol>
 *
 * <h2>Blocked contexts</h2>
 * Lexical suggestions are suppressed when the caret sits after {@code .} or {@code ::}
 * (member access, for the semantic provider) or inside a comment / string / char literal.
 *
 * <h2>Examples</h2>
 * <ul>
 *   <li>{@code Scene sce|} → source identifier proposal {@code scene}</li>
 *   <li>{@code import javafx.scene.Scene; ... Sc|} → imported type proposal {@code Scene}</li>
 *   <li>{@code scene = nu|} → literal proposal {@code null}</li>
 *   <li>{@code if (scene != null) ret|} → keyword proposal {@code return}</li>
 * </ul>
 */
@ServiceProvider(service = CompletionProvider.class)
public final class JavaLexicalCompletionProvider implements CompletionProvider {

    private static final ResourceBundle BUNDLE =
            ResourceBundle.getBundle("com.gluonhq.netbeans.nbfx.editor.completion.CompletionUI");

    // Keyword set from the Java lexer, filtered by primary category and sorted alphabetically.
    // Includes the Java contextual keywords (sealed/non-sealed/permits/record/yield).
    private static final List<String> KEYWORDS = Stream.concat(
                    Arrays.stream(JavaTokenId.values())
                            .filter(id -> {
                                String category = id.primaryCategory();
                                return "keyword".equals(category) || "keyword-directive".equals(category);
                            })
                            .map(JavaTokenId::fixedText)
                            .filter(Objects::nonNull),
                    Stream.of("sealed", "non-sealed", "permits", "record", "yield"))
            .distinct()
            .sorted()
            .toList();

    private static final List<String> LITERALS = List.of("true", "false", "null");

    // Lower numeric value = higher rank in the popup. Sources before the caret are the most
    // likely targets, types from imports follow, and literals/keywords have the least rank.
    private static final int PRIORITY_SOURCE_BEFORE_CARET = 10;
    private static final int PRIORITY_SOURCE_AFTER_CARET = 20;
    private static final int PRIORITY_IMPORT = 40;
    private static final int PRIORITY_LITERAL = 90;
    private static final int PRIORITY_KEYWORD = 100;

    /**
     * Lexical completion is only meaningful for Java source — module descriptors and
     * other file types are skipped.
     */
    @Override
    public boolean supports(CompletionContext context) {
        if (JavaModuleInfoContextUtils.isModuleInfoFile(context)) {
            // Module descriptors are handled exclusively by the semantic module-info provider
            return false;
        }
        String ext = context.fileObject().getExt();
        return "java".equalsIgnoreCase(ext);
    }

    /**
     * Builds the lexical proposal list for {@code context}. The method always returns a
     * completed future — work is synchronous and quick (one lexer pass + two regex scans).
     *
     * <p>Steps:</p>
     * <ol>
     *   <li>Bail on cancellation and on {@code import} lines.</li>
     *   <li>Tokenize once: collect declared types, imports, every identifier, and the
     *       tokens at/before the caret used by {@link #isBlockedContext}.</li>
     *   <li>Reject blocked contexts (member access, comments, strings, char literals).</li>
     *   <li>Append keywords/literals and drop the exact-prefix self-match.</li>
     * </ol>
     */
    @Override
    public CompletableFuture<List<CompletionItem>> query(CompletionContext context,
                                                         CompletionCancellation cancellation) {
        if (cancellation.isCancelled() || isNewKeywordContext(context.documentText(), context.caretOffset())) {
            return CompletableFuture.completedFuture(List.of());
        }

        JavaImportContext importContext = JavaImportContext.from(context);
        if (importContext.isImport()) {
            // `static` is the only legal lexical proposal on an import line, and only after `import`.
            // Once a `.` has been typed or `static` is already present, defer to the semantic provider.
            if (importContext.isQualified() || importContext.isStatic()) {
                return CompletableFuture.completedFuture(List.of());
            }
            return CompletableFuture.completedFuture(importStaticKeywordOnly(context.prefix()));
        }

        if (isSemanticOnlyContext(context)) {
            return CompletableFuture.completedFuture(List.of());
        }

        String source = context.documentText();
        if (source.isBlank()) {
            // No code yet: just return the default list of keywords + literals.
            return CompletableFuture.completedFuture(keywordItems(new LinkedHashMap<>(), context.prefix()));
        }

        TokenHierarchy<?> hierarchy = TokenHierarchy.create(source, false, JavaTokenId.language(),
                null, SourceContext.createLexerAttributes(context.fileObject()));
        TokenSequence<JavaTokenId> sequence = hierarchy.tokenSequence(JavaTokenId.language());
        if (sequence == null) {
            // No sequences: just return the default list of keywords + literals.
            return CompletableFuture.completedFuture(keywordItems(new LinkedHashMap<>(), context.prefix()));
        }

        String prefix = context.prefix();
        // Probe one char left of the caret: that token reveals what the user is inside
        // (e.g. a string literal), independent of the identifier currently being typed.
        int probeOffset = context.caretOffset() == 0 ? -1 : context.caretOffset() - 1;
        JavaTokenId tokenAtProbe = null;
        JavaTokenId tokenBeforeAnchor = null;

        // Map of imports: simple name - fqcn
        Map<String, String> explicitImports = JavaCompletionTypeUtils.parseExplicitImports(source);
        // Map of declared types: name - kind
        Map<String, CompletionTypeKind> declaredTypes = JavaCompletionTypeUtils.parseDeclaredTypes(source);

        // Tracks whether we're currently inside an `import ...;` or `package ...;` statement
        boolean inImport = false;
        boolean inPackage = false;
        String importedSimpleName = null;

        Map<String, CompletionItem> results = new LinkedHashMap<>();

        sequence.moveStart();
        while (sequence.moveNext()) {
            if (cancellation.isCancelled()) {
                return CompletableFuture.completedFuture(List.of());
            }

            JavaTokenId id = sequence.token().id();
            String text = sequence.token().text().toString();
            int start = sequence.offset();
            int end = start + sequence.token().length();

            if (probeOffset >= start && probeOffset < end) {
                tokenAtProbe = id;
            }
            // Last non-whitespace token strictly before the anchor — used to spot `.` / `::`
            // member-access positions that should defer to the semantic provider.
            if (end <= context.anchorOffset() && id != JavaTokenId.WHITESPACE) {
                tokenBeforeAnchor = id;
            }

            if (id == JavaTokenId.IMPORT) {
                inImport = true;
                importedSimpleName = null;
                continue;
            }
            if (id == JavaTokenId.PACKAGE) {
                inPackage = true;
                continue;
            }
            if (id == JavaTokenId.SEMICOLON) {
                if (inImport && importedSimpleName != null) {
                    // End of an `import a.b.C;` line: promote the last identifier (`C`) into a type proposal.
                    CompletionTypeKind typeKind = resolveImportedTypeKind(importedSimpleName, explicitImports);
                    createItem(results, importedSimpleName,
                            PRIORITY_IMPORT + prefixPenalty(importedSimpleName, prefix),
                            BUNDLE.getString("completion.popup.lexer.import"),
                            CompletionItemKind.TYPE, typeKind);
                }
                // clear the import/package state for the next token
                inImport = false;
                inPackage = false;
                importedSimpleName = null;
                continue;
            }
            if (id == JavaTokenId.STAR && inImport) {
                // Wildcard import: nothing to collect as a simple name.
                importedSimpleName = null;
                continue;
            }
            if (id != JavaTokenId.IDENTIFIER) {
                continue;
            }

            if (inImport) {
                // Keep overwriting: the last identifier before the `;` is the simple name.
                importedSimpleName = text;
                continue;
            }
            if (inPackage) {
                continue;
            }

            // Identifiers appearing before the caret tend to be in scope, so they
            // are ranked above forward references that appear later in the file.
            int basePriority = start < context.caretOffset() ? PRIORITY_SOURCE_BEFORE_CARET : PRIORITY_SOURCE_AFTER_CARET;

            CompletionTypeKind declaredTypeKind = declaredTypes.getOrDefault(text, CompletionTypeKind.OTHER);
            CompletionItemKind itemKind = declaredTypeKind == CompletionTypeKind.OTHER ? CompletionItemKind.VARIABLE
                    : CompletionItemKind.TYPE;

            // finally, create source identifier item for this token
            createItem(results, text,
                    basePriority + prefixPenalty(text, prefix),
                    BUNDLE.getString("completion.popup.lexer.source"),
                    itemKind, declaredTypeKind);
        }

        if (isBlockedContext(tokenAtProbe, tokenBeforeAnchor)) {
            // no results in blocked context
            return CompletableFuture.completedFuture(List.of());
        }

        // Remove the proposal that exactly matches what the user already typed, and return final results.
        List<CompletionItem> items = keywordItems(results, prefix).stream()
                .filter(item -> prefix.isBlank() || !item.label().equals(prefix))
                .toList();

        return CompletableFuture.completedFuture(items);
    }

    // private helpers

    /**
     * Returns just the {@code static} keyword (filtered by {@code prefix}) — the single
     * legal lexical proposal on an {@code import |} line.
     */
    private static List<CompletionItem> importStaticKeywordOnly(String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        if (!prefix.isBlank() && !"static".startsWith(lowerPrefix)) {
            return List.of();
        }
        Map<String, CompletionItem> result = new LinkedHashMap<>();
        createItem(result, "static",
                PRIORITY_KEYWORD + prefixPenalty("static", prefix),
                BUNDLE.getString("completion.popup.lexer.keyword"),
                CompletionItemKind.KEYWORD, CompletionTypeKind.OTHER);
        return List.copyOf(result.values());
    }

    /**
     * Augments {@code results} with literal and keyword candidates, then returns the merged
     * list filtered by case-insensitive {@code prefix} match. Existing entries with a better
     * (lower) priority are preserved by {@link #preferItem}.
     */
    private static List<CompletionItem> keywordItems(Map<String, CompletionItem> results, String prefix) {
        for (String literal : LITERALS) {
            createItem(results, literal,
                    PRIORITY_LITERAL + prefixPenalty(literal, prefix),
                    BUNDLE.getString("completion.popup.lexer.literal"),
                    CompletionItemKind.KEYWORD, CompletionTypeKind.OTHER);
        }
        for (String keyword : KEYWORDS) {
            createItem(results, keyword,
                    PRIORITY_KEYWORD + prefixPenalty(keyword, prefix),
                    BUNDLE.getString("completion.popup.lexer.keyword"),
                    CompletionItemKind.KEYWORD, CompletionTypeKind.OTHER);
        }
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        return results.values().stream()
                .filter(item -> prefix.isBlank() || item.label().toLowerCase(Locale.ROOT).startsWith(lowerPrefix))
                .toList();
    }

    /**
     * Inserts a {@link SimpleCompletionItem} for {@code label} into {@code candidates},
     * rejecting empty labels or those that cannot start a Java identifier. Collisions on
     * the same label are resolved by {@link #preferItem}.
     */
    private static void createItem(Map<String, CompletionItem> candidates, String label,
                                   int priority, String detail,
                                   CompletionItemKind kind, CompletionTypeKind typeKind) {
        if (label == null || label.isBlank() || !Character.isJavaIdentifierStart(label.charAt(0))) {
            return;
        }
        CompletionItem item = JavaCompletionItems.lexical(label, priority, detail, kind, typeKind);
        candidates.merge(label, item, JavaLexicalCompletionProvider::preferItem);
    }

    /**
     * When there are two items with the same label, type items have precedence over plain
     * variables, otherwise the lower-priority item wins.
     */
    private static CompletionItem preferItem(CompletionItem existing, CompletionItem incoming) {
        if (existing.kind() != incoming.kind()) {
            if (incoming.kind() == CompletionItemKind.TYPE && existing.kind() == CompletionItemKind.VARIABLE) {
                return incoming;
            }
            if (existing.kind() == CompletionItemKind.TYPE && incoming.kind() == CompletionItemKind.VARIABLE) {
                return existing;
            }
        }
        return incoming.sortPriority() < existing.sortPriority() ? incoming : existing;
    }

    /**
     * Best-effort classification of an imported simple name. Tries to load the FQCN via
     * {@code Class.forName} — works for JDK and JavaFX types reachable on the classpath,
     * falls back to {@link CompletionTypeKind#OTHER} for project classes not yet compiled.
     */
    private static CompletionTypeKind resolveImportedTypeKind(String simpleName, Map<String, String> imports) {
        String fqcn = imports.get(simpleName);
        if (fqcn == null) {
            return CompletionTypeKind.OTHER;
        }
        Class<?> clazz = JavaCompletionTypeUtils.tryLoad(fqcn);
        if (clazz == null) {
            return CompletionTypeKind.OTHER;
        }
        return CompletionTypeKind.from(clazz);
    }

    /** If {@code candidate} starts with the user's prefix, reduce priority, so it stays near the top. */
    private static int prefixPenalty(String candidate, String prefix) {
        if (prefix.isBlank()) {
            return 0;
        }
        return candidate.startsWith(prefix) ? 0 : 1;
    }

    /**
     * Returns {@code true} when the caret sits in a position that has no lexical proposals:
     * after {@code .} / {@code ::} (member access, semantic), or inside a comment / string / char literal.
     */
    private static boolean isBlockedContext(JavaTokenId tokenAtProbe, JavaTokenId tokenBeforeAnchor) {
        return tokenBeforeAnchor == JavaTokenId.DOT || tokenBeforeAnchor == JavaTokenId.COLONCOLON
                || tokenAtProbe == JavaTokenId.LINE_COMMENT || tokenAtProbe == JavaTokenId.BLOCK_COMMENT
                || tokenAtProbe == JavaTokenId.JAVADOC_COMMENT || tokenAtProbe == JavaTokenId.STRING_LITERAL
                || tokenAtProbe == JavaTokenId.MULTILINE_STRING_LITERAL || tokenAtProbe == JavaTokenId.CHAR_LITERAL;
    }

}
