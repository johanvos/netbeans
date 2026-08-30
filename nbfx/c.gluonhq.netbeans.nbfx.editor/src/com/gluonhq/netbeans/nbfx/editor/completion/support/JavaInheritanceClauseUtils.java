package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionCancellation;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionTypeKind;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.ElementHandle;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionContextUtils.isDirectlyVisibleType;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.isInternalJdkPackage;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.isInvalidCompletionTypeName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.lowerPrefix;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.packageName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.prefixMismatch;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.putItem;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.simpleName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItems.newType;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItems.sameFileType;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionTypeUtils.extractPackageName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionTypeUtils.parseDeclaredTypeInfos;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionTypeUtils.parseImports;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaPackagePathCollector.SOURCE_AND_DEPS;

/**
 * Collects type proposals for the {@code extends}, {@code implements} and
 * {@code permits} clauses of a type declaration. Filters the project
 * {@link ClassIndex} down to the {@link ElementKind kinds} that are
 * grammatically legal in each clause:
 *
 * <ul>
 *   <li>{@link Filter#CLASS_ONLY}      — class {@code extends |} (and {@code enum} /
 *       {@code record} extends, which is rejected at compile time but still routed
 *       here for completion);</li>
 *   <li>{@link Filter#INTERFACE_ONLY}  — class {@code implements |}, interface
 *       {@code extends |}, interface {@code implements |};</li>
 *   <li>{@link Filter#CLASS_OR_INTERFACE} — sealed type {@code permits |}.</li>
 * </ul>
 *
 * <p>Like the {@code new}-context type catalog, the result set is scoped to the
 * Ctrl+Space mode:</p>
 * <ul>
 *   <li>first Ctrl+Space → {@code java.lang} plus types reachable through explicit
 *       imports, wildcard imports and the current package;</li>
 *   <li>second Ctrl+Space ({@link com.gluonhq.netbeans.nbfx.api.completion.CompletionProvider#COMPLETION_ALL_QUERY_TYPE})
 *       → every type on the classpath matching the prefix and the clause filter.</li>
 * </ul>
 */
public final class JavaInheritanceClauseUtils {

    /** Which inheritance clause the caret sits in. */
    public enum ClauseKind { EXTENDS, IMPLEMENTS, PERMITS }

    enum Filter { CLASS_ONLY, INTERFACE_ONLY, CLASS_OR_INTERFACE }

    private JavaInheritanceClauseUtils() {
    }

