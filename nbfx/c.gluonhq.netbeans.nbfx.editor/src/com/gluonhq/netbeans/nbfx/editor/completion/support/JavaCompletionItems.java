package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItemKind;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionTypeKind;
import com.gluonhq.netbeans.nbfx.api.completion.SimpleCompletionItem;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import java.lang.reflect.Modifier;
import java.util.Locale;

import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.isDeprecated;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.simpleName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.simpleTypeName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.toModifierBits;

/**
 * Factory methods for completion items: {@link SimpleCompletionItem} constructors used by every provider.
 */
public final class JavaCompletionItems {

    // Priority constants (lower = ranked first).
    static final int PRIORITY_EXPECTED_TYPE = 3;
    static final int PRIORITY_SCOPE_LOCAL = 4;
    static final int PRIORITY_ANNOTATION_MEMBER = 4;
    static final int PRIORITY_SEMANTIC_FIELD = 5;
    static final int PRIORITY_SEMANTIC_METHOD = 6;
    static final int PRIORITY_WRAPPER_TYPE = 6;
    static final int PRIORITY_ASSIGNABLE_SUBTYPE = 7;
    static final int PRIORITY_ANNOTATION_TYPE = 10;
    static final int PRIORITY_IMPORT_TYPE = 12;
    static final int PRIORITY_NEW_TYPE = 17;
    static final int PRIORITY_PACKAGE = 27;

    private JavaCompletionItems() {
    }

    // Item factories

    /** Lexical item: keyword, literal, local identifier, imported type. */
    public static CompletionItem lexical(String label, int priority, String rightText,
                                         CompletionItemKind kind, CompletionTypeKind typeKind) {
        return new SimpleCompletionItem(label, label, label.toLowerCase(Locale.ROOT),
                priority, label, rightText, kind, typeKind, 0, false, false);
    }

    /** Type/identifier item with shared parameters. */
    private static CompletionItem typeItem(String name, int priority, String rightText,
                                           CompletionTypeKind typeKind, int modifierBits,
                                           boolean emphasized, boolean deprecated) {
        return typeItem(name, priority, rightText, typeKind, modifierBits, emphasized, deprecated, "");
    }

    /**
     * Type/identifier item that also carries the {@linkplain CompletionItem#qualifiedName()
     * fully-qualified name}, so the editor can add a missing {@code import} when the item is committed.
     */
    private static CompletionItem typeItem(String name, int priority, String rightText,
                                           CompletionTypeKind typeKind, int modifierBits,
                                           boolean emphasized, boolean deprecated, String qualifiedName) {
        return new SimpleCompletionItem(name, name, name, priority,
                name, rightText, CompletionItemKind.TYPE, typeKind, modifierBits,
                emphasized, deprecated, qualifiedName);
    }

    /** Field / enum-constant proposal. {@code declared} marks members declared on the qualifier type (vs inherited). */
    static CompletionItem semanticField(Elements elements, VariableElement field,
                                        String prefix, boolean declared) {
        String name = field.getSimpleName().toString();
        return new SimpleCompletionItem(name, name, name,
                computePriority(PRIORITY_SEMANTIC_FIELD, name, prefix),
                name, simpleTypeName(field.asType().toString()),
                CompletionItemKind.FIELD, CompletionTypeKind.OTHER,
                toModifierBits(field.getModifiers()),
                declared, isDeprecated(elements, field));
    }

    /**
     * Local variable / parameter / resource / exception-parameter proposal. Renders with
     * the {@link CompletionItemKind#VARIABLE} icon and ranks higher than a field so the
     * popup surfaces the closest declaration first.
     */
    static CompletionItem semanticLocal(Elements elements, VariableElement variable, String prefix) {
        String name = variable.getSimpleName().toString();
        return new SimpleCompletionItem(name, name, name,
                computePriority(PRIORITY_SCOPE_LOCAL, name, prefix),
                name, simpleTypeName(variable.asType().toString()),
                CompletionItemKind.VARIABLE, CompletionTypeKind.OTHER,
                toModifierBits(variable.getModifiers()),
                true, isDeprecated(elements, variable));
    }

    /** Method proposal with full parameter signature in the label; inserts {@code name(} or {@code name()} depending on arity. */
    static CompletionItem semanticMethod(Elements elements, ExecutableElement method,
                                         String prefix, boolean declared) {
        return semanticMethod(elements, method, prefix, declared, false);
    }

