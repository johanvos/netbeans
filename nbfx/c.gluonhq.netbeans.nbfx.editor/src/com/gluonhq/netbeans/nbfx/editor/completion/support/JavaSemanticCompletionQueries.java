package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionCancellation;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionContext;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionProvider;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import org.netbeans.api.java.source.CompilationController;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import java.util.ArrayList;
import java.util.List;

import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaAnnotationCompletionUtils.annotationNameForArgument;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaAnnotationCompletionUtils.annotationValueContext;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaAnnotationCompletionUtils.collectAnnotationMembers;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaAnnotationCompletionUtils.collectAnnotationTypes;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaAnnotationCompletionUtils.collectAnnotationValueProposals;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionContextUtils.addGeneralNewContextItems;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionContextUtils.asTypeElement;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionContextUtils.collectExpectedTypeItems;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionContextUtils.currentDeclarationNameBefore;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionContextUtils.declarationKindBefore;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionContextUtils.findEnclosingInvocationOpenParen;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionContextUtils.hasResolvableExpectedType;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionContextUtils.isInsideNewTypeIdentifierWithOpenParen;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionContextUtils.isPrefixOfJavaKeyword;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionContextUtils.resolveCastOperandType;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionContextUtils.resolveStandaloneNewExpectedType;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionInvocationUtils.resolveArgumentIndex;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionInvocationUtils.resolveConstructorOverloadsFallback;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionInvocationUtils.resolveInvocationOverloads;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.isInvalidExpressionType;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.lowerPrefix;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.prefixMismatch;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItems.semanticField;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItems.separator;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionNewContextUtils.collectTypeItems;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaInheritanceClauseUtils.collectInheritanceTypes;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaInvocationArgumentUtils.resolveReceiverDeclaredType;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaInvocationArgumentUtils.resolveSubstitutedParameterType;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaMemberQualifierResolver.collectSemanticMembers;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaMemberQualifierResolver.findMemberSelectPath;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaMemberQualifierResolver.resolveMemberQualifierFallback;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaPackagePathCollector.collectPackagePathItems;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaPackagePathCollector.collectTopLevelPackageItems;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaScopeIdentifierCollector.collectScopeIdentifiers;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaScopeIdentifierCollector.collectScopeIdentifiersAssignableTo;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaScopeIdentifierCollector.resolveScopeForIdentifier;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaSemanticQueryRunner.runQueryWithSource;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaSourceRepair.synthesizeBalancedInvocation;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaSourceRepair.synthesizeIdentifierTerminator;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaSourceTextScanner.countCommaArgIndex;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaSourceTextScanner.resolveSemanticOffset;

/**
 * Semantic completion query entry points. Each public method is a small recipe combining
 * helpers from this package and returns an already-resolved item list.
 */
public final class JavaSemanticCompletionQueries {

    private JavaSemanticCompletionQueries() {
    }

    // Query entry points

    /**
     * Type completion right after the {@code new} keyword. Three resolution paths,
     * tried in order:
     * <ol>
     *   <li><b>Inside a typed {@code new VBo|x(} identifier</b>: the parser can't yet
     *       see the constructor, so fall straight through to the general type catalog
     *       (visible imports + {@code java.lang} + packages).</li>
     *   <li><b>Inside an invocation argument</b> ({@code add(new |)},
     *       {@code new VBox(new |)}, …): walk the path outward to the enclosing
     *       {@link MethodInvocationTree}/{@link NewClassTree}, then propose the
     *       expected parameter type plus its assignable subtypes and the usual
     *       wrapper conventions ({@code Weak<Name>}). On second {@code Ctrl+Space}
     *       the general catalog is returned instead.</li>
     *   <li><b>Standalone {@code new |}</b>: an enclosing variable initializer,
     *       assignment LHS or {@code return} statement can pin down the expected
     *       type; otherwise the general type catalog is returned.</li>
     * </ol>
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code Scene scene = new |} — variable initializer pins {@code Scene}.</li>
     *   <li>{@code scene.setRoot(new |)} — argument slot pins {@code Parent} plus
     *       subtypes ({@code Group}, {@code Pane}, …).</li>
     *   <li>{@code box.getChildren().add(new |)} — generic-aware substitution
     *       resolves the slot to {@code Node} plus subtypes ({@code Button}, …).</li>
     *   <li>{@code new |} inside a plain statement — no expected-type context, so
     *       the general type catalog is shown.</li>
     * </ul>
     */
    public static List<CompletionItem> queryNewWithJavaSource(CompletionContext context,
                                                              CompletionCancellation cancellation) {
        boolean showAllItems = context.queryType() == CompletionProvider.COMPLETION_ALL_QUERY_TYPE;
        return JavaSemanticQueryRunner.runQuery(context, cancellation, (controller, items) -> {
            int offset = resolveSemanticOffset(context.documentText(), context.caretOffset());
            TreePath path = controller.getTreeUtilities().pathFor(offset);

            if (path == null || isInsideNewTypeIdentifierWithOpenParen(context)) {
                addGeneralNewContextItems(
                        items, controller, context.prefix(), showAllItems, context.documentText(), cancellation);
                return;
            }

            JavaCompletionInvocationUtils.InvocationResolutionRecord invocation = resolveNewInvocation(
                    controller, path, context.documentText(), context.anchorOffset());
            if (invocation == null) {
                addStandaloneNewItems(controller, context, path, items, showAllItems, cancellation);
                return;
            }
            if (showAllItems) {
                addGeneralNewContextItems(
                        items, controller, context.prefix(), true, context.documentText(), cancellation);
                return;
            }
            items.addAll(JavaCompletionContextUtils.collectExpectedNewArgumentTypes(
                    controller, invocation.overloads(), invocation.receiverType(),
                    invocation.argIndex(), context.prefix(), cancellation));
            appendGeneralTypesAndPackagesTail(controller, context, items, false, cancellation);
        });
    }