    static List<CompletionItem> collectInheritanceTypes(CompilationController controller,
                                                        String prefix, Filter filter,
                                                        ClauseKind clauseKind,
                                                        boolean showAllItems, String sourceText,
                                                        String excludeSimpleName,
                                                        CompletionCancellation cancellation) {
        String lowerPrefix = lowerPrefix(prefix);
        String currentPackage = extractPackageName(sourceText);
        Map<String, CompletionItem> result = new LinkedHashMap<>();

        // Names already listed in the clause being edited — these are excluded from
        // the popup so the user can't propose `permits D, D` or `implements A, A`.
        Set<String> alreadyListed = collectExistingClauseNames(sourceText, excludeSimpleName, clauseKind);

        // PERMITS is special: it constrains the popup to types that are already
        // declared subtypes of the sealed type being edited. Resolve the self type
        // (via Elements first, falling back to the same-file regex parse) and use it
        // to filter both same-file declarations and the ClassIndex sweep below. When
        // the self type can't be resolved at all (very first edit, before any subtype
        // exists) we fall back to the legacy unfiltered behavior so the popup is
        // never empty.
        SubtypeFilter subtypeFilter = (clauseKind == ClauseKind.PERMITS)
                ? buildPermitsSubtypeFilter(controller, sourceText, currentPackage, excludeSimpleName)
                : SubtypeFilter.NONE;

        // 1. java.lang is always offered — enumerated via the PackageElement to avoid
        //    a classpath-wide ClassIndex scan for such a small, well-known package.
        //    For PERMITS, we skip this entirely: java.lang has no useful subtypes of a
        //    project-local sealed type.
        if (clauseKind != ClauseKind.PERMITS || !subtypeFilter.isActive()) {
            collectJavaLangInheritanceTypes(controller, prefix, lowerPrefix, filter, alreadyListed,
                    cancellation, result);
            if (cancellation.isCancelled()) {
                return List.of();
            }
        }

        // 2. Top-level types declared in the active source file are always offered —
        //    they may not be on the classpath yet (e.g. `interface AA {} class B implements AA`)
        //    so the ClassIndex pass below cannot see them. The type currently being
        //    declared is excluded (a class cannot extend / implement itself). For PERMITS
        //    we additionally restrict to declarations whose extends/implements clause
        //    references the self type.
        collectSameFileInheritanceTypes(prefix, lowerPrefix, filter, sourceText, excludeSimpleName,
                subtypeFilter, alreadyListed, result);

        Map<String, String> imports = sourceText == null ? Map.of() : parseImports(sourceText);
        Set<String> wildcardImportPackages = new HashSet<>();
        for (String key : imports.keySet()) {
            if (key.startsWith("*:")) {
                wildcardImportPackages.add(key.substring(2));
            }
        }

        // 3. Explicit imports are always offered when they pass the clause filter, even when
        //    the ClassIndex sweep below misses them — empty/blank prefixes are an edge case
        //    where `ClassIndex.getDeclaredTypes("", CASE_INSENSITIVE_PREFIX, …)` can come
        //    back empty depending on the project setup, but a freshly imported type like
        //    `import javafx.application.Application;` must still surface for
        //    `class HelloApplication extends |`.
        collectImportedInheritanceTypes(controller, prefix, lowerPrefix, filter, imports,
                subtypeFilter, alreadyListed, result, cancellation);
        if (cancellation.isCancelled()) {
            return List.of();
        }

        Set<ElementHandle<TypeElement>> handles = controller.getClasspathInfo().getClassIndex()
                .getDeclaredTypes(prefix == null ? "" : prefix,
                        ClassIndex.NameKind.CASE_INSENSITIVE_PREFIX, SOURCE_AND_DEPS);
        if (handles == null) {
            return List.copyOf(result.values());
        }
        for (ElementHandle<TypeElement> handle : handles) {
            if (cancellation.isCancelled()) {
                return List.of();
            }
            // ElementHandle carries the kind in metadata — no resolve required.
            if (!matchesFilter(handle.getKind(), filter)) {
                continue;
            }
            String fqcn = handle.getQualifiedName();
            if (fqcn.isBlank() || fqcn.contains("$")) {
                continue;
            }
            String pkg = packageName(fqcn);
            if (pkg.isBlank() || isInternalJdkPackage(pkg) || "java.lang".equals(pkg)) {
                // java.lang already handled above
                continue;
            }
            // PERMITS-only: restrict the classpath sweep to actual subtypes of the
            // sealed type being edited.
            if (subtypeFilter.isActive()) {
                TypeElement candidate = controller.getElements().getTypeElement(fqcn);
                if (candidate == null || !subtypeFilter.acceptsClasspath(controller, candidate)) {
                    continue;
                }
            } else if (!showAllItems
                    && !isDirectlyVisibleType(fqcn, pkg, imports, wildcardImportPackages, currentPackage)) {
                continue;
            }
            String simpleName = simpleName(fqcn);
            if (simpleName.isBlank() || isInvalidCompletionTypeName(simpleName)
                    || prefixMismatch(simpleName, lowerPrefix)
                    || alreadyListed.contains(simpleName)) {
                continue;
            }
            // Skip the type currently being declared (when it's already on the classpath
            // from a previous compile of the same file).
            if (excludeSimpleName != null && excludeSimpleName.equals(simpleName)
                    && pkg.equals(currentPackage == null ? "" : currentPackage)) {
                continue;
            }
            putItem(result, newType(fqcn, prefix, pkg, showAllItems));
        }
        return List.copyOf(result.values());
    }

