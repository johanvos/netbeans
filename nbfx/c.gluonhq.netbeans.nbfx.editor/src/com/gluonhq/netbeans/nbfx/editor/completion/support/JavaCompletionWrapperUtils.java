package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionCancellation;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.ElementHandle;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.lowerPrefix;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.packageName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.prefixMismatch;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.putItem;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItems.conventionWrapperType;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItems.structuralWrapperType;

/**
 * Provides wrapper-type suggestions around expected listener-like types.
 * Example: {@code someProperty().addListener(new |)} where popup rows can include convention and
 * structural wrappers like {@code WeakInvalidationListener}.
 */
final class JavaCompletionWrapperUtils {

    private static final String WRAPPER_PREFIX = "Weak";

    private JavaCompletionWrapperUtils() {
    }

    // ---------------------------------------------------------------------
    // Structural wrapper discovery from class index
    // ---------------------------------------------------------------------

    /**
     * Scans the project {@link ClassIndex} for classes in {@code expectedType}'s package
     * whose constructor or static factory accepts the expected type as a wrapped argument
     * — the typical "structural wrapper" pattern (e.g. {@code WeakInvalidationListener}
     * around {@code InvalidationListener}). Restricted to {@code expectedType}'s package
     * so the scan stays bounded; results are filtered by {@code prefix}.
     */
    static List<CompletionItem> collectStructuralWrapperTypeItems(CompilationController controller,
                                                                  TypeElement expectedType,
                                                                  String prefix,
                                                                  CompletionCancellation cancellation) {
        ClassIndex classIndex = controller.getClasspathInfo().getClassIndex();
        EnumSet<ClassIndex.SearchScope> scopes = EnumSet.of(
                ClassIndex.SearchScope.SOURCE,
                ClassIndex.SearchScope.DEPENDENCIES);
        String expectedPackage = packageName(expectedType.getQualifiedName().toString());
        String searchPrefix = prefix == null || prefix.isBlank() ? expectedPackage : prefix;
        if (searchPrefix.isBlank()) {
            return List.of();
        }
        Set<ElementHandle<TypeElement>> handles = classIndex.getDeclaredTypes(
                searchPrefix,
                ClassIndex.NameKind.CASE_INSENSITIVE_PREFIX,
                scopes);

        TypeMirror expectedErasure = controller.getTypes().erasure(expectedType.asType());
        Map<String, CompletionItem> result = new LinkedHashMap<>();

        for (ElementHandle<TypeElement> handle : handles) {
            if (cancellation.isCancelled()) {
                return List.of();
            }
            String handleQualifiedName = handle.getQualifiedName();
            if (!expectedPackage.isBlank() && !handleQualifiedName.startsWith(expectedPackage + ".")) {
                continue;
            }
            if (handleQualifiedName.contains("$")) {
                continue;
            }
            TypeElement candidate = handle.resolve(controller);
            if (candidate == null || candidate.equals(expectedType)) {
                continue;
            }
            if (candidate.getKind() != ElementKind.CLASS
                    || candidate.getModifiers().contains(Modifier.ABSTRACT)
                    || candidate.getModifiers().contains(Modifier.PRIVATE)) {
                continue;
            }

            TypeMirror candidateErasure = controller.getTypes().erasure(candidate.asType());
            if (!controller.getTypes().isSubtype(candidateErasure, expectedErasure)) {
                continue;
            }
            if (hasWrapperLikeConstructorOrFactory(controller, candidate, expectedErasure)) {
                putItem(result, structuralWrapperType(controller.getElements(), candidate, prefix));
            }
        }

        return result.values().stream().toList();
    }

    // ---------------------------------------------------------------------
    // Naming-convention wrapper discovery
    // ---------------------------------------------------------------------

    /**
     * Name-based shortcut for the most common JavaFX wrapper pattern: for every
     * assignable interface {@code Xxx}, propose the matching {@code WeakXxx} from
     * the same package when it exists. Cheap — no classpath scan, just a single
     * {@link JavaCompletionTypeUtils#tryLoad tryLoad} probe.
     */
    static List<CompletionItem> createConventionAssignableTypeItems(TypeElement expectedType, String prefix) {
        if (expectedType.getKind() != ElementKind.INTERFACE) {
            return List.of();
        }
        String packageName = packageName(expectedType.getQualifiedName().toString());
        if (packageName.isBlank()) {
            return List.of();
        }

        String wrapperSimpleName = WRAPPER_PREFIX + expectedType.getSimpleName().toString();
        if (prefixMismatch(wrapperSimpleName, lowerPrefix(prefix))) {
            return List.of();
        }
        String wrapperFqcn = packageName + "." + wrapperSimpleName;
        Class<?> wrapperClass = JavaCompletionTypeUtils.tryLoad(wrapperFqcn);
        if (wrapperClass == null) {
            return List.of(conventionWrapperType(
                    wrapperSimpleName, prefix, wrapperFqcn, 0, false));
        }
        Class<?> expectedClass = JavaCompletionTypeUtils.tryLoad(expectedType.getQualifiedName().toString());
        if (expectedClass != null && !expectedClass.isAssignableFrom(wrapperClass)) {
            return List.of();
        }
        return List.of(conventionWrapperType(wrapperSimpleName, prefix, wrapperClass.getTypeName(),
                wrapperClass.getModifiers(), wrapperClass.isAnnotationPresent(Deprecated.class)));
    }

    // ---------------------------------------------------------------------
    // Wrapper shape checks
    // ---------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code candidate} exposes a non-private constructor or a
     * static factory method (returning {@code candidate}) whose single parameter is the
     * expected type — i.e. it looks like a wrapper around {@code expectedErasure}.
     */
    static boolean hasWrapperLikeConstructorOrFactory(CompilationController controller,
                                                      TypeElement candidate,
                                                      TypeMirror expectedErasure) {
        TypeMirror candidateErasure = controller.getTypes().erasure(candidate.asType());
        for (javax.lang.model.element.Element enclosed : candidate.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CONSTRUCTOR) {
                javax.lang.model.element.ExecutableElement constructor = (javax.lang.model.element.ExecutableElement) enclosed;
                if (constructor.getModifiers().contains(Modifier.PRIVATE)) {
                    continue;
                }
                if (acceptsWrappedType(controller, constructor, expectedErasure)) {
                    return true;
                }
            }
            if (enclosed.getKind() == ElementKind.METHOD) {
                javax.lang.model.element.ExecutableElement method = (javax.lang.model.element.ExecutableElement) enclosed;
                if (!method.getModifiers().contains(Modifier.STATIC)) {
                    continue;
                }
                if (!controller.getTypes().isSameType(
                        controller.getTypes().erasure(method.getReturnType()),
                        candidateErasure)) {
                    continue;
                }
                if (acceptsWrappedType(controller, method, expectedErasure)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns {@code true} when {@code executable} takes exactly one parameter whose
     * erasure equals {@code expectedErasure} (varargs trailing arrays are unwrapped to
     * their component type first).
     */
    static boolean acceptsWrappedType(CompilationController controller,
                                      javax.lang.model.element.ExecutableElement executable,
                                      TypeMirror expectedErasure) {
        List<? extends javax.lang.model.element.VariableElement> params = executable.getParameters();
        if (params.size() != 1) {
            return false;
        }
        TypeMirror paramType = params.getFirst().asType();
        if (executable.isVarArgs() && paramType.getKind() == javax.lang.model.type.TypeKind.ARRAY) {
            paramType = ((javax.lang.model.type.ArrayType) paramType).getComponentType();
        }
        return controller.getTypes().isSameType(controller.getTypes().erasure(paramType), expectedErasure);
    }

}