    /**
     * Qualified-path completion for import declarations. Extracts the dotted qualifier
     * immediately before the caret's preceding {@code .}, enumerates the sub-packages and
     * top-level types reachable under it, and — when the import is a {@code static} one —
     * additionally appends the qualifier type's static members.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code import javafx.scene.|} → sub-packages ({@code control}, {@code layout},
     *       {@code paint}, …) plus top-level types ({@code Scene}, {@code Group}, …).</li>
     *   <li>{@code import javafx.scene.layout.VBox.|} — qualifier resolves to a type, so
     *       only nested types are returned (no sub-packages).</li>
     *   <li>{@code import static javafx.animation.Animation.|} — sub-packages/types under
     *       the qualifier and {@code Animation}'s {@code public static} members
     *       (fields, enum constants, methods, with bare-name insertion).</li>
     * </ul>
     *
     * <p>Returns an empty list when the qualifier cannot be extracted (caret not after a
     * {@code .}) or when the underlying
     * {@link org.netbeans.api.java.source.JavaSource JavaSource} task fails to run.</p>
     */
    public static List<CompletionItem> queryPackageWithJavaSource(CompletionContext context,
                                                                  CompletionCancellation cancellation) {
        // 1. Find the package path before the caret
        String packagePrefix = JavaCompletionTypeUtils.extractQualifier(
                context.documentText(), context.anchorOffset() - 1);
        if (packagePrefix == null || packagePrefix.isBlank()) {
            return List.of();
        }
        // 2. Find if it includes {@code static}
        boolean staticImport = JavaImportContext.from(context).isStatic();
        boolean showAllItems = context.queryType() == CompletionProvider.COMPLETION_ALL_QUERY_TYPE;

        // 3. Run the query:
        return JavaSemanticQueryRunner.runQuery(context, cancellation,
            (controller, items) -> {
                // 1. collect the package-path items
                items.addAll(collectPackagePathItems(
                        controller, packagePrefix, context.prefix(), staticImport, cancellation));

                if (staticImport) {
                    // 2. And for static imports, collect the qualifier type's static members (fields, methods, enum constants).
                    TypeElement ownerType = controller.getElements().getTypeElement(packagePrefix);
                    if (ownerType != null) {
                        items.addAll(collectSemanticMembers(
                                controller, ownerType, true, showAllItems, context.prefix(),
                                true, cancellation));
                    }
                }
        });
    }

    /**
     * Top-level package completion for import declarations whose path has not yet reached
     * a {@code .} — both {@code import jav|} and {@code import static jav|} land here.
     * The {@code static} keyword is irrelevant at this stage because the typed identifier
     * can only be a (top-level) package name; the static-member enumeration happens later,
     * once the user moves into {@link #queryPackageWithJavaSource} territory by typing the
     * first dot.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code import jav|} → {@code java}, {@code javax}, {@code javafx}, …</li>
     *   <li>{@code import |} → every top-level package on the classpath
     *       (filtered to exclude JDK-internal roots).</li>
     *   <li>{@code import static jav|} → same set as {@code import jav|}.</li>
     * </ul>
     *
     * <p>Returns an empty list when the underlying
     * {@link org.netbeans.api.java.source.JavaSource JavaSource} task fails to run.</p>
     */
    public static List<CompletionItem> queryImportTopLevelPackageWithJavaSource(CompletionContext context,
                                                                                CompletionCancellation cancellation) {
        return JavaSemanticQueryRunner.runQuery(context, cancellation,
            (controller, items) ->
                items.addAll(collectTopLevelPackageItems(controller, context.prefix(), cancellation)));
    }

    /**
     * Annotation-type completion for {@code @|} / {@code @Over|}.
     * Delegates to {@link JavaAnnotationCompletionUtils#collectAnnotationTypes}, which
     * walks the project {@link org.netbeans.api.java.source.ClassIndex ClassIndex} and
     * supplements the result with {@code java.lang.*} annotations enumerated directly
     * through {@link javax.lang.model.util.Elements#getPackageElement} — the JDK
     * bootstrap classpath is not always covered by the index scopes.
     */
    public static List<CompletionItem> queryAnnotationTypeWithJavaSource(CompletionContext context,
                                                                         CompletionCancellation cancellation) {
        return JavaSemanticQueryRunner.runQuery(context, cancellation,
            (controller, items) ->
                items.addAll(collectAnnotationTypes(controller, context.prefix(), cancellation)));
    }

    /**
     * Annotation-member completion for {@code @Foo(|)} / {@code @Foo(va|)}. Extracts
     * the dotted annotation name preceding the enclosing {@code (}, resolves the
     * annotation type (direct lookup → import-resolved name → runtime fallback) and
     * lists each declared member ({@link javax.lang.model.element.ElementKind#METHOD METHOD}
     * children) as a {@code name = }-style proposal. Returns an empty list when the
     * caret isn't inside an annotation argument list.
     */
    public static List<CompletionItem> queryAnnotationArgumentWithJavaSource(CompletionContext context,
                                                                             CompletionCancellation cancellation) {
        String annotationName = annotationNameForArgument(context.documentText(), context.anchorOffset());
        if (annotationName == null) {
            return List.of();
        }
        return JavaSemanticQueryRunner.runQuery(context, cancellation,
            (controller, items) ->
                items.addAll(collectAnnotationMembers(controller, annotationName, context.documentText(),
                        context.prefix(), cancellation)));
    }

