package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionCancellation;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionContext;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import com.sun.source.tree.Scope;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import org.netbeans.api.java.source.CompilationController;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.lowerPrefix;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.prefixMismatch;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.putItem;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItems.semanticField;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItems.semanticLocal;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItems.semanticMethod;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaSourceTextScanner.resolveSemanticOffset;

/**
 * Collects in-scope identifiers — locals, parameters, resource/exception variables,
 * fields, enum constants and (zero-arg) methods — visible from a {@link TreePath}.
 */
final class JavaScopeIdentifierCollector {

    private JavaScopeIdentifierCollector() {
    }

    /**
     * Walks lexical scopes outward from {@code path} (collecting locals, parameters,
     * resource/exception variables), then adds enclosing-class members (fields and
     * zero-arg, non-void methods). Each candidate is filtered by prefix and assignability
     * to any of {@code expectedTypes}.
     */
    static List<CompletionItem> collectScopeIdentifiersAssignableTo(CompilationController controller,
                                                                    TreePath path,
                                                                    List<TypeMirror> expectedTypes,
                                                                    String prefix,
                                                                    CompletionCancellation cancellation) {
        Scope scope = controller.getTrees().getScope(path);
        if (scope == null) {
            return List.of();
        }
        Types types = controller.getTypes();
        String lowerPrefix = lowerPrefix(prefix);
        Map<String, CompletionItem> result = new LinkedHashMap<>();
        ExcludedScope excluded = excludedSelfReferences(controller, path);

        for (Scope s = scope; s != null; s = s.getEnclosingScope()) {
            if (cancellation.isCancelled()) {
                return List.of();
            }
            for (Element el : s.getLocalElements()) {
                addScopeCandidate(el, controller, types, expectedTypes,
                        prefix, lowerPrefix, excluded, result);
            }
        }
        TypeElement enclosing = scope.getEnclosingClass();
        if (enclosing != null) {
            for (Element member : controller.getElements().getAllMembers(enclosing)) {
                if (cancellation.isCancelled()) {
                    return List.of();
                }
                addScopeCandidate(member, controller, types, expectedTypes,
                        prefix, lowerPrefix, excluded, result);
            }
        }
        return List.copyOf(result.values());
    }

    private static void addScopeCandidate(Element el, CompilationController controller,
                                          Types types, List<TypeMirror> expectedTypes,
                                          String prefix, String lowerPrefix,
                                          ExcludedScope excluded, Map<String, CompletionItem> result) {
        if (isNotValidElement(el, lowerPrefix, excluded)) {
            return;
        }
        ElementKind kind = el.getKind();
        if (isVariableKind(kind)) {
            if (isAssignableToAny(types, el.asType(), expectedTypes)) {
                CompletionItem item = isLocalKind(kind)
                        ? semanticLocal(controller.getElements(), (VariableElement) el, prefix)
                        : semanticField(controller.getElements(), (VariableElement) el, prefix, true);
                putItem(result, item);
            }
            return;
        }
        if (kind == ElementKind.METHOD) {
            ExecutableElement method = (ExecutableElement) el;
            // Limit to no-arg, non-void methods so we can confidently surface them as
            // assignable expressions (getter-style usage).
            if (!method.getParameters().isEmpty() || method.getReturnType().getKind() == TypeKind.VOID) {
                return;
            }
            if (isAssignableToAny(types, method.getReturnType(), expectedTypes)) {
                putItem(result, semanticMethod(controller.getElements(), method, prefix, true));
            }
        }
    }

    private static boolean isVariableKind(ElementKind kind) {
        return isLocalKind(kind) || kind == ElementKind.FIELD || kind == ElementKind.ENUM_CONSTANT;
    }

    private static boolean isLocalKind(ElementKind kind) {
        return kind == ElementKind.LOCAL_VARIABLE
                || kind == ElementKind.PARAMETER
                || kind == ElementKind.RESOURCE_VARIABLE
                || kind == ElementKind.EXCEPTION_PARAMETER;
    }

    /**
     * Enumerates every identifier visible from {@code path} regardless of expected type
     * — locals, parameters, fields and zero-or-more-arg methods from enclosing scopes
     * and the enclosing class. Used for plain identifier completion ({@code roo|} in
     * an expression position).
     */
    static List<CompletionItem> collectScopeIdentifiers(CompilationController controller, TreePath path,
                                                        String prefix, CompletionCancellation cancellation) {
        Scope scope = controller.getTrees().getScope(path);
        return collectScopeIdentifiers(controller, scope, prefix, cancellation,
                excludedSelfReferences(controller, path));
    }

