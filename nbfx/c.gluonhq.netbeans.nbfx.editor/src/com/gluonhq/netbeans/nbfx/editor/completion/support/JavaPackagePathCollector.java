package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionCancellation;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.CompilationController;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.isInternalJdkPackage;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.isInvalidCompletionTypeName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.lowerPrefix;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.prefixMismatch;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.putItem;

/**
 * Enumerates sub-packages and top-level types under a fully qualified package prefix.
 * Used to populate the popup for qualified-path completion ({@code import javafx.scene.|}
 * or {@code new javafx.scene.|}).
 *
 * <p>The collector uses two narrowly scoped APIs so each keystroke runs fast even on large classpaths:</p>
 * <ul>
 *   <li>{@link ClassIndex#getPackageNames(String, boolean, Set)} for the sub-package
 *       branch — returns only direct children of {@code packagePrefix}, no per-type
 *       iteration required.</li>
 *   <li>{@link javax.lang.model.util.Elements#getPackageElement(CharSequence) getPackageElement}
 *       (or {@link javax.lang.model.util.Elements#getTypeElement(CharSequence) getTypeElement}
 *       when the qualifier is itself a type) for the types branch — enumerates only the
 *       children of that one enclosing element, not the whole classpath.</li>
 * </ul>
 *
 * <p>Returned items are split into two groups (sub-packages first, types second) and
 * pre-filtered by the user prefix.</p>
 */
final class JavaPackagePathCollector {

    /** Standard {@link ClassIndex} search scopes shared by every type/package collector. */
    static final EnumSet<ClassIndex.SearchScope> SOURCE_AND_DEPS = EnumSet.of(
            ClassIndex.SearchScope.SOURCE, ClassIndex.SearchScope.DEPENDENCIES);

    private JavaPackagePathCollector() {
    }

    /**
     * Returns the merged sub-package + type candidates reachable under {@code packagePrefix}.
     *
     * <h2>Algorithm</h2>
     * <ol>
     *   <li>Resolve the qualifier as both a {@link PackageElement} and a {@link TypeElement}.
     *       A non-null type means the qualifier is a class (e.g.
     *       {@code import javafx.scene.layout.VBox.|}) and the sub-package branch is
     *       suppressed — its dotted children are nested types, not sub-packages.</li>
     *   <li>Sub-packages: ask {@link ClassIndex#getPackageNames} with
     *       {@code directOnly = true} for the next-level package segments under
     *       {@code packagePrefix + "."}; filter by the user prefix.</li>
     *   <li>Types: enumerate the enclosing element's children
     *       ({@link Element#getEnclosedElements()}) and keep only the {@link TypeElement}s
     *       that match the prefix and pass {@link #isAccessibleType the visibility check}.</li>
     * </ol>
     *
     * @param staticImport when {@code true}, type entries insert their simple name
     *                     followed by a trailing {@code .} so {@code import static foo.Bar|} gives {@code foo.Bar.}
     *                     allowing the selection of a static member.
     */
    static List<CompletionItem> collectPackagePathItems(CompilationController controller,
                                                        String packagePrefix, String prefix,
                                                        boolean staticImport, CompletionCancellation cancellation) {
        // Resolve the qualifier just once. Either, both, or neither of these can be non-null.
        PackageElement qualifierPackage = controller.getElements().getPackageElement(packagePrefix);
        TypeElement qualifierType = controller.getElements().getTypeElement(packagePrefix);

        String lowerPrefix = lowerPrefix(prefix);
        Map<String, CompletionItem> subPackages = new LinkedHashMap<>();
        Map<String, CompletionItem> types = new LinkedHashMap<>();

        // 1. Sub-packages — only meaningful when the qualifier is a package.
        if (qualifierType == null) {
            collectSubPackages(controller, packagePrefix, prefix, lowerPrefix, cancellation, subPackages);
            if (cancellation.isCancelled()) return List.of();
        }

        // 2. Top-level (or nested) types directly inside the qualifier.
        Element enclosing = qualifierType != null ? qualifierType : qualifierPackage;
        if (enclosing != null) {
            collectTypesIn(enclosing, packagePrefix, prefix, lowerPrefix, staticImport, cancellation, types);
            if (cancellation.isCancelled()) return List.of();
        }

        // Sub-packages first, types second.
        List<CompletionItem> result = new ArrayList<>(subPackages.values());
        result.addAll(types.values());
        return result;
    }