    /**
     * Annotation-value completion for {@code @Foo(member = |)} / {@code @Foo(member = Pref|)}
     * and the array-literal variants ({@code @Target(value = {ElementType.METHOD, |})}).
     *
     * <p>Walks back from the caret to recover the {@code member} name preceding the
     * enclosing {@code =}, resolves the annotation type the same way as
     * {@link #queryAnnotationArgumentWithJavaSource}, and dispatches to
     * {@link JavaAnnotationCompletionUtils#collectAnnotationValueProposals} which
     * currently emits qualified enum-constant rows (e.g. {@code RetentionPolicy.SOURCE})
     * for enum and array-of-enum members.</p>
     *
     * <p>Returns an empty list for member types without a specialised proposal — String,
     * primitives, {@code Class<?>}, nested annotations — letting the catch-all
     * identifier query take over.</p>
     */
    public static List<CompletionItem> queryAnnotationValueWithJavaSource(CompletionContext context,
                                                                          CompletionCancellation cancellation) {
        JavaAnnotationCompletionUtils.AnnotationValueContext valueContext =
                annotationValueContext(context.documentText(), context.anchorOffset());
        if (valueContext == null) {
            return List.of();
        }
        return JavaSemanticQueryRunner.runQuery(context, cancellation,
            (controller, items) ->
                items.addAll(collectAnnotationValueProposals(controller,
                        valueContext.annotationName(), valueContext.memberName(),
                        context.documentText(), context.prefix(), cancellation)));
    }

    /**
     * Throwable-type completion for {@code throws |} and {@code catch (|)} positions
     * (including multi-catch like {@code catch (IOException | Sql|)}).
     *
     * <p>Resolves {@link Throwable} via {@link javax.lang.model.util.Elements Elements}
     * and emits the expected-type row for it plus every assignable subtype on the
     * classpath via {@link JavaCompletionContextUtils#collectExpectedTypeItems}. A
     * packages-only tail follows (no {@code java.lang} type catalog — those are mostly
     * non-throwable noise here) so the user can still drill into a package containing a
     * project-local exception subtype.</p>
     *
     * <p>Returns an empty list when neither {@code Throwable} nor any package matches
     * the prefix, or when the underlying {@link org.netbeans.api.java.source.JavaSource
     * JavaSource} task fails.</p>
     */
    public static List<CompletionItem> queryThrowableTypeWithJavaSource(CompletionContext context,
                                                                        CompletionCancellation cancellation) {
        boolean showAllItems = context.queryType() == CompletionProvider.COMPLETION_ALL_QUERY_TYPE;
        return JavaSemanticQueryRunner.runQuery(context, cancellation, (controller, items) -> {
            TypeElement throwable = controller.getElements().getTypeElement("java.lang.Throwable");
            if (throwable != null) {
                items.addAll(JavaCompletionContextUtils.collectExpectedTypeItems(
                        controller, throwable.asType(), context.prefix(), showAllItems, cancellation));
            }
            appendPackagesTail(controller, context, items, cancellation);
        });
    }

    /**
     * Case-label completion for {@code switch (selector) { case | }} (statement and
     * expression forms, arrow / colon syntax, multi-label, type patterns).
     *
     * <p>Resolves the selector type off the enclosing {@link SwitchTree} /
     * {@link SwitchExpressionTree} and branches:</p>
     * <ul>
     *   <li><b>Enum selector</b> — proposes its {@code ENUM_CONSTANT} members
     *       (e.g. {@code case RE|} → {@code RED}).</li>
     *   <li><b>Reference-type selector</b> (Java 21+ type patterns) — delegates to
     *       {@link JavaCompletionContextUtils#collectExpectedTypeItems} for the
     *       selector type plus assignable subtypes, then appends a packages-only
     *       tail (e.g. {@code switch (paint) { case | }} → {@code Paint},
     *       {@code Color}, {@code LinearGradient}, …).
     *       {@code null} literal is included as well</li>
     * </ul>
     *
     * <p>Rhe {@code null} literal is offered as well: it is a valid
     * case label since Java 21 and the lexical provider is suppressed on blank-prefix
     * case-label positions, so the semantic query has to surface it.</p>
     *
     * <p>Returns no items when the selector type cannot be resolved, letting the
     * lexical provider take over.</p>
     */
    public static List<CompletionItem> queryCaseLabelWithJavaSource(CompletionContext context,
                                                                    CompletionCancellation cancellation) {
        boolean showAllItems = context.queryType() == CompletionProvider.COMPLETION_ALL_QUERY_TYPE;
        return JavaSemanticQueryRunner.runQuery(context, cancellation, (controller, items) -> {
            int probe = resolveSemanticOffset(context.documentText(), context.caretOffset());
            TreePath path = controller.getTreeUtilities().pathFor(probe);
            TypeMirror selectorType = resolveSwitchSelectorType(path, controller);
            TypeElement selectorElement = asTypeElement(selectorType);
            if (selectorElement == null) {
                return;
            }
            if (selectorElement.getKind() == ElementKind.ENUM) {
                collectEnumConstantItems(controller, selectorElement, context.prefix(), cancellation, items);
                return;
            }
            // Type-pattern position: `switch (paint) { case Color c -> … }`.
            // Include the selector type plus every assignable subtype.
            items.addAll(collectExpectedTypeItems(
                    controller, selectorType, context.prefix(), showAllItems, cancellation));
            appendNullLiteralIfMatching(context.prefix(), items);
            appendPackagesTail(controller, context, items, cancellation);
        });
    }

    /**
     * Inheritance-clause type completion for {@code class Foo extends |},
     * {@code interface I extends A, |}, {@code class Foo implements |},
     * {@code sealed class Foo permits |} and friends.
     *
     * <p>Routes the {@link JavaInheritanceClauseUtils.ClauseKind clause kind} (combined
     * with the declaration keyword recovered by
     * {@link JavaCompletionContextUtils#declarationKindBefore declarationKindBefore})
     * to one of the three filters supported by {@link JavaInheritanceClauseUtils}:
     * classes only for {@code extends} on a class declaration, interfaces only for
     * {@code implements} and for {@code interface … extends}, and both for
     * {@code permits}.</p>
     *
     * <p>The type catalog is scoped to the Ctrl+Space mode — first hit returns
     * {@code java.lang} plus visible imports / current package; a second hit
     * ({@link CompletionProvider#COMPLETION_ALL_QUERY_TYPE}) broadens to the full
     * classpath. A separator-prefixed packages list is always appended so the user
     * can still drill into a package that hosts a project-local supertype.</p>
     */
    public static List<CompletionItem> queryInheritanceClauseTypesWithJavaSource(CompletionContext context,
                                                                                 JavaInheritanceClauseUtils.ClauseKind clauseKind,
                                                                                 CompletionCancellation cancellation) {
        boolean showAllItems = context.queryType() == CompletionProvider.COMPLETION_ALL_QUERY_TYPE;
        JavaInheritanceClauseUtils.Filter filter = resolveInheritanceFilter(context, clauseKind);
        // The type currently being declared cannot legally appear as its own supertype —
        // exclude it from the popup (e.g. `class C extends |` should not propose `C`).
        String selfName = currentDeclarationNameBefore(context, clauseKeyword(clauseKind));
        return JavaSemanticQueryRunner.runQuery(context, cancellation, (controller, items) -> {
            items.addAll(collectInheritanceTypes(
                    controller, context.prefix(), filter, clauseKind, showAllItems,
                    context.documentText(), selfName, cancellation));
            appendPackagesTail(controller, context, items, cancellation);
        });
    }

