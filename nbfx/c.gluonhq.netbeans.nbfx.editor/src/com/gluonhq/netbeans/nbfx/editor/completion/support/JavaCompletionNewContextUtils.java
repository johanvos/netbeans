package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionCancellation;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.ElementHandle;

import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
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
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaPackagePathCollector.SOURCE_AND_DEPS;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.simpleName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.topLevelPackage;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItems.newType;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionTypeUtils.extractPackageName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionTypeUtils.parseImports;

/**
 * Type and package proposals for the "general catalog" fallback shared by the
 * {@code new}-completion and invocation-argument completion flows.
 *
 * <p>To avoid scanning every declared type on the classpath on each keystroke, the
 * collectors use the narrowest {@link ClassIndex} API available for each task:</p>
 * <ul>
 *   <li>Types: {@link ClassIndex#getDeclaredTypes} with
 *       {@link ClassIndex.NameKind#CASE_INSENSITIVE_PREFIX} so the index pre-filters
 *       by the typed prefix; {@code java.lang.*} is enumerated directly through
 *       {@link javax.lang.model.util.Elements#getPackageElement}.</li>
 *   <li>Packages: {@link ClassIndex#getPackageNames} with {@code directOnly = true}
 *       so the index returns only top-level package names instead of forcing a per-type
 *       iteration that derives the package from each FQCN.</li>
 * </ul>
 */
final class JavaCompletionNewContextUtils {

    private record NewContextItemsRecord(List<CompletionItem> types, List<CompletionItem> packages) {

        static NewContextItemsRecord of() {
            return new NewContextItemsRecord(List.of(), List.of());
        }
        static NewContextItemsRecord of(Map<String, CompletionItem> types) {
            return new NewContextItemsRecord(types.values().stream().toList(), List.of());
        }
    }

    private JavaCompletionNewContextUtils() {
    }

    // Public Entry Points

    /**
     * Collects type proposals visible from the current compilation unit, filtered by
     * {@code prefix}. When {@code javaLangOnly} is set only {@code java.lang} types are
     * returned; otherwise the result also includes explicit imports, wildcard imports
     * and same-package types parsed from {@code sourceText}.
     */
    static List<CompletionItem> collectTypeItems(CompilationController controller, String prefix,
                                                 boolean showAllItems, boolean javaLangOnly,
                                                 String sourceText, CompletionCancellation cancellation) {
        return collectItems(controller, prefix, showAllItems, javaLangOnly, sourceText, cancellation).types();
    }

    /** Collects top-level package proposals matching {@code prefix}. */
    static List<CompletionItem> collectPackageItems(CompilationController controller, String prefix,
                                                    boolean showAllItems, CompletionCancellation cancellation) {
        return collectItems(controller, prefix, showAllItems, false, null, cancellation).packages();
    }

