package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionCancellation;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.ElementHandle;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.isInternalJdkPackage;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.lowerPrefix;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.packageName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.prefixMismatch;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.putItem;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItems.assignableSubtype;

/**
 * Provides completion items assignable to an expected target type.
 * Example: {@code scene.setRoot(new |)} where expected type {@code Parent} leads to matching subtypes
 * like {@code Group}, {@code Pane}, and {@code AnchorPane}.
 */
final class JavaCompletionAssignableSubtypeUtils {

    private static final int MAX_ASSIGNABLE_CLASS_INDEX_ITEMS_WITH_BLANK_PREFIX = 300;

    private JavaCompletionAssignableSubtypeUtils() {
    }

    // ---------------------------------------------------------------------
    // Semantic classpath subtype collection
    // ---------------------------------------------------------------------

    /**
     * Collects class-index type candidates filtered by prefix and assignability to the expected type.
     * Example: {@code scene.setRoot(new |)} where only {@code Parent}-compatible items are kept.
     *
     * <p>When {@code includeCrossPackage} is {@code false} (the default Ctrl+Space behavior)
     * the result is restricted to subtypes living in the same package (or a
     * sub-package) as the expected type — keeps the popup focused. When {@code true}
     * (a second Ctrl+Space → {@code COMPLETION_ALL_QUERY_TYPE}) the package restriction
     * is lifted so the full classpath is searched; e.g. for {@code throws |} this surfaces
     * {@code java.io.IOException}, {@code java.sql.SQLException}, … alongside the
     * {@code java.lang} exceptions.</p>
     */
    static List<CompletionItem> collectAssignableTypesFromClasspath(CompilationController controller,
                                                                    TypeElement expectedType,
                                                                    String prefix,
                                                                    boolean includeCrossPackage,
                                                                    CompletionCancellation cancellation) {
        String lowerPrefix = lowerPrefix(prefix);
        ClassIndex classIndex = controller.getClasspathInfo().getClassIndex();
        EnumSet<ClassIndex.SearchScope> scopes = EnumSet.of(
                ClassIndex.SearchScope.SOURCE, ClassIndex.SearchScope.DEPENDENCIES);

        String expectedTypeName = expectedType.getQualifiedName().toString();

        Set<ElementHandle<TypeElement>> handles = new LinkedHashSet<>();
        String expectedPackage = packageName(expectedTypeName);
        if (!includeCrossPackage && !expectedPackage.isBlank()) {
            String packageRegex = Pattern.quote(expectedPackage + ".") + ".*";
            handles.addAll(classIndex.getDeclaredTypes(packageRegex, ClassIndex.NameKind.REGEXP, scopes));
            if (handles.isEmpty()) {
                handles.addAll(classIndex.getDeclaredTypes(expectedPackage + ".", ClassIndex.NameKind.PREFIX, scopes));
            }
        }
        if (handles.isEmpty() && !prefix.isBlank()) {
            handles.addAll(classIndex.getDeclaredTypes(prefix, ClassIndex.NameKind.CASE_INSENSITIVE_PREFIX, scopes));
        }
        if (handles.isEmpty() && prefix.isBlank()) {
            handles.addAll(classIndex.getDeclaredTypes("", ClassIndex.NameKind.CASE_INSENSITIVE_PREFIX, scopes));
        }

        Map<String, CompletionItem> result = new LinkedHashMap<>();
        if (!handles.isEmpty()) {
            boolean expectedInternal = isInternalJdkPackage(expectedPackage);
            for (ElementHandle<TypeElement> handle : handles) {
                if (cancellation.isCancelled()) {
                    return List.of();
                }
                if (prefix.isBlank() && result.size() >= MAX_ASSIGNABLE_CLASS_INDEX_ITEMS_WITH_BLANK_PREFIX) {
                    break;
                }
                String handleQualifiedName = handle.getQualifiedName();
                if (!includeCrossPackage
                        && !expectedPackage.isBlank()
                        && !handleQualifiedName.startsWith(expectedPackage + ".")) {
                    continue;
                }
                if (handleQualifiedName.contains("$")) {
                    continue;
                }
                // Skip internal JDK / vendor types before calling handle.resolve():
                if (!expectedInternal && isInternalJdkPackage(packageName(handleQualifiedName))) {
                    continue;
                }
                TypeElement candidate = handle.resolve(controller);
                if (candidate == null || candidate.equals(expectedType) ||
                        candidate.getModifiers().contains(Modifier.PRIVATE)) {
                    continue;
                }
                String name = candidate.getSimpleName().toString();
                if (name.isBlank() || prefixMismatch(name, lowerPrefix)) {
                    continue;
                }
                if (!controller.getTypes().isSubtype(controller.getTypes().erasure(candidate.asType()),
                        controller.getTypes().erasure(expectedType.asType()))) {
                    continue;
                }

                CompletionItem item = assignableSubtype(controller.getElements(), candidate, prefix);
                putItem(result, item);
            }
        }
        return result.values().stream().toList();
    }

    /**
     * Convenience overload retaining the original same-package restriction
     * (i.e. {@code includeCrossPackage = false}).
     */
    static List<CompletionItem> collectAssignableTypesFromClasspath(CompilationController controller,
                                                                    TypeElement expectedType,
                                                                    String prefix,
                                                                    CompletionCancellation cancellation) {
        return collectAssignableTypesFromClasspath(controller, expectedType, prefix, false, cancellation);
    }
}