    /**
     * {@code instanceof}-target type completion for {@code obj instanceof |} and
     * {@code obj instanceof Sc|}.
     *
     * <p>Walks up to the enclosing {@link InstanceOfTree}, resolves the operand
     * expression's {@link TypeMirror}, and reuses
     * {@link JavaCompletionContextUtils#collectExpectedTypeItems collectExpectedTypeItems}
     * so the popup shows the operand type plus assignable subtypes — exactly the
     * types for which the {@code instanceof} check carries useful information.</p>
     *
     * <p>The general types/packages tail follows: {@code java.lang} (always) plus
     * top-level packages, broadened to the full classpath catalog and cross-package
     * subtypes on a second Ctrl+Space
     * ({@link CompletionProvider#COMPLETION_ALL_QUERY_TYPE}).</p>
     */
    public static List<CompletionItem> queryInstanceofTypeWithJavaSource(CompletionContext context,
                                                                         CompletionCancellation cancellation) {
        boolean showAllItems = context.queryType() == CompletionProvider.COMPLETION_ALL_QUERY_TYPE;
        return JavaSemanticQueryRunner.runQuery(context, cancellation, (controller, items) -> {
            int offset = resolveSemanticOffset(context.documentText(), context.caretOffset());
            TreePath path = controller.getTreeUtilities().pathFor(offset);
            TypeMirror operandType = resolveInstanceofOperandType(path, controller);
            if (operandType != null) {
                items.addAll(collectExpectedTypeItems(
                        controller, operandType, context.prefix(), showAllItems, cancellation));
            }
            appendGeneralTypesAndPackagesTail(controller, context, items, showAllItems, cancellation);
        });
    }

    /**
     * Cast-target type completion for {@code (|) obj} and {@code (Sc|) obj}.
     *
     * <p>Expected-type resolution (first non-null wins):</p>
     * <ol>
     *   <li><b>Cast target</b> via {@link JavaCompletionContextUtils#resolveStandaloneNewExpectedType}
     *       — variable initializer ({@code Parent p = (|) obj}), assignment RHS, return
     *       statement, or known method-invocation argument slot.</li>
     *   <li><b>Cast operand</b> — the typical
     *       {@code ((|) p).method()} shape with no LHS / return / arg target: the
     *       operand's declared type is used instead, so e.g.
     *       {@code Paint p = …; ((|) p).darker();} surfaces {@code Paint} plus its
     *       assignable subtypes ({@code Color}, {@code LinearGradient}, …).</li>
     * </ol>
     *
     * <p>When neither yields a type, falls back to the visible-type catalog
     * ({@code java.lang} + imports + current package). The general types/packages
     * tail is always appended, with classpath broadening on a second Ctrl+Space
     * ({@link CompletionProvider#COMPLETION_ALL_QUERY_TYPE}).</p>
     */
    public static List<CompletionItem> queryCastTypeWithJavaSource(CompletionContext context,
                                                                   CompletionCancellation cancellation) {
        boolean showAllItems = context.queryType() == CompletionProvider.COMPLETION_ALL_QUERY_TYPE;
        return JavaSemanticQueryRunner.runQuery(context, cancellation, (controller, items) -> {
            int offset = resolveSemanticOffset(context.documentText(), context.caretOffset());
            TreePath path = controller.getTreeUtilities().pathFor(offset);
            TypeMirror expected = resolveStandaloneNewExpectedType(path, controller);
            if (expected == null) {
                // No inferable target type: infer from the cast operand expression instead.
                expected = resolveCastOperandType(controller, context.documentText(), context.anchorOffset());
            }
            if (expected != null) {
                items.addAll(collectExpectedTypeItems(
                        controller, expected, context.prefix(), showAllItems, cancellation));
            }
            if (items.isEmpty()) {
                // No expected-type constraint: surface visible types from the classpath matching the prefix.
                items.addAll(collectTypeItems(controller, context.prefix(), showAllItems, false,
                        context.documentText(), cancellation));
            }
            appendGeneralTypesAndPackagesTail(controller, context, items, showAllItems, cancellation);
        });
    }