    /**
     * Enumerates the top-level package candidates matching the user prefix. Used for
     * import lines where the user hasn't typed a {@code .} yet — both
     * {@code import jav|} and {@code import static jav|} (the static flavor is just a
     * top-level package query; the {@code static} keyword has no effect on which
     * packages can appear).
     *
     * <p>Asks {@link ClassIndex#getPackageNames} with {@code directOnly = true} and an
     * empty package-name prefix so the index returns every top-level package in scope;
     * the user's typed prefix is then applied client-side. Internal JDK roots
     * ({@code sun}, {@code com.sun}, …) are filtered out.</p>
     *
     * @param prefix the user's typed prefix (may be empty); filters the result set
     *               case-insensitively
     */
    static List<CompletionItem> collectTopLevelPackageItems(CompilationController controller,
                                                            String prefix,
                                                            CompletionCancellation cancellation) {
        ClassIndex classIndex = controller.getClasspathInfo().getClassIndex();
        // The index API takes a package-name prefix (not the user's typed prefix), so we
        // pass "" to enumerate every top-level package and filter client-side below.
        Set<String> packages = classIndex.getPackageNames("", true, SOURCE_AND_DEPS);
        if (packages == null) return List.of(); // index task cancelled

        String lowerPrefix = lowerPrefix(prefix);
        Map<String, CompletionItem> result = new LinkedHashMap<>();
        for (String pkg : packages) {
            if (cancellation.isCancelled()) {
                return List.of();
            }
            if (pkg == null || pkg.isBlank() || isInternalJdkPackage(pkg)) continue;
            // In case pkg still has a sub-package:
            int dot = pkg.indexOf('.');
            String topLevel = dot < 0 ? pkg : pkg.substring(0, dot);
            if (topLevel.isBlank() || prefixMismatch(topLevel, lowerPrefix)) {
                continue;
            }
            putItem(result, JavaCompletionItems.packageItem(topLevel, prefix));
        }
        return List.copyOf(result.values());
    }

    private static void collectSubPackages(CompilationController controller, String packagePrefix,
                                           String prefix, String lowerPrefix,
                                           CompletionCancellation cancellation,
                                           Map<String, CompletionItem> subPackages) {
        String packageDotPrefix = packagePrefix + ".";
        Set<String> packageNames = controller.getClasspathInfo().getClassIndex()
                .getPackageNames(packageDotPrefix, true, SOURCE_AND_DEPS);
        if (packageNames == null) return;

        for (String fullPkg : packageNames) {
            if (cancellation.isCancelled()) return;
            if (fullPkg.isBlank() || isInternalJdkPackage(fullPkg)) continue;
            // getPackageNames returns the full FQ package name — derive the next segment.
            String subPkg = fullPkg.length() > packageDotPrefix.length()
                    ? fullPkg.substring(packageDotPrefix.length()) : fullPkg;
            int dot = subPkg.indexOf('.');
            if (dot >= 0) subPkg = subPkg.substring(0, dot);
            if (subPkg.isBlank() || prefixMismatch(subPkg, lowerPrefix)) continue;
            // Skip when there is also a type with the same simple name under the qualifier —
            // it will be offered by the types branch.
            if (controller.getElements().getTypeElement(packagePrefix + "." + subPkg) != null) continue;

            // Add package item
            putItem(subPackages, JavaCompletionItems.packageItem(subPkg, prefix));
        }
    }

    private static void collectTypesIn(Element enclosing, String packagePrefix, String prefix,
                                       String lowerPrefix, boolean staticImport,
                                       CompletionCancellation cancellation,
                                       Map<String, CompletionItem> types) {
        for (Element child : enclosing.getEnclosedElements()) {
            if (cancellation.isCancelled()) return;
            if (!(child instanceof TypeElement typeChild)) continue;
            String simpleName = typeChild.getSimpleName().toString();
            if (isInvalidCompletionTypeName(simpleName) || prefixMismatch(simpleName, lowerPrefix)) continue;
            if (!isAccessibleType(typeChild)) continue;
            String fqcn = packagePrefix + "." + simpleName;

            // Add import type item
            putItem(types, JavaCompletionItems.importType(fqcn, prefix, staticImport));
        }
    }

    /**
     * Types surfaced under a dotted qualifier must be reachable from arbitrary source
     * positions, so reject anything that is private or package-private (default access).
     */
    private static boolean isAccessibleType(TypeElement type) {
        Set<Modifier> mods = type.getModifiers();
        if (mods.contains(Modifier.PRIVATE)) {
            return false;
        }
        // Package-private nested types are not visible to importers outside the declaring package.
        return mods.contains(Modifier.PUBLIC) || mods.contains(Modifier.PROTECTED);
    }
}
