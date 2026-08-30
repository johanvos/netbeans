package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionCancellation;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionContext;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.ElementHandle;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionContextUtils.findEnclosingInvocationOpenParen;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.isInternalJdkPackage;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.isInvalidCompletionTypeName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.lowerPrefix;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.packageName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.prefixMismatch;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.putItem;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.simpleName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItems.annotationEnumValue;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItems.annotationMember;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItems.annotationType;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionTypeUtils.resolveTypeElement;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaPackagePathCollector.SOURCE_AND_DEPS;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaSourceTextScanner.skipWhitespaceBackward;

/**
 * Annotation-completion support: detects {@code @|} and {@code @Foo(|)} positions and
 * collects matching items (annotation types from the classpath, and the annotation
 * type's declared member names respectively).
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>{@code @|} -&gt; lists all visible annotation types ({@code Override}, {@code FXML}, ...).</li>
 *   <li>{@code @SuppressWarnings(|)} or {@code @SuppressWarnings(va|)} -&gt; lists the annotation type's members
 *       ({@code value}, {@code ...}) inserted as {@code name = }.</li>
 * </ul>
 */
public final class JavaAnnotationCompletionUtils {


    private JavaAnnotationCompletionUtils() {
    }

    // Context detection

    /**
     * True when the caret immediately follows an unescaped {@code @} (optionally with
     * partial identifier characters in between), e.g. {@code @|}, {@code @Over|}.
     */
    public static boolean isAnnotationTypeContext(CompletionContext context) {
        String source = Objects.requireNonNull(context).documentText();
        int anchor = context.anchorOffset();
        if (source == null || source.isEmpty() || anchor <= 0 || anchor > source.length()) {
            return false;
        }
        int i = anchor - 1;
        // Skip back over any identifier characters typed after `@` (e.g. `@Ove|r`).
        while (i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) {
            i--;
        }
        return i >= 0 && source.charAt(i) == '@';
    }

    /**
     * True when the caret is inside the {@code (...)} argument list of an annotation,
     * e.g. {@code @FXML(|)} or {@code @SuppressWarnings("uncheck|ed")}. Mirrors the
     * structural scan of {@code isInvocationArgumentContext} but requires the
     * identifier preceding the opening paren to be preceded by {@code @}.
     */
    public static boolean isAnnotationArgumentContext(CompletionContext context) {
        return annotationNameForArgument(
                Objects.requireNonNull(context).documentText(), context.anchorOffset()) != null;
    }