    /**
     * Member-access completion ({@code receiver.|} or {@code receiver.prefix|}).
     *
     * <h2>Resolution strategy</h2>
     * <ol>
     *   <li><b>Tree-based</b>: ask the parser for the {@link MemberSelectTree} covering
     *       the caret ({@link JavaMemberQualifierResolver#findMemberSelectPath}); the
     *       expression's {@link TypeMirror} gives the owner type, and an
     *       {@code expression.element instanceof TypeElement} test flips the
     *       {@code staticOnly} switch (so {@code Math.|} surfaces only static members).</li>
     *   <li><b>Text fallback</b>: when the tree path is missing or the expression's
     *       {@link TypeMirror} is invalid,
     *       {@link JavaMemberQualifierResolver#resolveMemberQualifierFallback} walks back
     *       from the dot, finds the qualifier identifier, and resolves it via local
     *       variable declarations + imports.</li>
     *   <li>If both branches yield no usable type, the query returns an empty list.</li>
     *   <li>Otherwise the owner type's members are enumerated via
     *       {@link JavaMemberQualifierResolver#collectSemanticMembers}, filtered by the
     *       active prefix and (optionally) the {@code COMPLETION_ALL_QUERY_TYPE} flag.</li>
     * </ol>
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code scene.setR|} → {@code setRoot}, {@code setRoot(Parent)}, …</li>
     *   <li>{@code Math.|} → static-only ({@code abs}, {@code PI}, …)</li>
     *   <li>{@code System.|} → static-only ({@code err}, {@code out}, …)</li>
     *   <li>{@code System.err.|} → static-only, uses selection and not expression: ({@code print}, {@code println}, …)</li>
     *   <li>{@code Scene scene = new Scene(...); scene.|} — fallback path: the partial
     *       source may not parse, but the regex local-variable scan finds {@code scene}'s
     *       declared type and the popup still gets populated.</li>
     * </ul>
     */
    public static List<CompletionItem> queryMemberWithJavaSource(CompletionContext context,
                                                                 CompletionCancellation cancellation) {
        int dotOffset = Math.max(0, context.anchorOffset() - 1);
        int caretProbe = Math.max(0, context.caretOffset() - 1);
        boolean showAllItems = context.queryType() == CompletionProvider.COMPLETION_ALL_QUERY_TYPE;
        return JavaSemanticQueryRunner.runQuery(context, cancellation, (controller, items) -> {
            TreePath memberPath = findMemberSelectPath(controller, dotOffset, caretProbe);
            TypeMirror expressionType = null;
            boolean staticOnly = false;

            if (memberPath != null && memberPath.getLeaf() instanceof MemberSelectTree selectTree) {
                TreePath qualifierPath = resolveMemberQualifierPath(
                        controller, memberPath, selectTree, context.documentText(), dotOffset);
                expressionType = controller.getTrees().getTypeMirror(qualifierPath);
                staticOnly = controller.getTrees().getElement(qualifierPath) instanceof TypeElement;
            }
            if (isInvalidExpressionType(expressionType)) {
                JavaMemberQualifierResolver.QualifierResolution fallback =
                        resolveMemberQualifierFallback(controller, context.documentText(), dotOffset);
                if (fallback != null) {
                    expressionType = fallback.type();
                    staticOnly = fallback.staticOnly();
                }
            }
            if (isInvalidExpressionType(expressionType)) {
                // The qualifier is neither a type nor a variable, so it could be a package typed at an
                // expression position.
                items.addAll(collectQualifiedPackageItems(controller, context, cancellation));
                return;
            }
            TypeElement ownerType = asTypeElement(expressionType);
            if (ownerType != null) {
                // Collect members items
                items.addAll(collectSemanticMembers(
                        controller, ownerType, staticOnly, showAllItems, context.prefix(), cancellation));
            }
        });
    }

    /**
     * Picks the tree node that acts as the qualifier for the completion dot at {@code dotOffset}
     * inside {@code selectTree}.
     *
     * <p>Usually the qualifier is the select's expression: for {@code scene.setR|} it follows
     * {@code scene}, and for {@code System.out.pri|} it follows {@code System.out}.</p>
     *
     * <p>But in case of a <em>trailing-dot</em> chain like {@code System.err.|} the parser cannot form the
     * incomplete {@code System.err.<empty>} select, therefore the qualifier has to be the select itself,
     * {@code System.err}, and not the expression, {@code System}.</p>
     */
    private static TreePath resolveMemberQualifierPath(CompilationController controller,
                                                       TreePath memberPath, MemberSelectTree selectTree,
                                                       String source, int dotOffset) {
        CompilationUnitTree unit = memberPath.getCompilationUnit();
        ExpressionTree expression = selectTree.getExpression();
        SourcePositions positions = controller.getTrees().getSourcePositions();
        long expressionEnd = positions.getEndPosition(unit, expression);
        if (expressionEnd >= 0 && expressionEnd < dotOffset && dotOffset <= source.length()
                && !source.substring((int) expressionEnd, dotOffset).isBlank()) {
            // A resolved member sits between the expression and the completion dot: the qualifier is
            // the whole select (e.g. `System.err` in `System.err.|`), not just its expression.
            return memberPath;
        }
        // Typical use case: return the expression
        return new TreePath(memberPath, expression);
    }

    /**
     * Package-path completion for a dotted qualifier typed at an expression/statement position
     * (not an import), e.g. {@code javafx.|}, {@code javafx.scene.|} or {@code javafx.sce|}.
     *
     * <p>When the member-access qualifier resolves to neither a type nor a variable, it may be a
     * package. This offers the sub-packages and top-level types reachable under that qualifier —
     * the same catalog the qualified-import path uses. {@link JavaPackagePathCollector} is
     * self-guarding: it returns an empty list when the qualifier is not a known package, so an
     * unresolved (broken-source) qualifier simply yields no items.</p>
     */
    private static List<CompletionItem> collectQualifiedPackageItems(CompilationController controller,
                                                                     CompletionContext context,
                                                                     CompletionCancellation cancellation) {
        // 1. Find the package path before the caret
        String packagePrefix = JavaCompletionTypeUtils.extractQualifier(
                context.documentText(), context.anchorOffset() - 1);
        if (packagePrefix == null || packagePrefix.isBlank()) {
            return List.of();
        }
        // 2. Collect the package-path items
        return collectPackagePathItems(controller, packagePrefix, context.prefix(), false, cancellation);
    }