    private static NewContextItemsRecord collectItems(CompilationController controller, String prefix,
                                                      boolean showAllItems, boolean javaLangOnly,
                                                      String sourceText, CompletionCancellation cancellation) {
        String lowerPrefix = lowerPrefix(prefix);
        Map<String, CompletionItem> types = new LinkedHashMap<>();
        Map<String, CompletionItem> packages = new LinkedHashMap<>();

        // Always include matching java.lang types — enumerated directly through the
        // PackageElement to skip a full-classpath scan.
        collectJavaLangTypes(controller, prefix, lowerPrefix, showAllItems, cancellation, types);
        if (cancellation.isCancelled()) {
            return NewContextItemsRecord.of();
        }

        // Non-java.lang types require a directly-visible import (or showAllItems).
        if (javaLangOnly || (!showAllItems && lowerPrefix.isBlank())) {
            return NewContextItemsRecord.of(types);
        }

        Map<String, String> imports = sourceText == null ? Map.of() : parseImports(sourceText);
        Set<String> wildcardImportPackages = new HashSet<>();
        for (String key : imports.keySet()) {
            if (key.startsWith("*:")) {
                wildcardImportPackages.add(key.substring(2));
            }
        }
        String currentPackage = extractPackageName(sourceText);

        // Index pre-filter by simple-name prefix.
        // When the prefix is blank, enumerate everything, the per-handle filters keep the result set small.
        ClassIndex classIndex = controller.getClasspathInfo().getClassIndex();
        Set<ElementHandle<TypeElement>> handles = classIndex.getDeclaredTypes(
                prefix == null ? "" : prefix, ClassIndex.NameKind.CASE_INSENSITIVE_PREFIX, SOURCE_AND_DEPS);
        if (handles == null) {
            return NewContextItemsRecord.of(types);
        }
        for (ElementHandle<TypeElement> handle : handles) {
            if (cancellation.isCancelled()) {
                return NewContextItemsRecord.of();
            }
            String fqcn = handle.getQualifiedName();
            if (fqcn.isBlank() || fqcn.contains("$")) {
                continue;
            }
            String packageName = packageName(fqcn);
            if (packageName.isBlank() || isInternalJdkPackage(packageName) || "java.lang".equals(packageName)) {
                // java.lang already handled above
                continue;
            }
            String topLevel = topLevelPackage(packageName);
            if (!topLevel.isBlank() && !prefixMismatch(topLevel, lowerPrefix)) {
                putItem(packages, JavaCompletionItems.packageItem(topLevel, prefix));
            }
            if (!showAllItems &&
                    !isDirectlyVisibleType(fqcn, packageName, imports, wildcardImportPackages, currentPackage)) {
                continue;
            }
            String simpleName = simpleName(fqcn);
            if (simpleName.isBlank() || isInvalidCompletionTypeName(simpleName) ||
                    prefixMismatch(simpleName, lowerPrefix)) {
                continue;
            }
            Class<?> runtimeType = JavaCompletionTypeUtils.tryLoad(fqcn);
            if (runtimeType != null && !isAllowedType(runtimeType)) {
                continue;
            }
            putItem(types, newType(fqcn, prefix, packageName, showAllItems));
        }
        return new NewContextItemsRecord(types.values().stream().toList(), packages.values().stream().toList());
    }

    // Helpers

    /**
     * Enumerates the {@code java.lang} package's top-level types via the
     * {@link PackageElement}. Avoids triggering a classpath-wide
     * {@link ClassIndex#getDeclaredTypes} scan just to reach a small, well-known
     * package.
     */
    private static void collectJavaLangTypes(CompilationController controller, String prefix,
                                             String lowerPrefix, boolean showAllItems,
                                             CompletionCancellation cancellation,
                                             Map<String, CompletionItem> types) {
        PackageElement javaLang = controller.getElements().getPackageElement("java.lang");
        if (javaLang == null) {
            return;
        }
        for (Element child : javaLang.getEnclosedElements()) {
            if (cancellation.isCancelled()) {
                return;
            }
            if (!(child instanceof TypeElement typeChild)) {
                continue;
            }
            String simpleName = typeChild.getSimpleName().toString();
            if (simpleName.isBlank() || isInvalidCompletionTypeName(simpleName)
                    || prefixMismatch(simpleName, lowerPrefix)) {
                continue;
            }
            String fqcn = typeChild.getQualifiedName().toString();
            if (fqcn.contains("$")) {
                continue;
            }
            Class<?> runtimeType = JavaCompletionTypeUtils.tryLoad(fqcn);
            if (runtimeType != null && !isAllowedType(runtimeType)) {
                continue;
            }
            putItem(types, newType(fqcn, prefix, "java.lang", showAllItems));
        }
    }

    private static boolean isAllowedType(Class<?> type) {
        if (type == null) {
            return false;
        }
        return (type.isInterface() && !type.isAnnotation())
                || type.isRecord()
                || (!type.isPrimitive() && !type.isArray() && !type.isAnonymousClass()
                    && !type.isSynthetic() && !type.isEnum() && !type.isAnnotation());
    }

}