    /**
     * Returns the dotted qualifier appearing between {@code @} and the opening paren
     * that encloses {@code anchorOffset}, e.g. {@code "FXML"} for {@code @FXML(|)} or
     * {@code "javax.annotation.Generated"} for {@code @javax.annotation.Generated(|)}.
     * Returns {@code null} when the caret is not inside an annotation argument list.
     */
    public static String annotationNameForArgument(String source, int anchorOffset) {
        if (source == null || source.isEmpty() || anchorOffset <= 0) {
            return null;
        }
        int parenOffset = findEnclosingInvocationOpenParen(source, anchorOffset);
        if (parenOffset < 0) {
            return null;
        }
        int end = parenOffset;
        int i = parenOffset - 1;
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) {
            i--;
            end--;
        }
        int nameEnd = end;
        while (i >= 0 && (Character.isJavaIdentifierPart(source.charAt(i)) || source.charAt(i) == '.')) {
            i--;
        }
        int nameStart = i + 1;
        if (nameStart >= nameEnd) {
            return null;
        }
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) {
            i--;
        }
        if (i < 0 || source.charAt(i) != '@') {
            return null;
        }
        return source.substring(nameStart, nameEnd);
    }

    /**
     * Resolved annotation-value context: the dotted annotation qualifier and the
     * named member whose value the caret is editing. Returned by
     * {@link #annotationValueContext} as a single atomic value so callers don't have
     * to repeat the (array-literal-tolerant) backward scan twice.
     */
    public record AnnotationValueContext(String annotationName, String memberName) {
    }

    /**
     * True when the caret sits at an annotation-member value position, e.g.
     * {@code @Retention(value = |)}, {@code @Retention(value = Ret|)},
     * {@code @Target(value = {ElementType.METHOD, |})}. Equivalent to
     * {@code annotationValueContext(...) != null}.
     */
    public static boolean isAnnotationValueContext(CompletionContext context) {
        return annotationValueContext(
                Objects.requireNonNull(context).documentText(), context.anchorOffset()) != null;
    }

    /**
     * Resolves the {@code (annotation, member)} pair when the caret sits at an
     * annotation-value position; returns {@code null} otherwise.
     *
     * <p>The scan tolerates a single enclosing array initializer (Java's
     * {@code @Anno(member = {a, b, |})} form): when the immediate left-context is
     * inside {@code { ... }} we walk back across its (possibly nested) braces until
     * the opening {@code {} of the array assigned to this member, then continue as
     * for the scalar case.</p>
     *
     * <p>The annotation-qualifier resolution is intentionally performed from a probe
     * offset that sits <i>just after the {@code =}</i> — i.e. outside any array
     * literal — because {@link #annotationNameForArgument} relies on
     * {@link JavaCompletionContextUtils#findEnclosingInvocationOpenParen} which bails
     * on the first unmatched {@code {} it sees while scanning backward. Probing from
     * the anchor (which may be inside such a {@code { ... }}) would always fail.</p>
     *
     * <p>Examples that yield {@code memberName = "value"}:</p>
     * <ul>
     *   <li>{@code @Retention(value = |)} / {@code @Retention(value = Ret|)}</li>
     *   <li>{@code @Target(value = {ElementType.METHOD, |})}</li>
     *   <li>{@code @SuppressWarnings(value = {"all", |})}</li>
     * </ul>
     */
    public static AnnotationValueContext annotationValueContext(String source, int anchorOffset) {
        if (source == null || source.isEmpty() || anchorOffset <= 0 || anchorOffset > source.length()) {
            return null;
        }
        int i = anchorOffset - 1;
        i = skipWhitespaceBackward(source, i);
        // Array-initializer tolerant: rewind across `{ ... }` (with nested braces) to
        // the `=` of the enclosing `member = { ... }`. Done *before* the annotation
        // qualifier check, since annotationNameForArgument() can't see past the
        // unmatched `{` that opens the array literal.
        if (i >= 0 && (source.charAt(i) == ',' || source.charAt(i) == '{'
                || isArrayLiteralLeftContext(source, i))) {
            int braceOffset = scanBackToEnclosingOpenBrace(source, i);
            if (braceOffset < 0) {
                return null;
            }
            i = skipWhitespaceBackward(source, braceOffset - 1);
        }
        if (i < 0 || source.charAt(i) != '=') {
            return null;
        }
        int eqOffset = i;
        // Read the member identifier preceding the `=`.
        i = skipWhitespaceBackward(source, eqOffset - 1);
        int nameEnd = i + 1;
        while (i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) {
            i--;
        }
        int nameStart = i + 1;
        if (nameStart >= nameEnd) {
            return null;
        }
        String memberName = source.substring(nameStart, nameEnd);
        // Resolve the enclosing annotation from a probe offset that sits outside any
        // array literal — i.e. immediately after the `=`.
        String annotationName = annotationNameForArgument(source, eqOffset + 1);
        if (annotationName == null) {
            return null;
        }
        return new AnnotationValueContext(annotationName, memberName);
    }

    // Item collection

    /**
     * Enumerates every annotation type discoverable through the {@link ClassIndex} and
     * turns each into a completion proposal. The index is queried with
     * {@link ClassIndex.NameKind#CASE_INSENSITIVE_PREFIX CASE_INSENSITIVE_PREFIX} —
     * including the blank-prefix case, where it still beats the wildcard {@code ".*"}
     * regex flavour on long classpaths — and the result is then filtered to
     * {@link ElementKind#ANNOTATION_TYPE}.
     *
     * <p>{@code java.lang} annotations ({@code @Override}, {@code @Deprecated},
     * {@code @SuppressWarnings}, {@code @FunctionalInterface}, …) are enumerated
     * directly through the {@link PackageElement} because the JDK bootstrap classpath
     * isn't always covered by {@link ClassIndex.SearchScope#DEPENDENCIES} — without
     * this {@code @Overri|} would silently miss its only candidate.</p>
     */
    public static List<CompletionItem> collectAnnotationTypes(CompilationController controller,
                                                              String prefix,
                                                              CompletionCancellation cancellation) {
        String lowerPrefix = lowerPrefix(prefix);
        Map<String, CompletionItem> result = new LinkedHashMap<>();

        // 1. Always include matching java.lang annotations.
        collectJavaLangAnnotations(controller, prefix, lowerPrefix, cancellation, result);
        if (cancellation.isCancelled()) {
            return List.of();
        }

        // 2. Classpath scan, server-side filtered by prefix.
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
            if (handle.getKind() != ElementKind.ANNOTATION_TYPE) {
                continue;
            }
            String fqcn = handle.getQualifiedName();
            if (fqcn.isBlank() || fqcn.contains("$") || isInternalJdkPackage(packageName(fqcn))) {
                continue;
            }
            String simpleName = simpleName(fqcn);
            if (simpleName.isBlank() || isInvalidCompletionTypeName(simpleName)
                    || prefixMismatch(simpleName, lowerPrefix)) {
                continue;
            }
            putItem(result, annotationType(fqcn, prefix));
        }
        return List.copyOf(result.values());
    }

    /**
     * Walks {@code java.lang} via its {@link PackageElement}, emitting an annotation
     * proposal for every {@link ElementKind#ANNOTATION_TYPE} member matching
     * {@code prefix}. No classpath scan is involved — the JDK is always present
     * through {@link javax.lang.model.util.Elements#getPackageElement Elements.getPackageElement}
     * even when the {@link ClassIndex} scopes don't cover it.
     */
    private static void collectJavaLangAnnotations(CompilationController controller, String prefix,
                                                   String lowerPrefix, CompletionCancellation cancellation,
                                                   Map<String, CompletionItem> result) {
        PackageElement javaLang = controller.getElements().getPackageElement("java.lang");
        if (javaLang == null) {
            return;
        }
        for (Element child : javaLang.getEnclosedElements()) {
            if (cancellation.isCancelled()) {
                return;
            }
            if (!(child instanceof TypeElement typeChild)
                    || typeChild.getKind() != ElementKind.ANNOTATION_TYPE) {
                continue;
            }
            String simpleName = typeChild.getSimpleName().toString();
            if (simpleName.isBlank() || isInvalidCompletionTypeName(simpleName)
                    || prefixMismatch(simpleName, lowerPrefix)) {
                continue;
            }
            putItem(result, annotationType(typeChild.getQualifiedName().toString(), prefix));
        }
    }

    /**
     * Resolves the annotation type whose argument list contains the caret and emits a
     * proposal for each of its members ({@code name = }-style insertion). Returns an
     * empty list when the annotation cannot be resolved (e.g. unknown import).
     */
    public static List<CompletionItem> collectAnnotationMembers(CompilationController controller,
                                                                String annotationName,
                                                                String source,
                                                                String prefix,
                                                                CompletionCancellation cancellation) {
        if (annotationName == null || annotationName.isBlank()) {
            return List.of();
        }
        TypeElement annotationType = resolveTypeElement(controller, annotationName, source);
        if (annotationType == null || annotationType.getKind() != ElementKind.ANNOTATION_TYPE) {
            return List.of();
        }
        String lowerPrefix = lowerPrefix(prefix);
        Map<String, CompletionItem> result = new LinkedHashMap<>();
        for (Element member : annotationType.getEnclosedElements()) {
            if (cancellation.isCancelled()) {
                return List.of();
            }
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            String name = member.getSimpleName().toString();
            if (name.isBlank() || prefixMismatch(name, lowerPrefix)) {
                continue;
            }
            putItem(result, annotationMember((ExecutableElement) member, prefix));
        }
        return List.copyOf(result.values());
    }

    /**
     * Resolves the annotation type and the named member, then emits value proposals
     * matching the member's declared return type. Currently handles:
     * <ul>
     *   <li><b>enum-typed members</b> — emits {@code EnumSimpleName.CONSTANT} for every
     *       declared constant. Used by {@code @Retention(value = |)},
     *       {@code @Target(value = |)}, …</li>
     *   <li><b>array-of-enum members</b> — the component type is unwrapped and the
     *       same enum-constant proposals are emitted, so
     *       {@code @Target(value = {ElementType.METHOD, |})} suggests the remaining
     *       {@link javax.lang.model.element.ElementKind#ENUM_CONSTANT enum constants}.</li>
     * </ul>
     *
     * <p>Returns an empty list for member types we don't have a specialised proposal
     * for ({@code String}, primitives, {@code Class<?>}, nested annotation types). That
     * lets the lexical / identifier providers take over without producing duplicate
     * or misleading rows.</p>
     *
     * <p>Prefix matching is intentionally <i>label</i>-based, not constant-name-based:
     * for {@code @Retention(value = Ret|)} the user typed {@code "Ret"} expecting the
     * enum type name, not a constant, so we surface every constant whose enum simple
     * name matches the prefix in addition to those matching the constant name
     * directly.</p>
     */
    public static List<CompletionItem> collectAnnotationValueProposals(CompilationController controller,
                                                                       String annotationName,
                                                                       String memberName,
                                                                       String source,
                                                                       String prefix,
                                                                       CompletionCancellation cancellation) {
        if (annotationName == null || annotationName.isBlank() || memberName == null || memberName.isBlank()) {
            return List.of();
        }
        TypeElement annotationType = resolveTypeElement(controller, annotationName, source);
        if (annotationType == null || annotationType.getKind() != ElementKind.ANNOTATION_TYPE) {
            return List.of();
        }
        ExecutableElement member = findAnnotationMember(annotationType, memberName);
        if (member == null) {
            return List.of();
        }
        TypeMirror returnType = member.getReturnType();
        if (returnType != null && returnType.getKind() == TypeKind.ARRAY) {
            returnType = ((ArrayType) returnType).getComponentType();
        }
        if (returnType == null || returnType.getKind() != TypeKind.DECLARED) {
            return List.of();
        }
        Element typeAsElement = ((DeclaredType) returnType).asElement();
        if (!(typeAsElement instanceof TypeElement valueType) || valueType.getKind() != ElementKind.ENUM) {
            return List.of();
        }
        return collectEnumValueItems(controller, valueType, prefix, cancellation);
    }

    private static ExecutableElement findAnnotationMember(TypeElement annotationType, String memberName) {
        for (Element child : annotationType.getEnclosedElements()) {
            if (child.getKind() == ElementKind.METHOD
                    && memberName.contentEquals(child.getSimpleName())) {
                return (ExecutableElement) child;
            }
        }
        return null;
    }

    private static List<CompletionItem> collectEnumValueItems(CompilationController controller,
                                                              TypeElement enumType,
                                                              String prefix,
                                                              CompletionCancellation cancellation) {
        String enumSimpleName = enumType.getSimpleName().toString();
        String enumFqcn = enumType.getQualifiedName().toString();
        String lowerPrefix = lowerPrefix(prefix);
        // When the prefix matches the enum type's own name (typical mid-type case like
        // `value = Ret|`), every constant qualifies.
        boolean enumNameMatches = !prefixMismatch(enumSimpleName, lowerPrefix);
        Map<String, CompletionItem> result = new LinkedHashMap<>();
        for (Element member : enumType.getEnclosedElements()) {
            if (cancellation.isCancelled()) {
                return List.of();
            }
            if (member.getKind() != ElementKind.ENUM_CONSTANT) {
                continue;
            }
            String constantName = member.getSimpleName().toString();
            if (!enumNameMatches && prefixMismatch(constantName, lowerPrefix)) {
                continue;
            }
            putItem(result, annotationEnumValue(
                    controller.getElements(), (VariableElement) member, enumSimpleName, enumFqcn, prefix));
        }
        return List.copyOf(result.values());
    }

    /**
     * Used to enable the rewind-to-{@code {} path for inputs like
     * {@code @Target(value = {ElementType.METHOD, ElementType.FIELD|}}.
     */
    private static boolean isArrayLiteralLeftContext(String source, int i) {
        if (i < 0) return false;
        char c = source.charAt(i);
        if (!Character.isJavaIdentifierPart(c) && c != '.' && c != '"' && c != '\'') {
            return false;
        }
        // Quick balance-aware probe: walk back at most until the enclosing `(` or `;`
        // and see whether the most recent unbalanced opener is `{`.
        int depth = 0;
        for (int j = i; j >= 0; j--) {
            char d = source.charAt(j);
            if (d == ')' || d == ']' || d == '}') depth++;
            else if (d == '{') {
                if (depth == 0) return true;
                depth--;
            }
            else if (d == '(' || d == ';' || d == '\n') {
                if (depth == 0) return false;
            }
        }
        return false;
    }

    /**
     * Walks back from {@code from} until the unbalanced {@code {} that opens the
     * enclosing array literal, or returns -1 if none is reached before a hard
     * boundary ({@code (}, {@code ;}, end of source).
     */
    private static int scanBackToEnclosingOpenBrace(String source, int from) {
        int depth = 0;
        for (int j = from; j >= 0; j--) {
            char c = source.charAt(j);
            if (c == '}') depth++;
            else if (c == '{') {
                if (depth == 0) return j;
                depth--;
            } else if (c == '(' || c == ';') {
                if (depth == 0) return -1;
            }
        }
        return -1;
    }

}