    /**
     * @param bareNameInsert when true, the insertion is just the method's simple name
     *                       (no parentheses). Used by {@code import static foo.Bar.|}
     *                       where {@code import static foo.Bar.method(double);} is not
     *                       valid Java — only the bare member name may follow.
     */
    static CompletionItem semanticMethod(Elements elements, ExecutableElement method,
                                         String prefix, boolean declared, boolean bareNameInsert) {
        String name = method.getSimpleName().toString();
        String params = method.getParameters().stream()
                .map(p -> simpleTypeName(p.asType().toString()))
                .reduce((a, b) -> a + ", " + b).orElse("");
        String insert = bareNameInsert ? name : (method.getParameters().isEmpty() ? name + "()" : name + "(");
        return new SimpleCompletionItem(name, insert, name,
                computePriority(PRIORITY_SEMANTIC_METHOD, name, prefix),
                name + "(" + params + ")", simpleTypeName(method.getReturnType().toString()),
                CompletionItemKind.METHOD, CompletionTypeKind.OTHER,
                toModifierBits(method.getModifiers()),
                declared, isDeprecated(elements, method));
    }

    /** The expected type itself for an expected-type popup (highest type-row priority). */
    static CompletionItem expectedType(Elements elements, TypeElement type, String prefix) {
        String name = type.getSimpleName().toString();
        String fqcn = type.getQualifiedName().toString();
        return typeItem(name,
                computePriority(PRIORITY_EXPECTED_TYPE, name, prefix),
                fqcn,
                CompletionTypeKind.from(type.getKind().name()),
                toModifierBits(type.getModifiers()), true, isDeprecated(elements, type), fqcn);
    }

    /** A subtype assignable to an expected type, ranked just below {@link #expectedType}. */
    static CompletionItem assignableSubtype(Elements elements, TypeElement candidate, String prefix) {
        String name = candidate.getSimpleName().toString();
        String fqcn = candidate.getQualifiedName().toString();
        return typeItem(name,
                computePriority(PRIORITY_ASSIGNABLE_SUBTYPE, name, prefix),
                fqcn,
                CompletionTypeKind.from(candidate.getKind().name()),
                toModifierBits(candidate.getModifiers()), false, isDeprecated(elements, candidate), fqcn);
    }

    /** Type whose constructor wraps the expected type (e.g. {@code WeakInvalidationListener(InvalidationListener)}). */
    static CompletionItem structuralWrapperType(Elements elements, TypeElement candidate, String prefix) {
        String name = candidate.getSimpleName().toString();
        String fqcn = candidate.getQualifiedName().toString();
        return typeItem(name,
                computePriority(PRIORITY_WRAPPER_TYPE, name, prefix),
                fqcn,
                CompletionTypeKind.CLASS,
                toModifierBits(candidate.getModifiers()), false, isDeprecated(elements, candidate), fqcn);
    }

    /** Convention wrapper ({@code Weak<Name>} sibling of an expected interface). */
    static CompletionItem conventionWrapperType(String name, String prefix, String fqcn,
                                                int modifierBits, boolean deprecated) {
        return typeItem(name,
                computePriority(PRIORITY_WRAPPER_TYPE, name, prefix),
                fqcn, CompletionTypeKind.CLASS, modifierBits, false, deprecated, fqcn);
    }

    /** Type item for import/qualified-path context — typed by runtime class when available.
     * Appends a trailing {@code .} to the inserted text when {@code appendDot} is true.
     * Used by {@code import static foo.Bar|} so picking {@code Bar} produces {@code foo.Bar.} and the popup
     * can immediately follow up with the class's static members.
     */
    static CompletionItem importType(String fqcn, String prefix, boolean appendDot) {
        Class<?> runtime = JavaCompletionTypeUtils.tryLoad(fqcn);
        String simpleName = runtime != null ? runtime.getSimpleName() : simpleName(fqcn);
        String rightText = runtime != null ? runtime.getTypeName() : fqcn;
        String insert = appendDot ? simpleName + "." : simpleName;
        return new SimpleCompletionItem(simpleName, insert, simpleName,
                computePriority(PRIORITY_IMPORT_TYPE, simpleName, prefix),
                simpleName, rightText, CompletionItemKind.TYPE,
                runtime != null ? CompletionTypeKind.from(runtime) : CompletionTypeKind.OTHER,
                runtime != null ? runtime.getModifiers() : 0,
                false, runtime != null && runtime.isAnnotationPresent(Deprecated.class));
    }

    /**
     * Sub-package proposal for qualified-path completion (e.g. {@code import javafx.|}
     * offering {@code scene}, {@code stage}, …). The inserted text appends a trailing
     * {@code .} so the popup can immediately move into the next segment.
     */
    static CompletionItem packageItem(String packageName, String prefix) {
        return new SimpleCompletionItem(packageName, packageName + ".", packageName,
                computePriority(PRIORITY_PACKAGE, packageName, prefix),
                packageName, "",
                CompletionItemKind.PACKAGE, CompletionTypeKind.OTHER, 0, false, false);
    }

