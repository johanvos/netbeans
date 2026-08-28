package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionCancellation;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.TreePath;
import org.netbeans.api.java.source.CompilationController;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.lowerPrefix;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.prefixMismatch;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.putItem;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionTypeUtils.normalizeDeclaredType;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaSourceTextScanner.braceDepthAt;

/**
 * Enumerates the members of a {@link TypeElement} for the completion popup.
 *
 * <p>{@link #collectSemanticMembers} is invoked from {@link JavaSemanticCompletionQueries#queryPackageWithJavaSource}
 * for the {@code import static a.B.|} case.</p>
 */
final class JavaMemberQualifierResolver {

    record QualifierResolution(TypeMirror type, boolean staticOnly) {}

    /**
     * How far back from the caret/dot we probe when looking for an enclosing
     * {@link MemberSelectTree}. Broken sources cause the parser to attach the leaf at
     * unpredictable offsets; 32 characters comfortably covers a long identifier without
     * pulling in unrelated trees.
     */
    private static final int MEMBER_SELECT_PROBE_RADIUS = 32;

    private JavaMemberQualifierResolver() {
    }

    /**
     * Returns the matching members of {@code ownerType}, filtered by accessibility,
     * static-ness, prefix and visibility rules.
     *
     * <p>Summary:</p>
     * <ul>
     *   <li>{@code staticOnly} keeps only {@code static} members; otherwise non-static
     *       members are returned (and static members only when {@code showAllItems}).</li>
     *   <li>{@code showAllItems} (Ctrl+Space pressed a second time) lifts the
     *       private/protected/package-private filter so the full member set is offered.</li>
     * </ul>
     *
     * @param controller            javac controller used for formatting and access checks
     * @param ownerType             type whose members are enumerated
     * @param staticOnly            include only {@code static} members
     * @param showAllItems          {@code true} to consider the private/non-public members
     * @param prefix                user prefix, used for ranking and filtering
     * @param cancellation          cooperative cancellation signal
     */
    static List<CompletionItem> collectSemanticMembers(CompilationController controller, TypeElement ownerType,
                                                       boolean staticOnly, boolean showAllItems,
                                                       String prefix,
                                                       CompletionCancellation cancellation) {
        return collectSemanticMembers(controller, ownerType, staticOnly, showAllItems,
                prefix, false, cancellation);
    }

    /**
     * Returns the matching members of {@code ownerType}, filtered by accessibility,
     * static-ness, prefix and visibility rules.
     *
     * <p>Summary:</p>
     * <ul>
     *   <li>{@code staticOnly} keeps only {@code static} members; otherwise non-static
     *       members are returned (and static members only when {@code showAllItems}).</li>
     *   <li>{@code showAllItems} (Ctrl+Space pressed a second time) lifts the
     *       private/protected/package-private filter so the full member set is offered.</li>
     *   <li>{@code bareNameMethodInsert} switches the method insert text to the bare
     *       member name (no parentheses) — required by static-import completion since
     *       {@code import static foo.Bar.method(double);} is not valid Java. The same
     *       flag also restricts the visible static members to {@code public} ones,
     *       matching the JLS rule for which members can be statically imported.</li>
     * </ul>
     *
     * @param controller            javac controller used for formatting and access checks
     * @param ownerType             type whose members are enumerated
     * @param staticOnly            include only {@code static} members
     * @param showAllItems          {@code true} to consider the private/non-public members
     * @param prefix                user prefix, used for ranking and filtering
     * @param bareNameMethodInsert  insert the bare method name (static-import flavor)
     * @param cancellation          cooperative cancellation signal
     */
    static List<CompletionItem> collectSemanticMembers(CompilationController controller, TypeElement ownerType,
                                                       boolean staticOnly, boolean showAllItems,
                                                       String prefix, boolean bareNameMethodInsert,
                                                       CompletionCancellation cancellation) {
        String lowerPrefix = lowerPrefix(prefix);
        Map<String, CompletionItem> result = new LinkedHashMap<>();

        for (Element member : controller.getElements().getAllMembers(ownerType)) {
            if (cancellation.isCancelled()) {
                return List.of();
            }
            String name = member.getSimpleName().toString();
            if (name.isBlank() || "<init>".equals(name) || prefixMismatch(name, lowerPrefix)) {
                continue;
            }
            Set<Modifier> mods = member.getModifiers();
            boolean isStatic = mods.contains(Modifier.STATIC);
            boolean isPrivate = mods.contains(Modifier.PRIVATE);
            if (staticOnly ? !isStatic : (!showAllItems && isStatic)) {
                continue;
            }
            if (!showAllItems && isPrivate) {
                continue;
            }
            if (bareNameMethodInsert && !showAllItems && !mods.contains(Modifier.PUBLIC)) {
                continue;
            }
            boolean declared = member.getEnclosingElement().equals(ownerType);
            if (member.getKind() == ElementKind.FIELD || member.getKind() == ElementKind.ENUM_CONSTANT) {
                // 1. Add semanticField item (field/enum)
                putItem(result, JavaCompletionItems.semanticField(
                        controller.getElements(), (VariableElement) member, prefix, declared));
            } else if (member.getKind() == ElementKind.METHOD) {
                if (isPrivate && !declared) {
                    continue;
                }
                // 2. Add semanticMethod item
                putItem(result, JavaCompletionItems.semanticMethod(
                        controller.getElements(), (ExecutableElement) member, prefix, declared, bareNameMethodInsert));
            }
        }
        return List.copyOf(result.values());
    }