    /**
     * Same as {@link #collectScopeIdentifiers(CompilationController, TreePath, String, CompletionCancellation)}
     * but accepts a {@link Scope} directly. Use this when the scope has been resolved
     * via {@code TreeUtilities.scopeFor(offset)}, which has stronger error-recovery for
     * incomplete buffers than going through a {@code TreePath}.
     */
    static List<CompletionItem> collectScopeIdentifiers(CompilationController controller, Scope scope,
                                                        String prefix, CompletionCancellation cancellation) {
        return collectScopeIdentifiers(controller, scope, prefix, cancellation, new ExcludedScope());
    }

    private static List<CompletionItem> collectScopeIdentifiers(CompilationController controller, Scope scope,
                                                                String prefix, CompletionCancellation cancellation,
                                                                ExcludedScope excluded) {
        if (scope == null) {
            return List.of();
        }
        String lowerPrefix = lowerPrefix(prefix);
        Map<String, CompletionItem> result = new LinkedHashMap<>();

        for (Scope s = scope; s != null; s = s.getEnclosingScope()) {
            if (cancellation.isCancelled()) {
                return List.of();
            }
            for (Element el : s.getLocalElements()) {
                addPlainScopeCandidate(el, controller, prefix, lowerPrefix, excluded, result);
            }
        }
        TypeElement enclosing = scope.getEnclosingClass();
        if (enclosing != null) {
            for (Element member : controller.getElements().getAllMembers(enclosing)) {
                if (cancellation.isCancelled()) {
                    return List.of();
                }
                addPlainScopeCandidate(member, controller, prefix, lowerPrefix, excluded, result);
            }
        }
        return List.copyOf(result.values());
    }

    private static void addPlainScopeCandidate(Element el, CompilationController controller,
                                               String prefix, String lowerPrefix,
                                               ExcludedScope excluded, Map<String, CompletionItem> result) {
        if (isNotValidElement(el, lowerPrefix, excluded)) {
            return;
        }
        ElementKind kind = el.getKind();
        if (isLocalKind(kind)) {
            putItem(result, semanticLocal(controller.getElements(), (VariableElement) el, prefix));
            return;
        }
        if (kind == ElementKind.FIELD || kind == ElementKind.ENUM_CONSTANT) {
            putItem(result, semanticField(controller.getElements(), (VariableElement) el, prefix, true));
            return;
        }
        if (kind == ElementKind.METHOD) {
            putItem(result, semanticMethod(controller.getElements(), (ExecutableElement) el, prefix, true));
        }
    }