    /**
     * Returns the simple names already present in the clause currently being edited so
     * the popup can skip duplicates. The lookup keys on {@code excludeSimpleName} (the
     * declaration being typed) inside the same-file scan.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code sealed interface C permits D, |} → {@code {"D"}} (so the popup
     *       proposes {@code E} but not {@code D}).</li>
     *   <li>{@code class Foo implements A, B, |} → {@code {"A", "B"}}.</li>
     *   <li>{@code interface I extends X, |} → {@code {"X"}}.</li>
     * </ul>
     */
    private static Set<String> collectExistingClauseNames(String sourceText, String excludeSimpleName,
                                                          ClauseKind clauseKind) {
        if (sourceText == null || sourceText.isBlank() || excludeSimpleName == null) {
            return Set.of();
        }
        JavaCompletionTypeUtils.DeclaredTypeInfo info =
                JavaCompletionTypeUtils.parseDeclaredTypeInfos(sourceText).get(excludeSimpleName);
        if (info == null) {
            return Set.of();
        }
        List<String> clauseNames = switch (clauseKind) {
            case EXTENDS -> info.extendsNames();
            case IMPLEMENTS -> info.implementsNames();
            case PERMITS -> info.permitsNames();
        };
        return clauseNames.isEmpty() ? Set.of() : Set.copyOf(clauseNames);
    }

    /**
     * Adds proposals for every explicit single-type {@code import} in the file that matches
     * the clause's {@link Filter} and the user's {@code prefix}. Resolves each FQN through
     * {@link javax.lang.model.util.Elements#getTypeElement} so the {@link ElementKind} can
     * be inspected without a separate {@link ClassIndex} round-trip. This is a safety net:
     * the {@link ClassIndex} sweep further down handles the broader catalog, but it can
     * miss imported types when the user asks for completion with a blank or very short
     * prefix.
     *
     * <p>For {@code permits} an additional {@link SubtypeFilter#acceptsClasspath subtype
     * check} is applied: an imported type that does not extend / implement the sealed type
     * being edited is dropped.</p>
     */
    private static void collectImportedInheritanceTypes(CompilationController controller, String prefix,
                                                        String lowerPrefix, Filter filter,
                                                        Map<String, String> imports,
                                                        SubtypeFilter subtypeFilter,
                                                        Set<String> alreadyListed,
                                                        Map<String, CompletionItem> result,
                                                        CompletionCancellation cancellation) {
        for (Map.Entry<String, String> entry : imports.entrySet()) {
            if (cancellation.isCancelled()) {
                return;
            }
            String simpleName = entry.getKey();
            // Wildcard imports are encoded as "*:pkg" — they don't name a single type.
            if (simpleName.startsWith("*:")) {
                continue;
            }
            if (simpleName.isBlank() || isInvalidCompletionTypeName(simpleName)
                    || prefixMismatch(simpleName, lowerPrefix)
                    || alreadyListed.contains(simpleName)) {
                continue;
            }
            String fqcn = entry.getValue();
            if (fqcn == null || fqcn.isBlank() || fqcn.contains("$")) {
                continue;
            }
            TypeElement typeElement = controller.getElements().getTypeElement(fqcn);
            if (typeElement == null || !matchesFilter(typeElement.getKind(), filter)) {
                continue;
            }
            if (subtypeFilter.isActive() && !subtypeFilter.acceptsClasspath(controller, typeElement)) {
                continue;
            }
            String pkg = packageName(fqcn);
            // `java.lang` was handled by collectJavaLangInheritanceTypes; don't double-list.
            if ("java.lang".equals(pkg)) {
                continue;
            }
            putItem(result, newType(fqcn, prefix, pkg, false));
        }
    }

