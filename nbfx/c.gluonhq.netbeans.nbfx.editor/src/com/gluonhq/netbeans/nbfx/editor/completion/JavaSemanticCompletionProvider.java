package com.gluonhq.netbeans.nbfx.editor.completion;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionCancellation;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionContext;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionProvider;
import com.gluonhq.netbeans.nbfx.editor.completion.support.JavaAnnotationCompletionUtils;
import com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionContextUtils;
import com.gluonhq.netbeans.nbfx.editor.completion.support.JavaImportContext;
import com.gluonhq.netbeans.nbfx.editor.completion.support.JavaInheritanceClauseUtils;
import com.gluonhq.netbeans.nbfx.editor.completion.support.JavaModuleInfoCompletionQueries;
import com.gluonhq.netbeans.nbfx.editor.completion.support.JavaModuleInfoContextUtils;
import com.gluonhq.netbeans.nbfx.editor.completion.support.JavaSemanticCompletionQueries;
import org.openide.util.lookup.ServiceProvider;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Entry point for Java semantic completion backed by
 * {@link org.netbeans.api.java.source.JavaSource JavaSource} analysis. The provider
 * inspects the caret and dispatches the relevant work to a focused query method on
 * {@link JavaSemanticCompletionQueries}.
 *
 * <h2>Recognized scenarios</h2>
 *
 * <p><b>Module descriptor ({@code module-info.java}) directives</b> — routed to
 * {@link JavaModuleInfoCompletionQueries#query JavaModuleInfoCompletionQueries.query}
 * (checked first, before any regular member/identifier detector, since module descriptors have their own grammar):</p>
 * <ul>
 *   <li>{@code op|} / {@code mod|} on the header line — the {@code open} / {@code module} lead-in
 *       keywords.</li>
 *   <li>{@code requi|} or a blank statement inside the body — the directive keywords
 *       ({@code requires}, {@code exports}, {@code opens}, {@code uses}, {@code provides}).</li>
 *   <li>{@code requires |} — the {@code static} / {@code transitive} modifiers (when not already
 *       present) followed by every observable module name ({@code java.base}, {@code jdk.*},
 *       {@code javafx.controls}, project modules, …).</li>
 *   <li>{@code exports |} / {@code opens |} — the project's own source packages, offered as a
 *       drill-down package path ({@code com} &rarr; {@code foo} &rarr; …).</li>
 *   <li>{@code exports pkg to |} / {@code opens pkg to |} — observable module names.</li>
 *   <li>{@code uses |}, {@code provides |} and {@code provides X with |} — service / implementation
 *       types: {@code java.lang} types plus top-level packages on a plain {@code Ctrl+Space}, the
 *       whole classpath on a second {@code Ctrl+Space}, and type drill-down under a package
 *       qualifier.</li>
 * </ul>
 *
 * <p><b>Qualified import completion</b> — routed to
 * {@link JavaSemanticCompletionQueries#queryPackageWithJavaSource queryPackageWithJavaSource}:</p>
 * <ul>
 *   <li>{@code import javafx.scene.|} — qualified package path</li>
 *   <li>{@code import static javafx.animation.Animation.|} — qualified static import</li>
 * </ul>
 *
 * <p><b>Top-level import identifier</b> — routed to
 * {@link JavaSemanticCompletionQueries#queryImportTopLevelPackageWithJavaSource queryImportTopLevelPackageWithJavaSource}:</p>
 * <ul>
 *   <li>{@code import jav|} — top-level import (including the bare {@code import |} case)</li>
 *   <li>{@code import static jav|} — top-level static import (including the bare {@code import static |} case)</li>
 * </ul>
 *
 * <p><b>Annotation type position</b> — routed to
 * {@link JavaSemanticCompletionQueries#queryAnnotationTypeWithJavaSource queryAnnotationTypeWithJavaSource}:</p>
 * <ul>
 *   <li>{@code @|} — blank-prefix annotation completion (lists every visible annotation type
 *       including {@code java.lang.*})</li>
 *   <li>{@code @Over|} — prefix-filtered annotation completion ({@code @Override}, …)</li>
 * </ul>
 *
 * <p><b>Annotation argument list</b> — routed to
 * {@link JavaSemanticCompletionQueries#queryAnnotationArgumentWithJavaSource queryAnnotationArgumentWithJavaSource}:</p>
 * <ul>
 *   <li>{@code @SuppressWarnings(|)} — blank-prefix annotation-member completion
 *       ({@code value} inserted as {@code value = })</li>
 *   <li>{@code @SuppressWarnings(va|)} — prefix-filtered annotation-member completion</li>
 * </ul>
 *
 * <p><b>Annotation member value</b> — routed to
 * {@link JavaSemanticCompletionQueries#queryAnnotationValueWithJavaSource queryAnnotationValueWithJavaSource}:</p>
 * <ul>
 *   <li>{@code @Retention(value = |)} / {@code @Retention(value = Ret|)} — qualified
 *       enum constants of the member's declared type
 *       ({@code RetentionPolicy.SOURCE}, {@code RetentionPolicy.CLASS}, …).</li>
 *   <li>{@code @Target(value = {ElementType.METHOD, |})} — array-of-enum form;
 *       remaining {@code ElementType} constants are offered inside the array literal.</li>
 * </ul>
 *
 * <p><b>Qualified package after {@code new}</b> — routed to
 * {@link JavaSemanticCompletionQueries#queryPackageWithJavaSource queryPackageWithJavaSource}.
 * <ul>
 *    <li>{@code new javafx.scene.|}</li>
 * </ul>
 *
 * <p><b>Member access</b> — routed to
 * {@link JavaSemanticCompletionQueries#queryMemberWithJavaSource queryMemberWithJavaSource}:</p>
 * <ul>
 *   <li>{@code scene.|} — blank-prefix member completion (lists every member of {@code Scene})</li>
 *   <li>{@code scene.setR|} — prefix-filtered member completion ({@code setRoot}, …)</li>
 *   <li>{@code Math.|} — static member completion (the qualifier is a type, only {@code static} members are offered)</li>
 * </ul>
 *
 * <p><b>{@code new}-keyword type position</b> — routed to
 * {@link JavaSemanticCompletionQueries#queryNewWithJavaSource queryNewWithJavaSource}.</p>
 * <ul>
 *     <li>{@code box.getChildren().add(new |)} — blank-prefix new type and subtype completion
 *     ({@code Node}, {@code Label})</li>
 *     <li>{@code Scene scene = new Scen|} — new type completion ({@code Scene})</li>
 * </ul>
 *
 * <p><b>{@code throws} / {@code catch (…)} clauses</b> — routed to
 * {@link JavaSemanticCompletionQueries#queryThrowableTypeWithJavaSource queryThrowableTypeWithJavaSource}:</p>
 * <ul>
 *   <li>{@code void run() throws |} — blank-prefix exception completion ({@code Throwable}
 *       and every assignable subtype on the classpath)</li>
 *   <li>{@code void run() throws IOException, |} — exception completion past a comma in
 *       a {@code throws} clause</li>
 *   <li>{@code catch (IOException | Sql|)} — prefix-filtered exception completion inside
 *       a multi-catch parameter list</li>
 * </ul>
 *
 * <p><b>{@code extends} / {@code implements} / {@code permits} clauses</b> — routed to
 * {@link JavaSemanticCompletionQueries#queryInheritanceClauseTypesWithJavaSource queryInheritanceClauseTypesWithJavaSource}:</p>
 * <ul>
 *   <li>{@code class Foo extends |} — classes only;
 *       {@code interface I extends |} — interfaces only;</li>
 *   <li>{@code class Foo implements A, |} — interfaces only;</li>
 *   <li>{@code sealed class Foo permits |} — classes and interfaces.</li>
 * </ul>
 *
 * <p><b>{@code switch (…) { case | }} label</b> — routed to
 * {@link JavaSemanticCompletionQueries#queryCaseLabelWithJavaSource queryCaseLabelWithJavaSource}:</p>
 * <ul>
 *   <li>{@code case |}, {@code case RE|}, {@code case RED, |} — enum-constant proposals
 *       on enum selectors; selector type + assignable subtypes on reference selectors
 *       (Java 21 type patterns: {@code case Scene s -&gt; …}).</li>
 * </ul>
 *
 * <p><b>{@code instanceof}-target type</b> — routed to
 * {@link JavaSemanticCompletionQueries#queryInstanceofTypeWithJavaSource queryInstanceofTypeWithJavaSource}:</p>
 * <ul>
 *   <li>{@code obj instanceof |} or {@code if (obj instanceof Sc|)} — operand type plus
 *       assignable subtypes (the types for which the {@code instanceof} check carries
 *       useful information).</li>
 * </ul>
 *
 * <p><b>Cast-target type</b> — routed to
 * {@link JavaSemanticCompletionQueries#queryCastTypeWithJavaSource queryCastTypeWithJavaSource}:</p>
 * <ul>
 *   <li>{@code Parent p = (|) obj} — expected type (here {@code Parent}) plus assignable subtypes;</li>
 *   <li>{@code ((|) p).method()} — when no LHS / return / arg target exists, the cast's operand
 *       (here {@code p}) is used as the expected type so its subtypes are surfaced too.</li>
 * </ul>
 *
 * <p><b>Method/constructor argument list</b> — routed to
 * {@link JavaSemanticCompletionQueries#queryInvocationArgumentWithJavaSource queryInvocationArgumentWithJavaSource}:</p>
 * <ul>
 *   <li>{@code box.getChildren().add(|)} — blank-prefix argument completion</li>
 *   <li>{@code box.getChildren().add(lab|)} — prefix-filtered argument completion</li>
 *   <li>{@code box.getChildren().add(1, lab|)} — overload resolution, prefix-filtered argument completion</li>
 * </ul>
 *
 * <p><b>Plain identifier at expression position</b> — routed to
 * {@link JavaSemanticCompletionQueries#queryIdentifierWithJavaSource queryIdentifierWithJavaSource}
 * as the catch-all when no other semantic context matched:</p>
 * <ul>
 *   <li>{@code sce|} — in-scope locals, parameters, fields and methods (e.g. the
 *       local {@code scene}), plus visible types ({@code Scene} from an explicit
 *       import, {@code java.lang.*}, …)</li>
 *   <li>{@code Publi|} / {@code Clas|} — visible {@code Public*} / {@code Class*}
 *       types and {@code public} / {@code class} lexical proposals</li>
 *   <li>{@code publi|} / {@code clas|} — only {@code public} / {@code class} lexical proposals</li>
 *   <li>blank prefix — only lexical results</li>
 * </ul>
 *
 * <p>Any other caret position currently yields an empty list, leaving the popup to be
 * populated by the lexical provider that runs in parallel.</p>
 *
 * @see JavaSemanticCompletionQueries
 * @see JavaImportContext
 */
@ServiceProvider(service = CompletionProvider.class)
public final class JavaSemanticCompletionProvider implements CompletionProvider {

    /** Semantic completion is only meaningful for Java source files. */
    @Override
    public boolean supports(CompletionContext context) {
        return "java".equalsIgnoreCase(context.fileObject().getExt());
    }

    /**
     * Dispatches the request on a worker thread. The caret position is classified by
     * inspecting the context (imports first, then member access, then invocation
     * argument list); any position that doesn't match a more specific shape falls
     * through to the plain-identifier query, which handles in-scope identifiers and
     * the type catalog. All {@link org.netbeans.api.java.source.JavaSource JavaSource}
     * work is isolated inside {@link JavaSemanticCompletionQueries}.
     */
    @Override
    public CompletableFuture<List<CompletionItem>> query(CompletionContext context, CompletionCancellation cancellation) {
        return CompletableFuture.supplyAsync(() -> {
            if (cancellation.isCancelled()) {
                return List.of();
            }
            if (JavaModuleInfoContextUtils.isModuleInfoFile(context)) {
                // Module descriptors have their own directive grammar
                return JavaModuleInfoCompletionQueries.query(context, cancellation);
            }
            JavaImportContext importContext = JavaImportContext.from(context);
            if (importContext.isQualified() || JavaCompletionContextUtils.isNewPackageCompletionContext(context)) {
                return JavaSemanticCompletionQueries.queryPackageWithJavaSource(context, cancellation);
            }
            if (importContext.isTopLevel()) {
                return JavaSemanticCompletionQueries.queryImportTopLevelPackageWithJavaSource(context, cancellation);
            }
            if (JavaAnnotationCompletionUtils.isAnnotationValueContext(context)) {
                return JavaSemanticCompletionQueries.queryAnnotationValueWithJavaSource(context, cancellation);
            }
            if (JavaAnnotationCompletionUtils.isAnnotationArgumentContext(context)) {
                return JavaSemanticCompletionQueries.queryAnnotationArgumentWithJavaSource(context, cancellation);
            }
            if (JavaAnnotationCompletionUtils.isAnnotationTypeContext(context)) {
                return JavaSemanticCompletionQueries.queryAnnotationTypeWithJavaSource(context, cancellation);
            }
            if (JavaCompletionContextUtils.isMemberCompletionContext(context)) {
                return JavaSemanticCompletionQueries.queryMemberWithJavaSource(context, cancellation);
            }
            if (JavaCompletionContextUtils.isNewTypeCompletionContext(context)) {
                return JavaSemanticCompletionQueries.queryNewWithJavaSource(context, cancellation);
            }
            if (JavaCompletionContextUtils.isCatchClauseContext(context)
                    || JavaCompletionContextUtils.isThrowsClauseContext(context)) {
                return JavaSemanticCompletionQueries.queryThrowableTypeWithJavaSource(context, cancellation);
            }
            if (JavaCompletionContextUtils.isCaseLabelContext(context)) {
                return JavaSemanticCompletionQueries.queryCaseLabelWithJavaSource(context, cancellation);
            }
            if (JavaCompletionContextUtils.isExtendsClauseContext(context)) {
                return JavaSemanticCompletionQueries.queryInheritanceClauseTypesWithJavaSource(
                        context, JavaInheritanceClauseUtils.ClauseKind.EXTENDS, cancellation);
            }
            if (JavaCompletionContextUtils.isImplementsClauseContext(context)) {
                return JavaSemanticCompletionQueries.queryInheritanceClauseTypesWithJavaSource(
                        context, JavaInheritanceClauseUtils.ClauseKind.IMPLEMENTS, cancellation);
            }
            if (JavaCompletionContextUtils.isPermitsClauseContext(context)) {
                return JavaSemanticCompletionQueries.queryInheritanceClauseTypesWithJavaSource(
                        context, JavaInheritanceClauseUtils.ClauseKind.PERMITS, cancellation);
            }
            if (JavaCompletionContextUtils.isInstanceofTypeContext(context)) {
                return JavaSemanticCompletionQueries.queryInstanceofTypeWithJavaSource(context, cancellation);
            }
            if (JavaCompletionContextUtils.isCastTypeContext(context)) {
                return JavaSemanticCompletionQueries.queryCastTypeWithJavaSource(context, cancellation);
            }
            if (JavaCompletionContextUtils.isInvocationArgumentContext(context)) {
                return JavaSemanticCompletionQueries.queryInvocationArgumentWithJavaSource(context, cancellation);
            }
            return JavaSemanticCompletionQueries.queryIdentifierWithJavaSource(context, cancellation);
        });
    }

}