    private static boolean isAssignableToAny(Types types, TypeMirror src, List<TypeMirror> targets) {
        if (src == null) {
            return false;
        }
        TypeKind kind = src.getKind();
        if (kind == TypeKind.ERROR || kind == TypeKind.NONE || kind == TypeKind.VOID
                || kind == TypeKind.PACKAGE || kind == TypeKind.EXECUTABLE) {
            return false;
        }
        for (TypeMirror target : targets) {
            if (target == null) {
                continue;
            }
            try {
                if (types.isAssignable(src, target)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // Mismatched type universe (e.g. captured wildcard) — skip silently.
            }
        }
        return false;
    }

    private static boolean isNotValidElement(Element el, String lowerPrefix, ExcludedScope excluded) {
        if (el == null || !excluded.add(el)) {
            return true;
        }
        String name = el.getSimpleName().toString();
        if (name.isBlank() || "this".equals(name) || "super".equals(name) || "<init>".equals(name)) {
            return true;
        }
        if (excluded.containsName(name)) {
            return true;
        }
        return prefixMismatch(name, lowerPrefix);
    }

    /**
     * Returns a {@code visited}-style seed containing every variable whose initializer
     * encloses the caret — i.e. variables that are still being declared at the caret
     * position and therefore not yet usable (Java's "self-reference in initializer"
     * rule). Pre-seeding the collector's {@code visited} set with these elements makes
     * {@link #isNotValidElement} reject them on first sight.
     *
     * <p>Example: in {@code VBox box = new VBox(|)} the parser's
     * {@link com.sun.source.tree.Scope Scope} at the caret includes {@code box} as a
     * local, but using it inside its own initializer would be a compile error — and a
     * meaningless completion. Walking up the {@link TreePath} we find the enclosing
     * {@link VariableTree} whose {@linkplain VariableTree#getInitializer() initializer}
     * is on the path from the leaf, resolve its {@link Element}, and exclude it.</p>
     */
    private static ExcludedScope excludedSelfReferences(CompilationController controller, TreePath path) {
        ExcludedScope excluded = new ExcludedScope();
        if (path == null) {
            return excluded;
        }
        TreePath child = path;
        for (TreePath p = path.getParentPath(); p != null; child = p, p = p.getParentPath()) {
            if (p.getLeaf() instanceof VariableTree variable
                    && variable.getInitializer() == child.getLeaf()) {
                // Element-based exclusion: identity match against scope-walked symbols.
                Element element = controller.getTrees().getElement(p);
                if (element != null) {
                    excluded.addElement(element);
                }
                // Name-based backstop: when the local is still being declared the parser
                // may not yet have resolved an attributed Element for it (or may return a
                // distinct instance from the one surfaced by Scope#getLocalElements), in
                // which case the element-identity check above fails to filter it out. The
                // local shadows any same-name field/parameter in enclosing scopes, so it
                // is safe to exclude by simple name as well.
                if (variable.getName() != null) {
                    excluded.addName(variable.getName().toString());
                }
            }
        }
        return excluded;
    }

    // Scope-probe resolution

    /**
     * Resolves the enclosing scope around the prefix, trying several probe offsets in
     * order so we still get a usable scope when the parser's error recovery degrades one
     * of them. Returns {@code null} only when every probe fails.
     *
     * <p>The probes are ordered to prefer offsets located <em>after</em> the previous
     * statement terminator: javac only adds a local variable to its enclosing scope from
     * the position right after its declaration ends, so probing inside the declaration
     * (e.g. on the {@code ;} that closes {@code int news = 0;}) would silently exclude
     * the very local the user is typing the prefix of. The anchor and caret offsets sit
     * past that boundary; the walked-back probe is kept as a last-resort fallback.</p>
     */
    static Scope resolveScopeForIdentifier(CompilationController controller, CompletionContext context) {
        int anchor = Math.max(0, context.anchorOffset());
        int caret = Math.max(0, context.caretOffset() - 1);
        int beforePrefix = resolveScopeProbeOffset(context);
        for (int probe : distinctProbes(anchor, caret, beforePrefix)) {
            Scope scope = scopeAtOrNull(controller, probe);
            if (scope != null) {
                return scope;
            }
        }
        return null;
    }

    /**
     * Returns an offset suitable for resolving the lexical scope around the prefix.
     * Falls back to the caret-based probe when the anchor is at the start of the
     * buffer (no character precedes the prefix).
     */
    private static int resolveScopeProbeOffset(CompletionContext context) {
        String source = context.documentText();
        int anchor = context.anchorOffset();
        if (source == null || anchor <= 0) {
            return resolveSemanticOffset(source, context.caretOffset());
        }
        int i = anchor - 1;
        while (i > 0 && Character.isWhitespace(source.charAt(i))) {
            i--;
        }
        return i;
    }

    private static int[] distinctProbes(int a, int b, int c) {
        if (b == a && c == a) {
            return new int[] { a };
        }
        if (c == a) {
            return new int[] { a, b };
        }
        if (b == a) {
            return new int[] { a, c };
        }
        if (b == c) {
            return new int[] { a, b };
        }
        return new int[] { a, b, c };
    }

    private static Scope scopeAtOrNull(CompilationController controller, int offset) {
        try {
            return controller.getTreeUtilities().scopeFor(offset);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Bundles the identifiers that must be hidden from a scope walk: javac {@link Element}
     * instances visited (for both identity-based dedup and self-reference exclusion) and
     * simple names of variables whose initializer encloses the caret (a defensive backstop
     * when the unattributed in-progress local doesn't match by element identity).
     */
    private static final class ExcludedScope {
        private final Set<Element> elements = new HashSet<>();
        private final Set<String> names = new HashSet<>();

        boolean add(Element el) {
            return elements.add(el);
        }

        void addElement(Element el) {
            elements.add(el);
        }

        void addName(String name) {
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }

        boolean containsName(String name) {
            return names.contains(name);
        }
    }

}