    /**
     * Enumerates the {@code java.lang} package's top-level types via the
     * {@link PackageElement}, keeping only those matching the clause's {@link Filter}
     * and the user's {@code prefix}.
     */
    private static void collectJavaLangInheritanceTypes(CompilationController controller, String prefix,
                                                        String lowerPrefix, Filter filter,
                                                        Set<String> alreadyListed,
                                                        CompletionCancellation cancellation,
                                                        Map<String, CompletionItem> result) {
        PackageElement javaLang = controller.getElements().getPackageElement("java.lang");
        if (javaLang == null) {
            return;
        }
        for (Element child : javaLang.getEnclosedElements()) {
            if (cancellation.isCancelled()) {
                return;
            }
            if (!(child instanceof TypeElement typeChild) || !matchesFilter(typeChild.getKind(), filter)) {
                continue;
            }
            String simpleName = typeChild.getSimpleName().toString();
            if (simpleName.isBlank() || isInvalidCompletionTypeName(simpleName)
                    || prefixMismatch(simpleName, lowerPrefix)
                    || alreadyListed.contains(simpleName)) {
                continue;
            }
            String fqcn = typeChild.getQualifiedName().toString();
            if (fqcn.contains("$")) {
                continue;
            }
            putItem(result, newType(fqcn, prefix, "java.lang", false));
        }
    }

    /**
     * Adds proposals for every top-level type declared in {@code sourceText} that
     * matches the clause's {@link Filter} and the user's {@code prefix}. Lets
     * {@code interface AA {} class B implements AA|} surface {@code AA} even though
     * it hasn't been compiled into the {@link ClassIndex} yet. The type currently
     * being declared ({@code excludeSimpleName}) is filtered out: a type cannot
     * legally appear as its own supertype.
     *
     * <p>For {@code permits} the {@link SubtypeFilter} additionally rejects any
     * declaration whose {@code extends} / {@code implements} clause doesn't list
     * the sealed type being edited — this is how {@code sealed interface C permits |}
     * surfaces the records {@code D}, {@code E} declared later in the same file
     * even before they hit the {@link ClassIndex}.</p>
     */
    private static void collectSameFileInheritanceTypes(String prefix, String lowerPrefix, Filter filter,
                                                        String sourceText, String excludeSimpleName,
                                                        SubtypeFilter subtypeFilter,
                                                        Set<String> alreadyListed,
                                                        Map<String, CompletionItem> result) {
        if (sourceText == null || sourceText.isBlank()) {
            return;
        }
        for (Map.Entry<String, JavaCompletionTypeUtils.DeclaredTypeInfo> entry
                : parseDeclaredTypeInfos(sourceText).entrySet()) {
            String simpleName = entry.getKey();
            JavaCompletionTypeUtils.DeclaredTypeInfo info = entry.getValue();
            CompletionTypeKind kind = info.kind();
            if (simpleName.isBlank() || isInvalidCompletionTypeName(simpleName)
                    || prefixMismatch(simpleName, lowerPrefix) || !matchesFilter(kind, filter)
                    || simpleName.equals(excludeSimpleName)
                    || alreadyListed.contains(simpleName)) {
                continue;
            }
            if (subtypeFilter.isActive() && !subtypeFilter.acceptsSameFile(info)) {
                continue;
            }
            putItem(result, sameFileType(simpleName, prefix, kind));
        }
    }

    /**
     * Resolves the sealed type being edited and produces a {@link SubtypeFilter} that
     * accepts only types that already extend / implement it. Tries the {@code Elements}
     * route first (so the previously-compiled version of the sealed type can drive a
     * full {@link Types#isSubtype} check against the classpath sweep), then falls back
     * to the same-file regex parse so freshly added declarations still survive even
     * when the sealed type hasn't been compiled yet.
     *
     * <p>Returns {@link SubtypeFilter#NONE} (i.e. unfiltered) when both routes fail —
     * keeps the popup useful while the user is still typing the very first iteration
     * of the sealed declaration.</p>
     */
    private static SubtypeFilter buildPermitsSubtypeFilter(CompilationController controller,
                                                           String sourceText, String currentPackage,
                                                           String excludeSimpleName) {
        if (excludeSimpleName == null || excludeSimpleName.isBlank()) {
            return SubtypeFilter.NONE;
        }
        String selfFqcn = currentPackage == null || currentPackage.isBlank()
                ? excludeSimpleName : currentPackage + "." + excludeSimpleName;
        TypeElement selfType = controller.getElements().getTypeElement(selfFqcn);
        if (selfType != null) {
            return new SubtypeFilter(excludeSimpleName, selfType);
        }
        boolean hasSameFileSubtype = parseDeclaredTypeInfos(sourceText).values().stream()
                .anyMatch(info -> info.extendsNames().contains(excludeSimpleName)
                        || info.implementsNames().contains(excludeSimpleName));
        return hasSameFileSubtype ? new SubtypeFilter(excludeSimpleName, null) : SubtypeFilter.NONE;
    }