    /**
     * Argument-position completion for a method or constructor call. Fires when the caret sits
     * inside an unmatched {@code (...)} whose head is a method/constructor name (not preceded by
     * {@code new} in the standalone-{@code new} flow.
     *
     * <h2>Resolution strategy</h2>
     * <ol>
     *   <li><b>Source repair</b>: fix an unterminated argument list ({@code add(|}) that prevents the Java
     *       parser from producing a usable {@link MethodInvocationTree} by inserting the minimal closers
     *       ({@code );}) to recover the tree without perturbing offsets at or before the caret.</li>
     *   <li><b>Argument index</b>: compute the zero-based index inside the comma-separated argument list.</li>
     *   <li><b>Overload resolution</b>: Walk the receiver type and collects every method (or constructor) whose name
     *       and number of arguments matches.</li>
     *   <li><b>Expected types</b>: each overload's parameter type at the active index is
     *       substituted against the receiver's type arguments, or dropped on error.</li>
     *   <li><b>Primary section</b>: in-scope identifiers (locals, parameters, fields, no-arg
     *       methods) assignable to any expected type are emitted.</li>
     *   <li><b>Secondary section</b>: when a non-blank prefix is being typed, every matching
     *       in-scope identifier is emitted regardless of expected type.</li>
     *   <li><b>Fallback tail</b>: {@code java.lang} types and packages so the popup is never
     *       empty on manual {@code Ctrl+Space}.</li>
     * </ol>
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code box.getChildren().add(|)} — surfaces every {@code Node}-typed local/field
     *       reachable from the caret.</li>
     *   <li>{@code stage.setScene(|)} — proposes {@code scene} (and other {@code Scene}-typed
     *       identifiers) plus the {@code java.lang} tail.</li>
     *   <li>{@code list.add(0, ele|)} — argument-index {@code 1} restricts overload resolution
     *       to the two-arg {@code add(int, E)}, so only {@code E}-typed identifiers prefixed
     *       with {@code "ele"} pass the assignable filter.</li>
     *   <li>{@code new VBox(0, chil|)} — varargs slot ({@code Node[]}) unwraps to {@code Node}
     *       so each {@code Node}-typed identifier is offered as a varargs payload.</li>
     * </ul>
     *
     * <p>Returns an empty list when no enclosing invocation is found, the parser produces no
     * usable tree even after repair, or the {@link org.netbeans.api.java.source.JavaSource
     * JavaSource} task fails to run.</p>
     */
    public static List<CompletionItem> queryInvocationArgumentWithJavaSource(CompletionContext context,
                                                                             CompletionCancellation cancellation) {
        // If the argument list is unterminated (e.g. `add(|`), the Java parser may then fail to produce a
        // usable MethodInvocationTree, so insert the minimal closers needed to balance brackets and recover the tree.
        String sourceOverride = synthesizeBalancedInvocation(context.documentText(), context.caretOffset());
        boolean showAllItems = context.queryType() == CompletionProvider.COMPLETION_ALL_QUERY_TYPE;
        return runQueryWithSource(context, sourceOverride, cancellation,
                (controller, items) -> {
                    // Argument index is computed textually so it stays correct when the parser
                    // hasn't fully populated the argument list (e.g. `add(0, |)` would otherwise
                    // report only one parsed argument).
                    int parenOffset = findEnclosingInvocationOpenParen(context.documentText(), context.anchorOffset());
                    if (parenOffset < 0) {
                        return;
                    }
                    int textualArgIndex = countCommaArgIndex(
                            context.documentText(), parenOffset + 1, context.anchorOffset());

                    int offset = resolveSemanticOffset(sourceOverride, context.caretOffset());
                    TreePath path = controller.getTreeUtilities().pathFor(offset);
                    TreePath invocationPath = findEnclosingInvocation(path);
                    List<ExecutableElement> overloads = invocationPath == null ? List.of()
                            : resolveInvocationOverloads(controller, invocationPath, textualArgIndex);

                    List<TypeMirror> expectedTypes = collectInvocationExpectedTypes(
                            controller, invocationPath, overloads, textualArgIndex);

                    // Primary section: assignable in-scope identifiers.
                    if (path != null && !expectedTypes.isEmpty()) {
                        // Collect assignable scope identifiers
                        items.addAll(collectScopeIdentifiersAssignableTo(
                                controller, path, expectedTypes, context.prefix(), cancellation));
                    }
                    // Secondary section: every prefix-matching in-scope identifier, regardless of expected type.
                    if (path != null && !context.prefix().isBlank()) {
                        // Collect scope identifiers
                        items.addAll(collectScopeIdentifiers(controller, path, context.prefix(), cancellation));
                    }
                    // Fallback section: general java.lang types and packages.
                    appendGeneralTypesAndPackagesTail(controller, context, items, showAllItems, cancellation);
                });
    }