    /**
     * Finds a {@link MemberSelectTree} path covering {@code dotOffset} or {@code caretProbeOffset}.
     * Probes backward up to {@link #MEMBER_SELECT_PROBE_RADIUS} characters because the parser
     * may attach the leaf one or several positions off — especially in partially typed sources.
     * Returns {@code null} when no enclosing {@code MemberSelectTree} is found.
     */
    static TreePath findMemberSelectPath(CompilationController controller,
                                         int dotOffset, int caretProbeOffset) {
        int upperProbe = Math.max(dotOffset, caretProbeOffset);
        int lowerProbe = Math.max(0, Math.min(dotOffset, caretProbeOffset) - MEMBER_SELECT_PROBE_RADIUS);
        for (int probe = upperProbe; probe >= lowerProbe; probe--) {
            TreePath path = controller.getTreeUtilities().pathFor(probe);
            if (path == null) {
                continue;
            }
            for (TreePath current = path; current != null; current = current.getParentPath()) {
                if (current.getLeaf() instanceof MemberSelectTree) {
                    return current;
                }
            }
        }
        return null;
    }

    /**
     * Walks back from {@code dotOffset} to extract the qualifier identifier and resolve it
     * to a {@link QualifierResolution}. Tries two strategies in order:
     * <ol>
     *   <li>Ask the parser for the {@link TreePath} at the qualifier's position and pull
     *       the {@link Element}'s {@link TypeMirror} out of it.</li>
     *   <li>If that fails (broken source, unresolved symbol), regex-scan {@code source}
     *       for a declaration-like pattern {@code Type qualifier ...} (locals, fields,
     *       method/catch parameters) that's in scope
     *       at the dot and resolve {@code Type} via imports + classpath.</li>
     * </ol>
     * Returns {@code null} when both strategies fail to identify a usable type.
     */
    static QualifierResolution resolveMemberQualifierFallback(CompilationController controller,
                                                              String source, int dotOffset) {
        if (source == null || source.isBlank() || dotOffset <= 0 || dotOffset > source.length()) {
            return null;
        }
        int i = Math.clamp(dotOffset - 1, 0, source.length() - 1);
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) i--;
        if (i < 0) return null;

        int end = i;
        while (i >= 0 && (Character.isJavaIdentifierPart(source.charAt(i)) || source.charAt(i) == '.')) i--;
        String qualifier = source.substring(i + 1, end + 1).trim();
        if (qualifier.isBlank()) return null;

        TreePath qualifierPath = null;
        for (int probe = end; probe >= Math.max(0, i + 1); probe--) {
            qualifierPath = controller.getTreeUtilities().pathFor(probe);
            if (qualifierPath != null) break;
        }
        if (qualifierPath != null) {
            Element element = controller.getTrees().getElement(qualifierPath);
            if (element != null && element.getKind() != ElementKind.PACKAGE) {
                TypeMirror type = element.asType();
                if (type != null && type.getKind() != TypeKind.PACKAGE) {
                    return new QualifierResolution(type, element instanceof TypeElement);
                }
            }
        }
        return resolveLocalVariableQualifierFallback(controller, source, qualifier, dotOffset);
    }

    private static QualifierResolution resolveLocalVariableQualifierFallback(CompilationController controller,
                                                                             String source,
                                                                             String qualifier, int dotOffset) {
        // Match common declaration shapes for the qualifier:
        // - local variables: Type qualifier = ...;
        // - fields: Type qualifier;
        // - parameters: (Type qualifier)
        // and similar list-based forms like catch params.
        Pattern declarationPattern = Pattern.compile(
                "(?m)([\\w.$<>\\[\\]]+)\\s+" + Pattern.quote(qualifier) + "\\s*[=;,)]+");
        String prefix = source.substring(0, Math.min(dotOffset, source.length()));
        int dotDepth = braceDepthAt(prefix, prefix.length());
        Matcher matcher = declarationPattern.matcher(prefix);
        String declaredType = null;
        while (matcher.find()) {
            String candidate = matcher.group(1);
            // Skip captures that are Java reserved keywords — they can never be a type name.
            if (SourceVersion.isKeyword(candidate)) {
                continue;
            }
            // Prefer declarations whose enclosing scope still contains the qualifier usage,
            // i.e. brace depth at the declaration position is no deeper than at the usage.
            int matchDepth = braceDepthAt(prefix, matcher.start());
            if (matchDepth <= dotDepth) {
                declaredType = candidate;
            }
        }
        if (declaredType == null) return null;
        declaredType = declaredType.replaceAll("<.*>", "").trim();
        if (declaredType.isBlank()) return null;

        String normalized = normalizeDeclaredType(declaredType);
        if (normalized.isBlank()) return null;

        TypeElement typeElement = JavaCompletionTypeUtils.resolveTypeElement(controller, normalized, source);
        return typeElement == null ? null : new QualifierResolution(typeElement.asType(), false);
    }
}
