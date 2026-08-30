package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionCancellation;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionContext;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import org.netbeans.api.java.source.CompilationController;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaAnnotationCompletionUtils.isAnnotationArgumentContext;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaAnnotationCompletionUtils.isAnnotationTypeContext;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaAnnotationCompletionUtils.isAnnotationValueContext;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionAssignableSubtypeUtils.collectAssignableTypesFromClasspath;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionInvocationUtils.resolveParameterTypeForIndex;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.lowerPrefix;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.prefixMismatch;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.putItem;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.simpleName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItems.expectedType;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionNewContextUtils.collectPackageItems;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionNewContextUtils.collectTypeItems;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionTypeUtils.extractQualifier;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaMemberQualifierResolver.resolveMemberQualifierFallback;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaSourceTextScanner.findMatchingCloseParen;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaSourceTextScanner.skipWhitespaceBackward;

/**
 * Provides completion-context detection and expected-type helpers for semantic Java completion.
 * Examples: {@code scene.setR|}, {@code new Scen|}, and {@code scene.setRoot(new |)} where
 * popup candidates follow the detected context.
 */
public final class JavaCompletionContextUtils {

    // Java keywords that may legally lead a declaration or statement. Restricted to
    // the lowercase reserved words; contextual keywords (e.g. `record`, `sealed`) are also included.
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "exports", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "module", "native", "new",
            "open", "opens", "package", "permits", "private", "protected", "provides", "public", "record",
            "return", "sealed", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "to", "transient", "transitive", "try", "uses", "var", "void", "volatile",
            "while", "with", "yield");

    private static final Set<String> CONTROL_FLOW_KEYWORDS = Set.of(
            "if", "while", "for", "switch", "catch", "synchronized",
            "return", "do", "try", "new", "assert", "throw", "yield", "case", "instanceof");

    private JavaCompletionContextUtils() {
    }

    // Completion Context Detection

    /**
     * Detects member-access completion: the caret sits after a {@code .} that follows an
     * identifier (or directly after the dot with no prefix yet). Walks back from the
     * anchor over identifier characters, then checks for a preceding {@code .}.
     *
     * <p>Examples — returns {@code true} for:</p>
     * <ul>
     *   <li>{@code scene.|}      — blank-prefix member completion</li>
     *   <li>{@code scene.setR|}  — prefix-filtered member completion</li>
     *   <li>{@code a.b.c.|}      — chained member access</li>
     *   <li>{@code Math.|}       — static member access (qualifier is a type)</li>
     * </ul>
     *
     * <p>Returns {@code false} for plain identifiers ({@code Sce|}, {@code val|}) and
     * for the start of the buffer.</p>
     */
    public static boolean isMemberCompletionContext(CompletionContext context) {
        if (!Objects.requireNonNull(context).hasTextBeforeAnchor()) {
            return false;
        }
        String source = context.documentText();
        int anchorOffset = context.anchorOffset();

        int i = anchorOffset - 1;
        while (i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) {
            i--;
        }
        return i >= 0 && source.charAt(i) == '.';
    }

    /**
     * Detects a qualified-package completion after the {@code new} keyword, e.g.
     * {@code new javafx.scene.|}; routes to {@code queryPackageWithJavaSource}
     * so the user can drill into sub-packages of the qualifier.
     */
    public static boolean isNewPackageCompletionContext(CompletionContext context) {
        if (!Objects.requireNonNull(context).hasCharBeforeAnchor('.')) {
            return false;
        }

        String source = context.documentText();
        int anchorOffset = context.anchorOffset();

        String qualifier = extractQualifier(source, anchorOffset - 1);
        if (qualifier == null || qualifier.isBlank()) {
            return false;
        }

        int i = skipWhitespaceBackward(source, anchorOffset - 2);
        while (i >= 0) {
            char ch = source.charAt(i);
            if (Character.isJavaIdentifierPart(ch) || ch == '.') {
                i--;
            } else {
                break;
            }
        }
        return isNew(source, i);
    }

    /**
     * Returns {@code true} when at least one of {@code overloads} exposes a
     * declared (reference) parameter type at {@code argIndex} — i.e. a slot where
     * "expected type" completion can propose a meaningful candidate. Used by the
     * dispatcher to decide whether to skip the textual constructor fallback.
     */
    static boolean hasResolvableExpectedType(List<ExecutableElement> overloads, int argIndex) {
        for (ExecutableElement overload : overloads) {
            TypeMirror parameterType = resolveParameterTypeForIndex(overload, argIndex);
            TypeMirror candidateType = resolveNewInstantiationType(parameterType);
            if (candidateType != null && asTypeElement(candidateType) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detects a type-name completion right after the {@code new} keyword, e.g.
     * {@code new Sc|} or {@code box.getChildren().add(new |)}.
     */
    public static boolean isNewTypeCompletionContext(CompletionContext context) {
        return isNewKeywordContext(Objects.requireNonNull(context).documentText(), context.anchorOffset(), false);
    }

    /**
     * Returns {@code true} when the token immediately before {@code offset}
     * (whitespace skipped) is the {@code new} keyword. Used by the lexical
     * provider to defer to the semantic provider on {@code new |} positions.
     */
    public static boolean isNewKeywordContext(String source, int offset) {
        return isNewKeywordContext(source, offset, true);
    }

    /**
     * Returns {@code true} when the caret sits inside the identifier of a typed
     * {@code new TypeName(…)} expression — e.g. {@code new VBo|x(}. Used to fall back
     * to the generic type catalog instead of trying to resolve an invocation that is
     * still being typed.
     */
    static boolean isInsideNewTypeIdentifierWithOpenParen(CompletionContext context) {
        String source = context.documentText();
        int anchor = context.anchorOffset();
        int caret = context.caretOffset();
        if (source == null || source.isBlank() || anchor < 0 || anchor >= source.length() || caret < anchor) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(source.charAt(anchor))) {
            return false;
        }

        int identifierEnd = scanIdentifierEnd(source, anchor);
        if (identifierEnd >= source.length() || source.charAt(identifierEnd) != '(') {
            return false;
        }

        return caret <= identifierEnd;
    }

    /**
     * Walks every overload of an invocation slot, resolves its substituted parameter type
     * at {@code argIndex}, and emits the matching expected-type rows (the expected type
     * itself plus assignable subtypes / wrapper conventions). Used by
     * {@link JavaSemanticCompletionQueries#queryNewWithJavaSource} when the caret sits
     * after {@code new} inside an invocation argument.
     */
    static List<CompletionItem> collectExpectedNewArgumentTypes(CompilationController controller,
                                                                List<ExecutableElement> overloads,
                                                                DeclaredType receiverType,
                                                                int argIndex, String prefix,
                                                                CompletionCancellation cancellation) {
        Map<String, CompletionItem> result = new LinkedHashMap<>();
        for (ExecutableElement overload : overloads) {
            if (cancellation.isCancelled()) {
                return List.of();
            }
            // Apply type-argument substitution so generic methods like
            // `ObservableList<Node>.add(E)` yield `Node` (the substituted parameter type)
            // instead of the type variable `E`.
            TypeMirror parameterType = JavaInvocationArgumentUtils.resolveSubstitutedParameterType(
                    controller.getTypes(), receiverType, overload, argIndex);
            TypeElement expectedType = asTypeElement(resolveNewInstantiationType(parameterType));
            if (expectedType != null) {
                collectItemsForExpectedType(controller, expectedType, prefix, false, cancellation, result);
            }
        }
        return List.copyOf(result.values());
    }

    /**
     * Generic-catalog tail used by {@code new} completions when no expected type is
     * known: appends every visible classpath type matching {@code prefix} followed by
     * every matching package.
     */
    static void addGeneralNewContextItems(List<CompletionItem> items,
                                          CompilationController controller,
                                          String prefix,
                                          boolean showAllItems,
                                          String sourceText,
                                          CompletionCancellation cancellation) {
        items.addAll(collectTypeItems(controller, prefix, showAllItems, false, sourceText, cancellation));
        items.addAll(collectPackageItems(controller, prefix, showAllItems, cancellation));
    }

    /**
     * Walks up from {@code newPath} to find a context that constrains the expected type of a
     * standalone {@code new} expression:
     * <ul>
     *   <li>{@code Scene scene = new |}              — {@link VariableTree} initializer</li>
     *   <li>{@code scene = new |}                    — {@link AssignmentTree} RHS</li>
     *   <li>{@code scene += new |} (and similar)     — {@link CompoundAssignmentTree} RHS</li>
     *   <li>{@code return new |;}                    — {@link ReturnTree} inside a {@link MethodTree}/{@link LambdaExpressionTree}</li>
     * </ul>
     * Returns {@code null} when no such constraining context is found.
     */
    static TypeMirror resolveStandaloneNewExpectedType(TreePath newPath, CompilationController controller) {
        if (newPath == null) {
            return null;
        }
        for (TreePath current = newPath; current != null; current = current.getParentPath()) {
            Tree leaf = current.getLeaf();
            if (leaf instanceof VariableTree variable) {
                Tree typeTree = variable.getType();
                return typeTree == null ? null
                        : controller.getTrees().getTypeMirror(new TreePath(current, typeTree));
            }
            if (leaf instanceof AssignmentTree assignment) {
                return controller.getTrees().getTypeMirror(new TreePath(current, assignment.getVariable()));
            }
            if (leaf instanceof CompoundAssignmentTree assignment) {
                return controller.getTrees().getTypeMirror(new TreePath(current, assignment.getVariable()));
            }
            if (leaf instanceof ReturnTree) {
                return resolveEnclosingReturnType(current, controller);
            }
            // MethodTree is a hard boundary: a `new` outside any of the above contexts has no expected type.
            if (leaf instanceof MethodTree || leaf instanceof LambdaExpressionTree) {
                return null;
            }
        }
        return null;
    }

    /**
     * Collects expected-type rows for a single known target type. Used when the type comes from
     * an assignment LHS, a variable initializer, or a return statement (no method overloads to scan).
     */
    static List<CompletionItem> collectExpectedTypeItems(CompilationController controller,
                                                         TypeMirror expectedTypeMirror,
                                                         String prefix,
                                                         CompletionCancellation cancellation) {
        return collectExpectedTypeItems(controller, expectedTypeMirror, prefix, false, cancellation);
    }

    /**
     * Same as {@link #collectExpectedTypeItems(CompilationController, TypeMirror, String, CompletionCancellation)}
     * but additionally lets the caller broaden the classpath subtype search past the
     * expected type's package — used by query implementations that propagate
     * {@link com.gluonhq.netbeans.nbfx.api.completion.CompletionProvider#COMPLETION_ALL_QUERY_TYPE}
     * (second Ctrl+Space) to surface assignable subtypes from unrelated packages.
     */
    static List<CompletionItem> collectExpectedTypeItems(CompilationController controller,
                                                         TypeMirror expectedTypeMirror,
                                                         String prefix,
                                                         boolean includeCrossPackageSubtypes,
                                                         CompletionCancellation cancellation) {
        TypeElement expectedType = asTypeElement(resolveNewInstantiationType(expectedTypeMirror));
        if (expectedType == null) {
            return List.of();
        }
        Map<String, CompletionItem> result = new LinkedHashMap<>();
        collectItemsForExpectedType(controller, expectedType, prefix, includeCrossPackageSubtypes, cancellation, result);
        return List.copyOf(result.values());
    }

    // Shared type helpers used by semantic query helpers

    /**
     * Narrows {@code mirror} to a {@link TypeElement} when it represents a declared
     * (class / interface / enum / record / annotation) type; returns {@code null}
     * otherwise.
     */
    static TypeElement asTypeElement(TypeMirror mirror) {
        if (mirror == null || mirror.getKind() != TypeKind.DECLARED) {
            return null;
        }
        Element element = ((DeclaredType) mirror).asElement();
        return element instanceof TypeElement typeElement ? typeElement : null;
    }

    /**
     * Walks backward from {@code from} over balanced {@code ()}/{@code []}/{@code {}}
     * (bailing on a statement-level {@code ;} or an outer {@code {}}) to find the offset
     * of the unmatched opening {@code (} that immediately encloses the caret.
     * Returns {@code -1} when no such paren exists.
     */
    public static int findEnclosingInvocationOpenParen(String source, int from) {
        int depthParen = 0, depthBracket = 0, depthBrace = 0;
        for (int i = from - 1; i >= 0; i--) {
            char c = source.charAt(i);
            if (c == ';' && depthParen == 0 && depthBracket == 0 && depthBrace == 0) {
                return -1;
            }
            switch (c) {
                case ')' -> depthParen++;
                case '(' -> {
                    if (depthParen == 0) {
                        return i;
                    }
                    depthParen--;
                }
                case ']' -> depthBracket++;
                case '[' -> {
                    if (depthBracket > 0) {
                        depthBracket--;
                    }
                }
                case '}' -> depthBrace++;
                case '{' -> {
                    if (depthBrace == 0) {
                        // Crossed a statement-block boundary without finding an enclosing call.
                        return -1;
                    }
                    depthBrace--;
                }
                default -> { /* keep scanning */ }
            }
        }
        return -1;
    }

    /**
     * Returns {@code true} when the caret sits in a context that the semantic provider
     * handles on its own and the lexical provider contribution is not needed.
     */
    public static boolean isSemanticOnlyContext(CompletionContext context) {
        if (isAnnotationTypeContext(context) || isAnnotationArgumentContext(context) ||
                isAnnotationValueContext(context) ||
                isInstanceofTypeContext(context) || isCastTypeContext(context)) {
            return true;
        }
        return context.prefix().isBlank() && (isInvocationArgumentContext(context) ||
                isThrowsClauseContext(context) || isCatchClauseContext(context) ||
                isCaseLabelContext(context) || isExtendsClauseContext(context) ||
                isImplementsClauseContext(context) || isPermitsClauseContext(context));
    }

    /**
     * Detects when the caret is inside the argument list of a method invocation or constructor
     * call (e.g. {@code box.getChildren().add(|)}, {@code list.add(roo|)}, {@code new VBox(|)}).
     *
     * <h2>Algorithm</h2>
     * <ol>
     *   <li>Skip the {@code expr.|} member-access cases — those are routed through
     *       {@link #isMemberCompletionContext} instead.</li>
     *   <li>Walk back from {@link CompletionContext#anchorOffset()} via
     *       {@link #findEnclosingInvocationOpenParen} to find the nearest unmatched
     *       {@code (}; bail out if none exists (caret not inside any argument list) or if it
     *       belongs to a structural construct ({@code {…}}, top-level {@code ;}, …).</li>
     *   <li>Read the identifier immediately preceding that {@code (}. It must be a real
     *       Java identifier and not one of the control-flow / expression-starter keywords.</li>
     *   <li>Distinguish invocation from method/constructor declaration by inspecting the
     *       token before the identifier:
     *       <ul>
     *         <li>punctuation ({@code ;}, <code>{</code>, {@code (}, {@code ,}, {@code =}, <code>}</code>,
     *             {@code >}, {@code :}, …) → invocation/expression position → return {@code true};</li>
     *         <li>another identifier token — if it's {@code new}, this is a constructor call
     *             ({@code new VBox(arg, sec|)}) → return {@code true}; otherwise it is a
     *             return type or modifier ({@code void start(|}) → declaration → return
     *             {@code false}.</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p>Returns {@code false} for the start of the code, for blank/empty sources, and
     * any position that doesn't satisfy the steps above.</p>
     */
    public static boolean isInvocationArgumentContext(CompletionContext context) {
        String source = Objects.requireNonNull(context).documentText();
        int anchor = context.anchorOffset();
        if (source == null || source.isEmpty() || anchor <= 0) {
            return false;
        }
        // Exclude `expr.|` (member access already handled by isMemberCompletionContext).
        if (source.charAt(anchor - 1) == '.') {
            return false;
        }
        NameBeforeParen name = nameBeforeEnclosingParen(source, anchor);
        if (name == null || isNonInvocationKeyword(name.text())) {
            return false;
        }
        return isInvocationOrNewBefore(source, name.start() - 1);
    }

    /**
     * Pure-prefix check: returns {@code true} when {@code prefix} is a non-empty
     * lowercase ASCII string that could be the beginning of one of the Java reserved
     * words listed in {@link #JAVA_KEYWORDS}.
     */
    public static boolean isPrefixOfJavaKeyword(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return false;
        }
        for (int k = 0; k < prefix.length(); k++) {
            char ch = prefix.charAt(k);
            if (ch < 'a' || ch > 'z') {
                return false;
            }
        }

        return JAVA_KEYWORDS.stream().anyMatch(kw -> kw.startsWith(prefix));
    }

    /**
     * Detects when the caret is positioned in a {@code throws} clause of a method or
     * constructor declaration, e.g. {@code void run() throws |} or
     * {@code void run() throws IOException, |}. See
     * {@link #isInCommaSeparatedListAfterKeyword} for the shared walk-back logic.
     */
    public static boolean isThrowsClauseContext(CompletionContext context) {
        return isInCommaSeparatedListAfterKeyword(context, "throws");
    }

    /**
     * Detects when the caret is positioned inside a {@code catch (…)} parameter list,
     * including multi-catch like {@code catch (IOException | Sql|)}. Reuses
     * {@link #nameBeforeEnclosingParen} so the walk-back logic stays in one place: the
     * enclosing paren is located, the immediately preceding identifier-token is read,
     * and the clause matches when that token is the {@code catch} keyword.
     */
    public static boolean isCatchClauseContext(CompletionContext context) {
        String source = Objects.requireNonNull(context).documentText();
        int anchor = context.anchorOffset();
        if (source == null || source.isEmpty() || anchor <= 0) {
            return false;
        }
        NameBeforeParen name = nameBeforeEnclosingParen(source, anchor);
        return name != null && "catch".equals(name.text());
    }

    /**
     * Detects when the caret is positioned on a {@code switch} case label, e.g.
     * {@code case |}, {@code case RE|}, or the multi-label form {@code case RED, |}.
     * Shares the walk-back logic with {@link #isThrowsClauseContext} (see
     * {@link #isInCommaSeparatedListAfterKeyword}).
     */
    public static boolean isCaseLabelContext(CompletionContext context) {
        return isInCommaSeparatedListAfterKeyword(context, "case");
    }

    /**
     * Detects when the caret is positioned in an {@code extends} clause —
     * {@code class Foo extends |}, {@code interface I extends A, |}, etc.
     */
    public static boolean isExtendsClauseContext(CompletionContext context) {
        return isInCommaSeparatedListAfterKeyword(context, "extends");
    }

    /**
     * Detects when the caret is positioned in an {@code implements} clause —
     * {@code class Foo implements |}, {@code class Foo implements A, B, |}, etc.
     */
    public static boolean isImplementsClauseContext(CompletionContext context) {
        return isInCommaSeparatedListAfterKeyword(context, "implements");
    }

    /**
     * Detects when the caret is positioned in a {@code permits} clause of a sealed
     * type declaration — {@code sealed class Foo permits |}, {@code … permits A, |}.
     */
    public static boolean isPermitsClauseContext(CompletionContext context) {
        return isInCommaSeparatedListAfterKeyword(context, "permits");
    }

    /**
     * Returns the declaration keyword ({@code "class"} / {@code "interface"} /
     * {@code "enum"} / {@code "record"}) that introduces the type whose
     * {@code extends} / {@code implements} / {@code permits} clause currently
     * contains the caret, or {@code null} when the structure cannot be
     * recognised. Used by inheritance-clause completion to bias proposals to
     * classes vs interfaces.
     *
     * <p>Walks back from the start of {@code keyword} (located via the same
     * helper that powers {@link #isInCommaSeparatedListAfterKeyword}), skipping
     * over the simple/qualified type name, generic parameter lists ({@code &lt;…&gt;})
     * and the usual separators (whitespace, dots, commas, {@code ?}, {@code &amp;}),
     * until it lifts out a declaration keyword.</p>
     */
    public static String declarationKindBefore(CompletionContext context, String keyword) {
        return walkBackToDeclaration(context, keyword, false);
    }

    /**
     * Returns the simple name of the type whose {@code extends} / {@code implements} /
     * {@code permits} clause currently contains the caret (e.g. {@code "C"} for
     * {@code class C extends |}), or {@code null} when the structure cannot be
     * recognized. Useful for filtering self-referential proposals out of the
     * inheritance popup.
     */
    public static String currentDeclarationNameBefore(CompletionContext context, String keyword) {
        return walkBackToDeclaration(context, keyword, true);
    }

    /**
     * Shared walk-back driver for {@link #declarationKindBefore} and
     * {@link #currentDeclarationNameBefore}. Returns the declaration keyword
     * (when {@code returnTypeName == false}) or the simple name that immediately
     * follows it (when {@code returnTypeName == true}).
     */
    private static String walkBackToDeclaration(CompletionContext context, String keyword, boolean returnTypeName) {
        int keywordOffset = findKeywordOffsetInCommaSeparatedList(context, keyword);
        if (keywordOffset < 0) {
            return null;
        }
        String source = context.documentText();
        int i = keywordOffset - 1;
        int genericDepth = 0;
        String previousWord = null;
        while (i >= 0) {
            char c = source.charAt(i);
            if (c == '>') {
                genericDepth++;
                i--;
                continue;
            }
            if (c == '<') {
                if (genericDepth > 0) {
                    genericDepth--;
                }
                i--;
                continue;
            }
            if (genericDepth > 0) {
                i--;
                continue;
            }
            if (Character.isWhitespace(c) || c == '.' || c == ',' || c == '?' || c == '&') {
                i--;
                continue;
            }
            if (Character.isJavaIdentifierPart(c)) {
                int end = i + 1;
                while (i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) {
                    i--;
                }
                String word = source.substring(i + 1, end);
                if ("class".equals(word) || "interface".equals(word) || "enum".equals(word) || "record".equals(word)) {
                    return returnTypeName ? previousWord : word;
                }
                previousWord = word;
                continue;
            }
            return null;
        }
        return null;
    }

    /**
     * Detects when the caret is positioned on the right-hand side of an {@code instanceof}
     * expression — the type slot of {@code obj instanceof |} or {@code obj instanceof Sc|}.
     * Resolves by skipping whitespace backward from the anchor and checking whether the
     * immediately preceding identifier token is the {@code instanceof} keyword.
     *
     * <p>Does <em>not</em> fire on the pattern-binding name slot ({@code obj instanceof
     * String s|}) or on a {@code when} guard expression ({@code obj instanceof String s
     * when |}) — those positions are followed by an identifier other than {@code instanceof},
     * so the keyword check fails as expected.</p>
     */
    public static boolean isInstanceofTypeContext(CompletionContext context) {
        String source = Objects.requireNonNull(context).documentText();
        int anchor = context.anchorOffset();
        if (source == null || source.isEmpty() || anchor <= 0) {
            return false;
        }
        return isKeywordImmediatelyBefore(source, anchor - 1, "instanceof");
    }

    /**
     * Detects when the caret is positioned inside the type slot of a cast expression,
     * i.e. inside the {@code (...)} of {@code (Sc|) obj} or {@code (|) obj}.
     *
     * <p>Cast detection is tighter than the generic "inside enclosing {@code (}" check
     * to avoid firing on every parenthesised expression. The rules are:</p>
     * <ul>
     *   <li>caret sits inside an unmatched {@code (} (with the usual statement-block
     *       guard) — same as the invocation/catch detectors;</li>
     *   <li>the open paren is <em>not</em> preceded by an identifier token (which would
     *       make it a method call, a constructor call, an {@code if}/{@code while}/…
     *       control-flow head, or a {@code catch} parameter list — all already
     *       handled by other detectors);</li>
     *   <li>the matching {@code )} exists and the next non-whitespace character after
     *       it is an expression-starter — an identifier letter, an opening paren, a
     *       string literal quote, or one of the unary operators {@code !} / {@code ~} /
     *       {@code +} / {@code -}. Casts must be applied to an expression; a
     *       parenthesised expression like {@code (2 + Sc|)} is followed by {@code ;} or
     *       an operator and is correctly rejected by this rule.</li>
     * </ul>
     */
    public static boolean isCastTypeContext(CompletionContext context) {
        String source = Objects.requireNonNull(context).documentText();
        int anchor = context.anchorOffset();
        if (source == null || source.isEmpty() || anchor <= 0) {
            return false;
        }
        // Exclude `expr.|` (member-access path).
        if (source.charAt(anchor - 1) == '.') {
            return false;
        }
        int openParen = findEnclosingInvocationOpenParen(source, anchor);
        if (openParen < 0) {
            return false;
        }
        // The open paren must NOT be a method/constructor/control-flow head — i.e. no
        // identifier token sits immediately before it.
        int beforeParen = skipWhitespaceBackward(source, openParen - 1);
        if (beforeParen >= 0 && Character.isJavaIdentifierPart(source.charAt(beforeParen))) {
            return false;
        }
        int closeParen = findMatchingCloseParen(source, openParen);
        if (closeParen < 0) {
            return false;
        }
        int j = closeParen + 1;
        while (j < source.length() && Character.isWhitespace(source.charAt(j))) {
            j++;
        }
        if (j >= source.length()) {
            return false;
        }
        char next = source.charAt(j);
        return Character.isJavaIdentifierStart(next)
                || next == '(' || next == '"'
                || next == '!' || next == '~' || next == '+' || next == '-';
    }

    /**
     * Returns true if the type is declared in the list of imports, or in a wildcard-imported package,
     * or in the same package.
     */
    public static boolean isDirectlyVisibleType(String fqcn, String packageName, Map<String, String> imports,
                                                 Set<String> wildcardImportPackages, String currentPackage) {
        String simpleName = simpleName(fqcn);
        String explicit = imports.get(simpleName);
        if (fqcn.equals(explicit)) {
            return true;
        }
        if (wildcardImportPackages.contains(packageName)) {
            return true;
        }
        return currentPackage != null && currentPackage.equals(packageName);
    }

    // private helpers

    /**
     * Identifier token immediately preceding the enclosing unmatched {@code (} around
     * {@code anchor}: the literal name plus its start offset (so callers that need to
     * walk further back have a usable anchor).
     */
    private record NameBeforeParen(String text, int start) {}

    /**
     * Returns the identifier token sitting just before the enclosing unmatched {@code (}
     * around {@code anchor} (e.g. {@code add} for {@code add(|)}, {@code catch} for
     * {@code catch (|)}). Whitespace between the {@code (} and the token is skipped, but
     * no other character is — anything else (a {@code ,}, {@code )}, dotted qualifier,
     * etc.) makes the helper return {@code null}. Also returns {@code null} when there
     * is no enclosing paren at all.
     */
    private static NameBeforeParen nameBeforeEnclosingParen(String source, int anchor) {
        int parenOffset = findEnclosingInvocationOpenParen(source, anchor);
        if (parenOffset < 0) {
            return null;
        }
        int i = skipWhitespaceBackward(source, parenOffset - 1);
        if (i < 0 || !Character.isJavaIdentifierPart(source.charAt(i))) {
            return null;
        }
        int end = i + 1;
        while (i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) {
            i--;
        }
        int start = i + 1;
        return new NameBeforeParen(source.substring(start, end), start);
    }

    private static boolean isNonInvocationKeyword(String name) {
        return CONTROL_FLOW_KEYWORDS.contains(name);
    }

    private static boolean isNewKeywordContext(String source, int offset, boolean skipIdentifierPrefix) {
        if (source == null || source.isBlank() || offset <= 0) {
            return false;
        }
        int i = Math.clamp(offset, 0, source.length()) - 1;

        if (skipIdentifierPrefix && i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) {
            while (i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) {
                i--;
            }
        }
        return isNew(source, i);
    }

    private static boolean isNew(String source, int i) {
        return isKeywordImmediatelyBefore(source, i, "new");
    }

    /**
     * Returns {@code true} when the token sitting just before {@code from} (whitespace
     * skipped) is a non-identifier character — punctuation such as {@code .}, {@code ,},
     * {@code =}, {@code (}, {@code ;}, {@code {} or {@code }}, i.e. an expression
     * position — or the {@code new} keyword. Returns {@code false} when the previous
     * token is any other identifier (which means we sit at a declaration like
     * {@code void start(} rather than an invocation).
     *
     * <p>Shared by {@link #isInvocationArgumentContext} so {@code foo.bar(|},
     * {@code (cond ? a : b).baz(|}, {@code new VBox(|} and {@code add(0, |} all classify
     * as invocation positions, while {@code void start(|} does not.</p>
     */
    private static boolean isInvocationOrNewBefore(String source, int from) {
        int i = skipWhitespaceBackward(source, from);
        if (i < 0 || !Character.isJavaIdentifierPart(source.charAt(i))) {
            // Punctuation (or start of file with nothing but whitespace) → invocation
            // position. The original textual walk-back has no way to distinguish a real
            // call from a chain like `expr.method(`; both are valid expressions whose
            // argument list semantic completion can populate.
            return true;
        }
        // Identifier before the name: the only invocation-like case is `new VBox(` —
        // anything else (a return type, a modifier, …) is a declaration.
        return isKeywordImmediatelyBefore(source, from, "new");
    }

    /**
     * Returns {@code true} when the identifier token immediately preceding {@code from}
     * (whitespace skipped) equals {@code keyword}. Shared by detectors of the form
     * "caret sits right after the {@code <keyword>} token".
     */
    private static boolean isKeywordImmediatelyBefore(String source, int from, String keyword) {
        int i = skipWhitespaceBackward(source, from);
        if (i < 0 || !Character.isJavaIdentifierPart(source.charAt(i))) {
            return false;
        }
        int end = i + 1;
        while (i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) {
            i--;
        }
        return keyword.equals(source.substring(i + 1, end));
    }

    private static int scanIdentifierEnd(String source, int start) {
        int i = Math.max(0, start);
        while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) {
            i++;
        }
        return i;
    }

    private static void collectItemsForExpectedType(CompilationController controller,
                                                    TypeElement expectedType,
                                                    String prefix,
                                                    boolean includeCrossPackageSubtypes,
                                                    CompletionCancellation cancellation,
                                                    Map<String, CompletionItem> result) {
        String lowerPrefix = lowerPrefix(prefix);
        String simpleName = expectedType.getSimpleName().toString();
        if (!prefixMismatch(simpleName, lowerPrefix)) {
            putItem(result, expectedType(controller.getElements(), expectedType, prefix));
        }
        collectAssignableTypesFromClasspath(controller, expectedType, prefix, includeCrossPackageSubtypes, cancellation)
                .forEach(assignable -> putItem(result, assignable));
        // Always suggest naming-convention wrappers (cheap, targeted, e.g. WeakXxx for each Xxx interface).
        JavaCompletionWrapperUtils.createConventionAssignableTypeItems(expectedType, prefix)
                .forEach(wrapper -> putItem(result, wrapper));
        JavaCompletionWrapperUtils.collectStructuralWrapperTypeItems(controller, expectedType, prefix, cancellation)
                .forEach(wrapper -> putItem(result, wrapper));
    }

    /**
     * Unwraps a parameter type into the {@link TypeMirror} that may legally appear on
     * the right-hand side of {@code new}: declared types pass through, type variables
     * collapse to their upper bound, everything else (primitives, arrays, …) yields
     * {@code null}.
     */
    private static TypeMirror resolveNewInstantiationType(TypeMirror parameterType) {
        if (parameterType == null) {
            return null;
        }
        return switch (parameterType.getKind()) {
            case DECLARED -> parameterType;
            case TYPEVAR -> ((TypeVariable) parameterType).getUpperBound();
            default -> null;
        };
    }

    private static TypeMirror resolveEnclosingReturnType(TreePath returnPath, CompilationController controller) {
        for (TreePath current = returnPath.getParentPath(); current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof MethodTree method) {
                Tree returnTypeTree = method.getReturnType();
                return returnTypeTree == null ? null
                        : controller.getTrees().getTypeMirror(new TreePath(current, returnTypeTree));
            }
            if (current.getLeaf() instanceof LambdaExpressionTree) {
                // Lambda return-type inference requires deeper analysis; skip rather than guess.
                return null;
            }
        }
        return null;
    }

    /**
     * Returns the static {@link TypeMirror} of the cast's operand expression for the
     * caret position inside {@code (|) operand}, or {@code null} when the operand
     * cannot be located / resolved.
     */
    static TypeMirror resolveCastOperandType(CompilationController controller,
                                                     String source, int anchor) {
        if (source == null || source.isEmpty() || anchor <= 0) {
            return null;
        }
        int openParen = findEnclosingInvocationOpenParen(source, anchor);
        if (openParen < 0) {
            return null;
        }
        int closeParen = findMatchingCloseParen(source, openParen);
        if (closeParen < 0) {
            return null;
        }
        int i = closeParen + 1;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        if (i >= source.length() || !Character.isJavaIdentifierStart(source.charAt(i))) {
            return null;
        }
        int end = i;
        while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) {
            end++;
        }
        // resolveMemberQualifierFallback walks back from the supplied offset to find
        // the qualifier identifier — passing `end` makes it pick up the operand name.
        JavaMemberQualifierResolver.QualifierResolution resolution =
                resolveMemberQualifierFallback(controller, source, end);
        return resolution == null ? null : resolution.type();
    }

    /**
     * Shared detector for "caret sits in a comma-separated list of identifiers/dotted
     * names introduced by a single keyword" — the shape of both {@code throws T1, |}
     * (throws clauses) and {@code case L1, |} (switch case labels).
     *
     * <p>Walks back from the anchor over <em>allowed inner characters</em>:</p>
     * <ul>
     *   <li>whitespace (the typical separator),</li>
     *   <li>{@code ,} (list separator),</li>
     *   <li>{@code .} (dotted qualified names like {@code java.io.IOException}),</li>
     *   <li>Java identifier parts (any token in the list, including the leading
     *       keyword itself once we reach it).</li>
     * </ul>
     *
     * <p>The walk succeeds when one of the identifier tokens it lifts out matches
     * {@code keyword}. Any other character ({@code (}, {@code )}, {@code {},
     * {@code }}, {@code ;}, {@code :}, {@code >}, {@code <}, {@code =}, …) is a
     * structural stopper and the walk fails — the caret is somewhere else (a method
     * body, a parameter list, a case body past {@code :}, an arrow-form body past
     * {@code ->}, etc.).</p>
     */
    private static boolean isInCommaSeparatedListAfterKeyword(CompletionContext context, String keyword) {
        return findKeywordOffsetInCommaSeparatedList(context, keyword) >= 0;
    }

    /**
     * Same walk as {@link #isInCommaSeparatedListAfterKeyword} but returns the start
     * offset of the matching keyword (or {@code -1} when the structure does not match).
     * Exposed so callers can resume scanning further back (e.g. to find the declaration
     * keyword that introduced an {@code extends} clause).
     */
    private static int findKeywordOffsetInCommaSeparatedList(CompletionContext context, String keyword) {
        String source = Objects.requireNonNull(context).documentText();
        int anchor = context.anchorOffset();
        if (source == null || source.isEmpty() || anchor <= 0) {
            return -1;
        }
        int i = anchor - 1;
        while (i >= 0) {
            char ch = source.charAt(i);
            if (Character.isWhitespace(ch) || ch == ',' || ch == '.') {
                i--;
                continue;
            }
            if (Character.isJavaIdentifierPart(ch)) {
                int end = i + 1;
                while (i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) {
                    i--;
                }
                int start = i + 1;
                if (keyword.equals(source.substring(start, end))) {
                    return start;
                }
                continue;
            }
            return -1;
        }
        return -1;
    }
}
