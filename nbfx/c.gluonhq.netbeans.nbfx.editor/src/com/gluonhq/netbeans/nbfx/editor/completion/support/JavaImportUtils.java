package com.gluonhq.netbeans.nbfx.editor.completion.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes the text edit needed to add a missing single-type {@code import} declaration when a
 * fully-qualified type is committed from the completion popup by its simple name.
 *
 * <p>The editor inserts only the simple name of a classpath type (e.g. {@code Scene} for a
 * {@code new Scene(...)} proposal), which fails semantic analysis with a "cannot find symbol" error
 * when the type is not yet imported. This utility decides whether an import is required and where it
 * should be placed so the resulting import block stays alphabetically sorted.
 *
 * <p>All logic is plain-text based (no semantic model), which keeps it deterministic and unit-testable
 * independently of a running {@code JavaSource}.
 */
public final class JavaImportUtils {

    // Matches a single import declaration (static or not), capturing the optional `static ` modifier
    // (group 1) and the imported name/FQN (group 2, allowing a trailing `.*` wildcard).
    private static final Pattern IMPORT_LINE =
            Pattern.compile("(?m)^[ \\t]*import[ \\t]+(static[ \\t]+)?([\\w.*]+)[ \\t]*;");

    private static final String JAVA_LANG_PACKAGE = "java.lang";
    private static final String LINE_SEPARATOR = "\n";

    private JavaImportUtils() {
    }

    /**
     * A single text insertion: the {@code text} must be inserted at global character {@code offset}
     * in the source (nothing is removed).
     */
    public record ImportEdit(int offset, String text) {
    }

    /**
     * Computes the edit that adds {@code import fqcn;} to {@code source}, or an empty optional when no
     * import is needed. No import is produced when the type has no package, is in {@code java.lang}, is
     * in the same package as the edited file, is already imported (explicitly or via a wildcard import
     * of its package), or when its simple name is already bound by another import.
     *
     * @param source the current full source text
     * @param fqcn   the fully-qualified name of the committed type (dotted, no {@code $})
     * @return the insertion to apply, or empty when the import is unnecessary or cannot be determined
     */
    public static Optional<ImportEdit> computeImport(String source, String fqcn) {
        if (source == null || fqcn == null) {
            return Optional.empty();
        }
        String normalized = fqcn.trim();
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == normalized.length() - 1 || normalized.indexOf('$') >= 0) {
            return Optional.empty();
        }
        String packageName = normalized.substring(0, lastDot);
        String simpleName = normalized.substring(lastDot + 1);
        if (JAVA_LANG_PACKAGE.equals(packageName)) {
            return Optional.empty();
        }
        if (packageName.equals(JavaCompletionTypeUtils.extractPackageName(source))) {
            return Optional.empty();
        }
        Map<String, String> imports = JavaCompletionTypeUtils.parseImports(source);
        if (imports.containsValue(normalized)
                || imports.containsKey("*:" + packageName)
                || imports.containsKey(simpleName)) {
            return Optional.empty();
        }
        return Optional.of(placeImport(source, normalized));
    }

    private static ImportEdit placeImport(String source, String fqcn) {
        List<ImportDeclaration> imports = scanImports(source);
        List<ImportDeclaration> nonStatic = imports.stream().filter(imp -> !imp.isStatic()).toList();

        if (!nonStatic.isEmpty()) {
            for (ImportDeclaration existing : nonStatic) {
                if (existing.name().compareTo(fqcn) > 0) {
                    return new ImportEdit(existing.start(), "import " + fqcn + ";" + LINE_SEPARATOR);
                }
            }
            ImportDeclaration last = nonStatic.get(nonStatic.size() - 1);
            return new ImportEdit(last.end(), LINE_SEPARATOR + "import " + fqcn + ";");
        }

        if (!imports.isEmpty()) {
            // Only static imports exist: place the new import above the static block, conventionally first.
            ImportDeclaration firstStatic = imports.get(0);
            return new ImportEdit(firstStatic.start(),
                    "import " + fqcn + ";" + LINE_SEPARATOR + LINE_SEPARATOR);
        }

        int packageEnd = packageDeclarationEnd(source);
        if (packageEnd >= 0) {
            return new ImportEdit(packageEnd,
                    LINE_SEPARATOR + LINE_SEPARATOR + "import " + fqcn + ";");
        }
        return new ImportEdit(0, "import " + fqcn + ";" + LINE_SEPARATOR + LINE_SEPARATOR);
    }

    private static List<ImportDeclaration> scanImports(String source) {
        List<ImportDeclaration> declarations = new ArrayList<>();
        Matcher matcher = IMPORT_LINE.matcher(source);
        while (matcher.find()) {
            declarations.add(new ImportDeclaration(
                    matcher.start(), matcher.end(), matcher.group(1) != null, matcher.group(2)));
        }
        return declarations;
    }

    /** Returns the offset right after the {@code ;} of the package declaration, or {@code -1} when absent. */
    private static int packageDeclarationEnd(String source) {
        Matcher matcher = Pattern.compile("(?m)^[ \\t]*package[ \\t]+[\\w.]+[ \\t]*;").matcher(source);
        return matcher.find() ? matcher.end() : -1;
    }

    private record ImportDeclaration(int start, int end, boolean isStatic, String name) {
    }
}
