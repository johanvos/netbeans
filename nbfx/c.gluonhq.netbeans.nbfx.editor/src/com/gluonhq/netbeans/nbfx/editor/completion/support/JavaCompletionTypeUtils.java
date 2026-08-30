package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionTypeKind;
import org.netbeans.api.java.source.CompilationController;

import javax.lang.model.element.TypeElement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides Java type-name parsing and best-effort class-loading helpers for completion.
 * Examples: {@code new javafx.scene.|} and {@code import javafx.scene.Scene;} that resolve
 * package/type names for popup ranking.
 */
public final class JavaCompletionTypeUtils {

    // Matches a top-level type declaration and captures its kind + simple name.
    // Used by parseDeclaredTypes() to promote local type names (class/interface/enum/record/@interface)
    // to TYPE rank in the popup without needing javac.
    // Pattern blocks:
    //   (?m)^\s*                                — multi-line mode, allow leading indent
    //   (?:@[\w.]+(?:\s*\([^)]*\))?\s+)*        — zero+ annotations, each with optional
    //                                             single-line argument list, each followed
    //                                             by mandatory whitespace
    //   (?:(?:public|protected|...|static)\s+)* — zero+ modifiers, each + whitespace
    //   (@interface|class|interface|enum|record) — group 1: the declaration kind
    //   \s+([A-Za-z_$][A-Za-z0-9_$]*)            — group 2: the type's simple name
    //
    // Known limitations (acceptable for a best-effort lexical helper): multi-line annotation
    // arguments are not handled, and the pattern matches on any line — including occurrences
    // inside block comments or strings.
    private static final Pattern DECLARED_TYPE_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:@[\\w.]+(?:\\s*\\([^)]*\\))?\\s+)*"
                    + "(?:(?:public|protected|private|abstract|final|sealed|non-sealed|strictfp|static)\\s+)*"
                    + "(@interface|class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    // Same as DECLARED_TYPE_PATTERN, but also captures the segment between the type name
    // and the opening `{` of the body — that segment carries the `extends …` / `implements …` /
    // `permits …` clauses and any generic / record-component header. Group 3 is the captured
    // tail; the caller pulls individual clause lists out of it via parseClauseNames().
    //
    //   ([^{]*)\\{   — group 3: everything up to (but not including) the opening brace.
    //                  `{` cannot appear in record headers, generic bounds or supertype lists,
    //                  so this is safe as a stop character.
    //   (?s)         — DOTALL so multi-line declarations (clauses on a separate line) are matched.
    private static final Pattern DECLARED_TYPE_WITH_TAIL_PATTERN = Pattern.compile(
            "(?ms)^\\s*(?:@[\\w.]+(?:\\s*\\([^)]*\\))?\\s+)*"
                    + "(?:(?:public|protected|private|abstract|final|sealed|non-sealed|strictfp|static)\\s+)*"
                    + "(@interface|class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
                    + "([^{]*)\\{");

    // Matches an `import a.b.C;` declaration and captures the fully qualified name.
    // Used by parseExplicitImports() to build a `simpleName -> fqcn` map that
    // resolveImportedTypeKind() then feeds to Class.forName for TYPE-kind classification.
    // Pattern blocks:
    //   (?m)^\s*       — multi-line mode, allow leading indent on each line
    //   import\s+      — the `import` keyword + at least one whitespace
    //   ([\w.]+)       — group 1: the FQCN. Wildcard imports (`pkg.*`) are excluded.
    //   \s*;           — optional whitespace + terminating semicolon
    //
    // The `static` modifier of static imports is NOT matched: those are intentionally
    // ignored here because it requires semantic analysis.
    private static final Pattern IMPORT_PATTERN = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)\\s*;");

    // Matches any import declaration (static or not) and captures the FQN, allowing the
    // trailing `.*` of wildcard imports. Used by parseImports().
    // Pattern blocks: Same as {@link #IMPORT_PATTERN} but with an optional `static` modifier and a more permissive FQN capture:
    //   (static\s+)? — an optional `static` modifier + at least one whitespace
    //   ([\w.*]+)      — group 1: the FQN. Wildcard imports (`pkg.*`) are included.
    private static final Pattern IMPORT_STATIC_PATTERN = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.*]+)\\s*;");

    // Matches any package declaration, and captures the FQN.
    // Pattern blocks: Same as {@link #IMPORT_PATTERN} but with `package` keyword:
    //   package\s+      — the `package` keyword + at least one whitespace
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    private static final Map<String, Optional<Class<?>>> CLASS_CACHE = new ConcurrentHashMap<>();

    private JavaCompletionTypeUtils() {
    }

    // Source text parsing helpers

    /** Regex-scans {@code source} for top-level type declarations and maps each name to its {@link CompletionTypeKind}. */
    public static Map<String, CompletionTypeKind> parseDeclaredTypes(String source) {
        Map<String, CompletionTypeKind> result = new LinkedHashMap<>();
        Matcher matcher = DECLARED_TYPE_PATTERN.matcher(source);
        while (matcher.find()) {
            String kind = matcher.group(1);
            String name = matcher.group(2);
            result.put(name, CompletionTypeKind.fromSource(kind));
        }
        return result;
    }

    /**
     * Information about a top-level type declaration extracted from the source text:
     * its {@link CompletionTypeKind kind} and the simple names listed in its
     * {@code extends …}, {@code implements …} and {@code permits …} clauses (if any).
     */
    public record DeclaredTypeInfo(CompletionTypeKind kind, List<String> extendsNames,
                                   List<String> implementsNames, List<String> permitsNames) {}

    /**
     * Regex-scans {@code source} for top-level type declarations and returns each
     * declaration's {@link DeclaredTypeInfo}.
     */
    public static Map<String, DeclaredTypeInfo> parseDeclaredTypeInfos(String source) {
        Map<String, DeclaredTypeInfo> result = new LinkedHashMap<>();
        if (source == null || source.isBlank()) {
            return result;
        }
        Matcher matcher = DECLARED_TYPE_WITH_TAIL_PATTERN.matcher(source);
        while (matcher.find()) {
            String kind = matcher.group(1);
            String name = matcher.group(2);
            String tail = matcher.group(3);
            String stripped = stripBalanced(tail);
            List<String> extendsNames = parseClauseNames(stripped, "extends");
            List<String> implementsNames = parseClauseNames(stripped, "implements");
            List<String> permitsNames = parseClauseNames(stripped, "permits");
            result.put(name, new DeclaredTypeInfo(
                    CompletionTypeKind.fromSource(kind), extendsNames, implementsNames, permitsNames));
        }
        return result;
    }

    /** Parses non-wildcard {@code import} declarations into a {@code simpleName -> fqcn} map. */
    public static Map<String, String> parseExplicitImports(String source) {
        Map<String, String> imports = new LinkedHashMap<>();
        Matcher matcher = IMPORT_PATTERN.matcher(source);
        while (matcher.find()) {
            // Wildcard imports cannot match IMPORT_PATTERN — `*` is outside `[\w.]` — so the
            // captured FQCN is always a proper a.b.C form here.
            String fqcn = matcher.group(1);
            int dot = fqcn.lastIndexOf('.');
            if (dot > 0 && dot < fqcn.length() - 1) {
                imports.put(fqcn.substring(dot + 1), fqcn);
            }
        }
        return imports;
    }

    /**
     * Scans {@code source} for non-static {@code import} declarations and returns a map
     * keyed by simple name. Wildcard imports {@code a.b.*} are encoded as
     * {@code "*:a.b" -> "a.b"} entries so a single map can hold both kinds; callers can
     * iterate the wildcard subset by filtering keys that start with {@code "*:"}.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code import javafx.scene.Scene;} → {@code "Scene"          -> "javafx.scene.Scene"}</li>
     *   <li>{@code import javafx.scene.*;} → {@code "*:javafx.scene" -> "javafx.scene"}</li>
     *   <li>{@code import static java.lang.Math.PI;}  → ignored (static imports are not member-resolution candidates)</li>
     * </ul>
     */
    static Map<String, String> parseImports(String source) {
        Map<String, String> imports = new LinkedHashMap<>();
        Matcher matcher = IMPORT_STATIC_PATTERN.matcher(source);
        while (matcher.find()) {
            // Group 1 is the (possibly null) `static\s+` capture — skip when present.
            if (matcher.group(1) != null) {
                continue;
            }
            String fqcn = matcher.group(2);
            if (fqcn.endsWith(".*")) {
                String pkg = fqcn.substring(0, fqcn.length() - 2);
                imports.put("*:" + pkg, pkg);
                continue;
            }
            int lastDot = fqcn.lastIndexOf('.');
            if (lastDot > 0 && lastDot < fqcn.length() - 1) {
                imports.put(fqcn.substring(lastDot + 1), fqcn);
            }
        }
        return imports;
    }

    /**
     * Returns the dotted qualifier sitting immediately before the {@code .} located at
     * {@code dotOffset} — used by qualified-import completion to find the FQCN whose
     * children must be enumerated.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code "import javafx.|"} → {@code "javafx"}</li>
     *   <li>{@code "import javafx.scene.|"} → {@code "javafx.scene"}</li>
     *   <li>{@code "import static java.lang.Math.|"} → {@code "java.lang.Math"}</li>
     *   <li>{@code "foo.bar .|"} (notice whitespace) → {@code "foo.bar"}</li>
     * </ul>
     *
     * <p>Returns {@code null} when {@code dotOffset} is out of range, doesn't point at a
     * {@code .}, or when no identifier characters precede the dot.</p>
     */
    static String extractQualifier(String source, int dotOffset) {
        if (dotOffset <= 0 || dotOffset >= source.length() || source.charAt(dotOffset) != '.') {
            return null;
        }
        int end = dotOffset;
        int i = dotOffset - 1;
        // Tolerate whitespace between the qualifier and the dot — `foo.bar .|` happens.
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) {
            i--;
            end--;
        }
        // Walk back over identifier parts and inner dots until a structural separator.
        while (i >= 0) {
            char ch = source.charAt(i);
            if (Character.isJavaIdentifierPart(ch) || ch == '.') {
                i--;
            } else {
                break;
            }
        }
        String qualifier = source.substring(i + 1, end).trim();
        if (qualifier.endsWith(".")) {
            qualifier = qualifier.substring(0, qualifier.length() - 1);
        }
        return qualifier;
    }

    // Runtime class loading fallback

    /**
     * Best-effort {@code Class.forName()} lookup over the context / class / system loaders,
     * with positive and negative results cached so repeated keystrokes don't re-trigger
     * slow classloader probes. Returns {@code null} for blank names or types that aren't
     * visible from any loader.
     */
    public static Class<?> tryLoad(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) {
            return null;
        }
        Optional<Class<?>> cached = CLASS_CACHE.get(fqcn);
        if (cached != null) {
            return cached.orElse(null);
        }
        ClassLoader[] loaders = {Thread.currentThread().getContextClassLoader(),
                JavaCompletionTypeUtils.class.getClassLoader(), ClassLoader.getSystemClassLoader()};
        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            try {
                Class<?> resolved = Class.forName(fqcn, false, loader);
                CLASS_CACHE.put(fqcn, Optional.of(resolved));
                return resolved;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // try next loader
            }
        }
        CLASS_CACHE.put(fqcn, Optional.empty());
        return null;
    }

    /**
     * Resolves a simple type name (e.g. {@code "Scene"}) to its fully qualified form by
     * searching {@code source}'s imports and package declaration. Used by the member
     * completion's text fallback to turn a local variable's declared type into something
     * {@code Elements.getTypeElement(...)} can look up.
     *
     * <h2>Resolution order</h2>
     * <ol>
     *   <li>If {@code declaredType} already contains a {@code .}, return it unchanged.</li>
     *   <li>Explicit import: {@code Scene} → {@code javafx.scene.Scene} when
     *       {@code import javafx.scene.Scene;} is present.</li>
     *   <li>Wildcard imports: try every {@code "*:pkg"} import, preferring the first one
     *       whose {@code pkg.Scene} actually loads via {@link #tryLoad}.</li>
     *   <li>{@code java.lang.<Name>} if it loads.</li>
     *   <li>Current package: prefix {@code declaredType} with the
     *       {@code package} declaration's value, if any.</li>
     *   <li>Otherwise return {@code declaredType} unchanged.</li>
     * </ol>
     *
     * <p>Returns {@code null} for blank input.</p>
     */
    static String resolveQualifiedTypeName(String declaredType, String source) {
        if (declaredType == null || declaredType.isBlank()) {
            return null;
        }
        if (declaredType.contains(".")) {
            return declaredType;
        }

        Map<String, String> imports = parseImports(source);
        String explicit = imports.get(declaredType);
        if (explicit != null) {
            return explicit;
        }

        String wildcardCandidate = null;
        for (Map.Entry<String, String> entry : imports.entrySet()) {
            if (entry.getKey().startsWith("*:")) {
                String candidate = entry.getValue() + "." + declaredType;
                if (tryLoad(candidate) != null) {
                    return candidate;
                }
                if (wildcardCandidate == null) {
                    wildcardCandidate = candidate;
                }
            }
        }
        if (wildcardCandidate != null) {
            return wildcardCandidate;
        }

        String javaLang = "java.lang." + declaredType;
        if (tryLoad(javaLang) != null) {
            return javaLang;
        }

        Matcher matcher = PACKAGE_PATTERN.matcher(source);
        if (matcher.find()) {
            return matcher.group(1) + "." + declaredType;
        }
        return declaredType;
    }

    /**
     * Strips array / varargs suffixes from a declared type name so it can be fed to
     * {@code Elements.getTypeElement(...)}.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code "String[]"}    → {@code "String"}</li>
     *   <li>{@code "int[][]"}     → {@code "int"}</li>
     *   <li>{@code "String..."}   → {@code "String"} (varargs are normalized through {@code "[]"})</li>
     *   <li>{@code "List<Foo>"}   → {@code "List<Foo>"} (generics are left intact;
     *       a caller that wants the raw name strips them separately)</li>
     *   <li>{@code null}          → {@code ""}</li>
     * </ul>
     */
    static String normalizeDeclaredType(String declaredType) {
        if (declaredType == null) {
            return "";
        }
        String normalized = declaredType.replace("...", "[]").trim();
        while (normalized.endsWith("[]")) {
            normalized = normalized.substring(0, normalized.length() - 2).trim();
        }
        return normalized;
    }

    /**
     * Returns the package declared at the top of {@code sourceText} (e.g.
     * {@code "com.foo.bar"} for {@code "package com.foo.bar;"}), or {@code null} when no
     * {@code package} declaration is present or the source is empty.
     */
    static String extractPackageName(String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            return null;
        }
        Matcher matcher = PACKAGE_PATTERN.matcher(sourceText);
        if (!matcher.find()) {
            return null;
        }
        String pkg = matcher.group(1);
        return pkg == null || pkg.isBlank() ? null : pkg;
    }

    /**
     * Resolves a (possibly simple) type name to a {@link TypeElement}, trying in order:
     * <ol>
     *   <li>direct {@link javax.lang.model.util.Elements#getTypeElement getTypeElement}
     *       (works for already-qualified names);</li>
     *   <li>{@link #resolveQualifiedTypeName} against {@code source}'s imports, then
     *       {@code getTypeElement} on the resolved FQCN;</li>
     *   <li>{@link #tryLoad} as a last-resort runtime probe, mapping the loaded
     *       {@link Class} back through {@code getTypeElement}.</li>
     * </ol>
     *
     * <p>Shared by callers that need to follow user-typed text — {@code @FXML(...)}
     * (annotation type), {@code Scene scene; scene.|} (member-access receiver) — back
     * to a javac element regardless of whether the user spelled it fully-qualified or
     * relies on imports. Returns {@code null} when none of the steps succeed.</p>
     */
    public static TypeElement resolveTypeElement(CompilationController controller,
                                                 String typeName, String source) {
        if (controller == null || typeName == null || typeName.isBlank()) {
            return null;
        }
        TypeElement direct = controller.getElements().getTypeElement(typeName);
        if (direct != null) {
            return direct;
        }
        String resolved = resolveQualifiedTypeName(typeName, source);
        if (resolved != null) {
            TypeElement viaImports = controller.getElements().getTypeElement(resolved);
            if (viaImports != null) {
                return viaImports;
            }
        }
        Class<?> runtime = tryLoad(resolved != null ? resolved : typeName);
        return runtime == null ? null : controller.getElements().getTypeElement(runtime.getName());
    }

    /**
     * Removes balanced {@code (…)} and {@code <…>} groups from {@code tail}. Record
     * headers like {@code (int x, String y)} and type arguments like {@code <T extends C>}
     * can contain commas and the {@code extends} keyword that would otherwise be picked up
     * by {@link #parseClauseNames}.
     */
    private static String stripBalanced(String tail) {
        StringBuilder out = new StringBuilder(tail.length());
        int paren = 0;
        int angle = 0;
        for (int i = 0; i < tail.length(); i++) {
            char ch = tail.charAt(i);
            if (ch == '(') { paren++; continue; }
            if (ch == ')') { if (paren > 0) paren--; continue; }
            if (ch == '<') { angle++; continue; }
            if (ch == '>') { if (angle > 0) angle--; continue; }
            if (paren == 0 && angle == 0) {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /**
     * Finds the {@code keyword} (must be one of {@code extends} / {@code implements} /
     * {@code permits}) in {@code tail} (already stripped of balanced groups) and returns
     * the list of simple type names listed after it, stopping at the next clause keyword
     * or at end of tail.
     */
    private static List<String> parseClauseNames(String tail, String keyword) {
        if (tail == null || tail.isBlank()) {
            return List.of();
        }
        Pattern keywordPattern = Pattern.compile("\\b" + keyword + "\\b");
        Matcher m = keywordPattern.matcher(tail);
        if (!m.find()) {
            return List.of();
        }
        int from = m.end();
        // Stop at the next clause keyword.
        int end = tail.length();
        for (String stopper : new String[] {"extends", "implements", "permits"}) {
            if (stopper.equals(keyword)) {
                continue;
            }
            Matcher sm = Pattern.compile("\\b" + stopper + "\\b").matcher(tail);
            if (sm.find(from) && sm.start() < end) {
                end = sm.start();
            }
        }
        String list = tail.substring(from, end);
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (String raw : list.split(",")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // Drop generic arguments, take the trailing simple name only.
            int lt = trimmed.indexOf('<');
            if (lt >= 0) {
                trimmed = trimmed.substring(0, lt).trim();
            }
            int dot = trimmed.lastIndexOf('.');
            if (dot >= 0 && dot < trimmed.length() - 1) {
                trimmed = trimmed.substring(dot + 1);
            }
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return List.copyOf(names);
    }
}