    /**
         * Filters candidate types down to subtypes of a target type. Carries two paths:
         * <ul>
         *   <li><b>Classpath</b> — uses {@link Types#isSubtype} when the target's
         *       {@link TypeElement} has been resolved.</li>
         *   <li><b>Same-file</b> — checks whether a {@link JavaCompletionTypeUtils.DeclaredTypeInfo}
         *       lists the target's simple name in its {@code extends} / {@code implements}
         *       clause; used when neither side has been compiled yet.</li>
         * </ul>
         * <p>An <i>inactive</i> filter (no target name <b>and</b> no resolved element)
         * accepts everything — used as a graceful fallback so the popup is never empty.</p>
         */
        private record SubtypeFilter(String targetSimpleName, TypeElement targetType) {

            static final SubtypeFilter NONE = new SubtypeFilter(null, null);

        boolean isActive() {
                return targetSimpleName != null || targetType != null;
            }

            boolean acceptsSameFile(JavaCompletionTypeUtils.DeclaredTypeInfo info) {
                if (targetSimpleName == null) {
                    return true;
                }
                return info.extendsNames().contains(targetSimpleName)
                        || info.implementsNames().contains(targetSimpleName);
            }

            boolean acceptsClasspath(CompilationController controller, TypeElement candidate) {
                if (targetType == null) {
                    // No resolved target — defer to the same-file regex by skipping the
                    // classpath candidate (it would have been surfaced by the same-file
                    // pass too if it were a legitimate subtype declared in this file).
                    return false;
                }
                if (candidate == targetType) {
                    return false;
                }
                Types types = controller.getTypes();
                TypeMirror candidateType = candidate.asType();
                TypeMirror selfType = targetType.asType();
                if (candidateType == null || selfType == null) {
                    return false;
                }
                try {
                    return types.isSubtype(types.erasure(candidateType), types.erasure(selfType));
                } catch (RuntimeException ignore) {
                    // Defensive: the NetBeans javac wrapper can throw on partially-resolved
                    // types when the user is mid-edit. Reject rather than spam the popup.
                    return false;
                }
            }
        }

    private static boolean matchesFilter(ElementKind kind, Filter filter) {
        return switch (filter) {
            case CLASS_ONLY -> kind == ElementKind.CLASS;
            case INTERFACE_ONLY -> kind == ElementKind.INTERFACE;
            // Records and enums are valid `permits` targets: a record is implicitly final
            // and an enum is implicitly final, but both can implement a sealed interface.
            case CLASS_OR_INTERFACE -> kind == ElementKind.CLASS || kind == ElementKind.INTERFACE
                    || kind == ElementKind.RECORD || kind == ElementKind.ENUM;
        };
    }

    private static boolean matchesFilter(CompletionTypeKind kind, Filter filter) {
        return switch (filter) {
            case CLASS_ONLY -> kind == CompletionTypeKind.CLASS;
            case INTERFACE_ONLY -> kind == CompletionTypeKind.INTERFACE;
            case CLASS_OR_INTERFACE -> kind == CompletionTypeKind.CLASS || kind == CompletionTypeKind.INTERFACE
                    || kind == CompletionTypeKind.RECORD || kind == CompletionTypeKind.ENUM;
        };
    }
}