    /**
     * Plain identifier completion at an expression position (e.g. {@code Scene scene = new Scene(...); sce|}).
     * The dispatcher routes here as the catch-all when no other semantic context
     * (member access, invocation argument, qualified/top-level import, …) matched.
     *
     * <h2>Resolution strategy</h2>
     * <ol>
     *   <li><b>Blank-prefix short-circuit</b>: with no prefix the query returns an empty list.</li>
     *   <li><b>Source repair</b>: Appends a {@code ;} and balances any unmatched <code>{</code> so the
     *       parser produces a usable tree.</li>
     *   <li><b>Scope resolution</b>: Probes several offsets around the caret to bypass the javac's errors.</li>
     *   <li><b>Scope identifiers</b>: locals, parameters, resource/exception variables,
     *       fields and methods that match the prefix are listed.</li>
     *   <li><b>Keyword-prefix gate</b>: if the prefix could be the start of a reserved word (e.g.
     *       {@code publi|}, {@code clas|}), the type-catalog section is suppressed.</li>
     *   <li><b>Type catalog</b>: appends {@code java.lang.*} types plus types reachable through explicit /
     *       wildcard imports and the current package. On a second {@code Ctrl+Space} the index-wide catalog is
     *       returned instead.</li>
     * </ol>
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code Scene scene = new Scene(); sce|} — surfaces the {@code scene} local
     *       (and any other in-scope identifiers starting with {@code sce}).</li>
     *   <li>{@code Sce|} — no scope match, the type catalog surfaces {@code Scene} from
     *       the explicit import.</li>
     *   <li>{@code publi|} — {@code public} keyword.</li>
     * </ul>
     *
     * <p>Returns an empty list when the prefix is blank, the {@link CompletionContext}
     * is {@code null}, or the {@link org.netbeans.api.java.source.JavaSource JavaSource}
     * task fails to run.</p>
     */
    public static List<CompletionItem> queryIdentifierWithJavaSource(CompletionContext context,
                                                                     CompletionCancellation cancellation) {
        if (context == null || context.prefix().isBlank()) {
            return List.of();
        }
        boolean showAllItems = context.queryType() == CompletionProvider.COMPLETION_ALL_QUERY_TYPE;
        // Insert a `;` after the identifier and close any unmatched `{` so the parser produces a usable tree.
        String repairedSource = synthesizeIdentifierTerminator(
                context.documentText(), context.anchorOffset(), context.prefix());
        // Suppress the type catalog when the prefix could grow into a Java reserved word
        boolean keywordPrefix = isPrefixOfJavaKeyword(context.prefix());
        return JavaSemanticQueryRunner.runQueryWithSource(context, repairedSource, cancellation,
                (controller, items) -> {
                    Scope scope = resolveScopeForIdentifier(controller, context);
                    if (scope != null) {
                        // In-scope identifiers: locals, parameters, resource/exception
                        // variables, fields and methods reachable from the caret.
                        items.addAll(collectScopeIdentifiers(controller, scope, context.prefix(), cancellation));
                    }
                    if (!keywordPrefix) {
                        // Type catalog: java.lang + directly visible imports / same-package
                        // (or every visible type on COMPLETION_ALL_QUERY_TYPE).
                        items.addAll(collectTypeItems(
                                controller, context.prefix(), showAllItems, false,
                                context.documentText(), cancellation));
                        // Top-level packages matching the prefix, so a qualified name can be typed
                        // from scratch at statement position (e.g. `jav|` -> `java`, `javafx`, ...).
                        items.addAll(collectTopLevelPackageItems(controller, context.prefix(), cancellation));
                    }
                });
    }

    // Shared building blocks used by the queries above

    /**
     * Walks {@code path} outward looking for a method/constructor invocation that can be
     * resolved to a set of overloads with at least one expected-type slot. Falls back to
     * the constructor-fallback resolver when the tree walk yielded nothing usable. The
     * returned record also carries the receiver {@link DeclaredType} (when the invocation
     * is a member-select like {@code box.getChildren().add(...)}) so that generic
     * parameter types can be substituted by the downstream expected-type collectors.
     */
    private static JavaCompletionInvocationUtils.InvocationResolutionRecord resolveNewInvocation(
            CompilationController controller, TreePath path, String source, int anchorOffset) {
        for (TreePath current = path; current != null; current = current.getParentPath()) {
            Tree leaf = current.getLeaf();
            if (!(leaf instanceof MethodInvocationTree) && !(leaf instanceof NewClassTree)) {
                continue;
            }
            int candidateArgIndex = resolveArgumentIndex(current, controller, anchorOffset);
            if (candidateArgIndex < 0) {
                continue;
            }
            List<ExecutableElement> candidates = resolveInvocationOverloads(
                    controller, current, candidateArgIndex);
            if (!candidates.isEmpty() && hasResolvableExpectedType(candidates, candidateArgIndex)) {
                DeclaredType receiver = resolveReceiverDeclaredType(controller, current);
                return new JavaCompletionInvocationUtils.InvocationResolutionRecord(
                        candidates, candidateArgIndex, receiver);
            }
        }
        return resolveConstructorOverloadsFallback(controller, source, anchorOffset);
    }

    private static void addStandaloneNewItems(CompilationController controller, CompletionContext context,
                                              TreePath path, List<CompletionItem> items,
                                              boolean showAllItems, CompletionCancellation cancellation) {
        TypeMirror expected = resolveStandaloneNewExpectedType(path, controller);
        if (expected != null) {
            List<CompletionItem> expectedItems = collectExpectedTypeItems(
                    controller, expected, context.prefix(), cancellation);
            if (!expectedItems.isEmpty()) {
                if (showAllItems) {
                    addGeneralNewContextItems(
                            items, controller, context.prefix(), true, context.documentText(), cancellation);
                    return;
                }
                items.addAll(expectedItems);
                appendGeneralTypesAndPackagesTail(controller, context, items, false, cancellation);
                return;
            }
        }
        addGeneralNewContextItems(
                items, controller, context.prefix(), showAllItems, context.documentText(), cancellation);
    }

    private static TreePath findEnclosingInvocation(TreePath path) {
        for (TreePath current = path; current != null; current = current.getParentPath()) {
            Tree leaf = current.getLeaf();
            if (leaf instanceof MethodInvocationTree || leaf instanceof NewClassTree) {
                return current;
            }
        }
        return null;
    }

    /**
     * Produces the list of expected reference types at the caret. Primitives are dropped
     * (literal-only slots cannot be filled with identifiers) and free type variables are
     * unwrapped to their upper bound.
     */
    private static List<TypeMirror> collectInvocationExpectedTypes(CompilationController controller,
                                                                   TreePath invocationPath,
                                                                   List<ExecutableElement> overloads,
                                                                   int textualArgIndex) {
        DeclaredType receiverType = resolveReceiverDeclaredType(controller, invocationPath);
        List<TypeMirror> expectedTypes = new ArrayList<>();
        for (ExecutableElement overload : overloads) {
            TypeMirror t = resolveSubstitutedParameterType(controller.getTypes(), receiverType, overload, textualArgIndex);
            if (t == null) {
                continue;
            }
            TypeKind tk = t.getKind();
            if (tk == TypeKind.ERROR || tk == TypeKind.NONE || tk.isPrimitive()) {
                continue;
            }
            if (tk == TypeKind.TYPEVAR) {
                t = ((TypeVariable) t).getUpperBound();
                if (t == null || t.getKind() == TypeKind.ERROR) {
                    continue;
                }
            }
            expectedTypes.add(t);
        }
        return expectedTypes;
    }