    /** Type item for the {@code new ...} context — typed by runtime class when available. */
    static CompletionItem newType(String fqcn, String prefix, String packageName, boolean showAllItems) {
        Class<?> runtime = JavaCompletionTypeUtils.tryLoad(fqcn);
        String simpleName = runtime != null ? runtime.getSimpleName() : simpleName(fqcn);
        String rightText = showAllItems ? "(" + packageName + ")" : "";
        return typeItem(simpleName,
                computePriority(PRIORITY_NEW_TYPE, simpleName, prefix),
                rightText,
                runtime != null ? CompletionTypeKind.from(runtime) : CompletionTypeKind.OTHER,
                runtime != null ? runtime.getModifiers() : 0,
                false, runtime != null && runtime.isAnnotationPresent(Deprecated.class),
                fqcn);
    }

    /**
     * Type item for a top-level type declared in the active source file (not yet on the
     * classpath, so {@link JavaCompletionTypeUtils#tryLoad} would fail). The caller
     * supplies the {@link CompletionTypeKind} parsed from the {@code class/interface/enum/record}
     * keyword so the popup picks the right icon.
     */
    static CompletionItem sameFileType(String simpleName, String prefix, CompletionTypeKind typeKind) {
        return typeItem(simpleName,
                computePriority(PRIORITY_IMPORT_TYPE, simpleName, prefix),
                "", typeKind, 0, false, false);
    }

    /**
     * Annotation-type proposal for the {@code @|} popup. Renders with the annotation
     * icon (via {@link CompletionTypeKind#ANNOTATION}) and inserts just the simple
     * name — the {@code @} is already on the line.
     */
    static CompletionItem annotationType(String fqcn, String prefix) {
        String simpleName = simpleName(fqcn);
        return new SimpleCompletionItem(simpleName, simpleName, simpleName,
                computePriority(PRIORITY_ANNOTATION_TYPE, simpleName, prefix), simpleName, fqcn,
                CompletionItemKind.TYPE, CompletionTypeKind.ANNOTATION,
                Modifier.PUBLIC, false, false);
    }
    /**
     * Annotation-member proposal for the {@code @Foo(|)} popup. Insertion places the
     * caret after {@code = } so the user can type the value immediately.
     */
    static CompletionItem annotationMember(ExecutableElement member, String prefix) {
        String name = member.getSimpleName().toString();
        String returnType = simpleTypeName(member.getReturnType().toString());
        // Insertion places the caret after the `=` so the user can immediately type the value.
        String insert = name + " = ";
        return new SimpleCompletionItem(name, insert, name,
                computePriority(PRIORITY_ANNOTATION_MEMBER, name, prefix), name, returnType,
                CompletionItemKind.FIELD, CompletionTypeKind.OTHER,
                Modifier.PUBLIC | Modifier.ABSTRACT,
                true, false);
    }

    /**
     * Enum-constant proposal for an annotation-value position, e.g. {@code @Retention(value = |)}
     * → {@code RetentionPolicy.SOURCE}. Insertion is always the qualified
     * {@code EnumSimpleName.CONSTANT} form because annotation values reject the bare
     * constant unless statically imported.
     */
    static CompletionItem annotationEnumValue(Elements elements, VariableElement constant,
                                              String enumSimpleName, String enumFqcn, String prefix) {
        String constantName = constant.getSimpleName().toString();
        String insert = enumSimpleName + "." + constantName;
        return new SimpleCompletionItem(insert, insert, insert,
                computePriority(PRIORITY_EXPECTED_TYPE, insert, prefix),
                insert, enumFqcn,
                CompletionItemKind.FIELD, CompletionTypeKind.OTHER,
                Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL,
                true, isDeprecated(elements, constant));
    }

    /** Visual separator row used to split the popup into themed sections. */
    static CompletionItem separator(String prefix) {
        int sepPriority = prefix == null || prefix.isBlank() ? 20 : 10;
        return new SimpleCompletionItem("separator", "", "separator",
                sepPriority, "", "",
                CompletionItemKind.SEPARATOR, CompletionTypeKind.OTHER, 0, false, false);
    }

    /**
     * {@code null} literal proposal — used by semantic queries (e.g. switch case
     * labels) that suppress lexical contributions but still need to surface
     * {@code null} as a valid choice.
     */
    static CompletionItem nullLiteral() {
        return lexical("null", PRIORITY_PACKAGE - 1, "literal",
                CompletionItemKind.KEYWORD, CompletionTypeKind.OTHER);
    }

    /**
     * Returns {@code base} adjusted by a small bonus / penalty that mirrors how well
     * {@code candidate} matches {@code prefix}: exact match wins, case-sensitive
     * starts-with next, case-insensitive variants follow, finally a constant penalty
     * for anything else.
     */
    private static int computePriority(int base, String candidate, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return base + 10;
        }
        if (candidate.equals(prefix)) return base;
        if (candidate.startsWith(prefix)) return base + 1;
        String lc = candidate.toLowerCase(Locale.ROOT);
        String lp = prefix.toLowerCase(Locale.ROOT);
        if (lc.equals(lp)) return base + 2;
        if (lc.startsWith(lp)) return base + 3;
        return base + 20;
    }
}