    /**
     * Appends one {@link JavaCompletionItems#semanticField semanticField} per
     * {@code ENUM_CONSTANT} member of {@code enumElement} whose name matches
     * {@code prefix} (case-insensitive).
     */
    private static void collectEnumConstantItems(CompilationController controller,
                                                 TypeElement enumElement,
                                                 String prefix,
                                                 CompletionCancellation cancellation,
                                                 List<CompletionItem> items) {
        String lowerPrefix = lowerPrefix(prefix);
        for (Element member : controller.getElements().getAllMembers(enumElement)) {
            if (cancellation.isCancelled()) {
                return;
            }
            if (member.getKind() != ElementKind.ENUM_CONSTANT) {
                continue;
            }
            if (prefixMismatch(member.getSimpleName().toString(), lowerPrefix)) {
                continue;
            }
            items.add(semanticField(controller.getElements(), (VariableElement) member, prefix, true));
        }
    }

    /**
     * Returns the resolved {@link TypeMirror} of the selector expression of the
     * nearest enclosing {@link SwitchTree} / {@link SwitchExpressionTree}, or
     * {@code null} when no switch wraps {@code path} or the type cannot be resolved.
     */
    private static TypeMirror resolveSwitchSelectorType(TreePath path, CompilationController controller) {
        for (TreePath current = path; current != null; current = current.getParentPath()) {
            Tree leaf = current.getLeaf();
            ExpressionTree selector = switch (leaf) {
                case SwitchTree switchTree -> switchTree.getExpression();
                case SwitchExpressionTree switchExpr -> switchExpr.getExpression();
                default -> null;
            };
            if (selector != null) {
                return controller.getTrees().getTypeMirror(new TreePath(current, selector));
            }
        }
        return null;
    }

    private static JavaInheritanceClauseUtils.Filter resolveInheritanceFilter(CompletionContext context,
                                                                              JavaInheritanceClauseUtils.ClauseKind clauseKind) {
        return switch (clauseKind) {
            case IMPLEMENTS -> JavaInheritanceClauseUtils.Filter.INTERFACE_ONLY;
            case PERMITS -> JavaInheritanceClauseUtils.Filter.CLASS_OR_INTERFACE;
            case EXTENDS -> "interface".equals(declarationKindBefore(context, "extends"))
                    ? JavaInheritanceClauseUtils.Filter.INTERFACE_ONLY
                    : JavaInheritanceClauseUtils.Filter.CLASS_ONLY;
        };
    }

    private static String clauseKeyword(JavaInheritanceClauseUtils.ClauseKind clauseKind) {
        return switch (clauseKind) {
            case EXTENDS -> "extends";
            case IMPLEMENTS -> "implements";
            case PERMITS -> "permits";
        };
    }

    /**
     * Walks {@code path} outward looking for the nearest enclosing {@link InstanceOfTree}
     * and returns the resolved {@link TypeMirror} of its operand expression, or
     * {@code null} when no {@code instanceof} wraps the caret.
     */
    private static TypeMirror resolveInstanceofOperandType(TreePath path, CompilationController controller) {
        for (TreePath current = path; current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof InstanceOfTree iot) {
                ExpressionTree operand = iot.getExpression();
                return controller.getTrees().getTypeMirror(new TreePath(current, operand));
            }
        }
        return null;
    }

    /**
     * Adds a separator (when {@code items} is non-empty) followed by general types and
     * top-level packages — the standard "fallback tail" common to all argument-list and
     * standalone-new completions.
     *
     * <p>On a regular Ctrl+Space ({@code showAllItems == false}) the type catalog is
     * restricted to {@code java.lang} to keep the popup focused. On a second
     * Ctrl+Space ({@code showAllItems == true}, i.e.
     * {@link CompletionProvider#COMPLETION_ALL_QUERY_TYPE}) the restriction is lifted
     * so the full classpath catalog (filtered by the typed prefix, if any) is offered.</p>
     */
    private static void appendGeneralTypesAndPackagesTail(CompilationController controller,
                                                          CompletionContext context,
                                                          List<CompletionItem> items,
                                                          boolean showAllItems,
                                                          CompletionCancellation cancellation) {
        List<CompletionItem> generalTypes = collectTypeItems(
                controller, context.prefix(), showAllItems, !showAllItems,
                context.documentText(), cancellation);
        List<CompletionItem> generalPackages = collectTopLevelPackageItems(controller, context.prefix(), cancellation);
        if (generalTypes.isEmpty() && generalPackages.isEmpty()) {
            return;
        }
        if (!items.isEmpty()) {
            items.add(separator(context.prefix()));
        }
        items.addAll(generalTypes);
        items.addAll(generalPackages);
    }

    /**
     * Adds a separator (when {@code items} is non-empty) followed by top-level package
     * items. Used by the throws / catch / case-label completions where the
     * {@code java.lang} type catalog would be mostly noise but the user may still want
     * to drill into a package containing a project-local subtype.
     *
     * <p>Top-level packages are enumerated unconditionally — i.e. on the very first
     * Ctrl+Space, with or without a prefix — via
     * {@link JavaPackagePathCollector#collectTopLevelPackageItems}.</p>
     */
    private static void appendPackagesTail(CompilationController controller, CompletionContext context,
                                           List<CompletionItem> items, CompletionCancellation cancellation) {
        List<CompletionItem> generalPackages = collectTopLevelPackageItems(
                controller, context.prefix(), cancellation);
        if (generalPackages.isEmpty()) {
            return;
        }
        if (!items.isEmpty()) {
            items.add(separator(context.prefix()));
        }
        items.addAll(generalPackages);
    }

    /**
     * Appends the {@code null} literal proposal when {@code prefix} matches it
     * case-insensitively (or is blank). Used by case-label completion to compensate
     * for the suppressed lexical contribution.
     */
    private static void appendNullLiteralIfMatching(String prefix, List<CompletionItem> items) {
        if (!prefixMismatch("null", lowerPrefix(prefix))) {
            items.add(JavaCompletionItems.nullLiteral());
        }
    }
}
