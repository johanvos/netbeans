package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionCancellation;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionContext;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionItemKind;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionProvider;
import com.gluonhq.netbeans.nbfx.api.completion.CompletionTypeKind;
import com.gluonhq.netbeans.nbfx.api.completion.SimpleCompletionItem;
import com.gluonhq.netbeans.nbfx.editor.completion.support.JavaModuleInfoContextUtils.ModuleCompletionContext;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.SourceUtils;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;

import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.isInternalJdkPackage;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.isInvalidCompletionTypeName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.lowerPrefix;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.packageName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.prefixMismatch;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionItemUtils.simpleName;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaCompletionTypeUtils.extractQualifier;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaModuleInfoContextUtils.DIRECTIVE_KEYWORDS;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaModuleInfoContextUtils.HEADER_KEYWORDS;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaModuleInfoContextUtils.STATIC_KEYWORD;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaModuleInfoContextUtils.TO_KEYWORDS;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaModuleInfoContextUtils.TRANSITIVE_KEYWORD;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaModuleInfoContextUtils.WITH_KEYWORDS;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaPackagePathCollector.collectPackagePathItems;
import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaPackagePathCollector.collectTopLevelPackageItems;

/**
 * Semantic completion for {@code module-info.java} descriptors, backed by
 * {@link org.netbeans.api.java.source.JavaSource JavaSource} analysis.
 *
 * <p>{@link JavaModuleInfoContextUtils} classifies the caret; this class turns that classification
 * into proposals:</p>
 * <ul>
 *   <li><b>{@code requires |}</b> — the {@code static}/{@code transitive} modifier keywords (when not
 *       already present) followed by every observable module name
 *       ({@code java.base}, {@code javafx.controls}, project modules, …).</li>
 *   <li><b>{@code exports pkg to |}</b> / <b>{@code opens pkg to |}</b> — observable module names.</li>
 *   <li><b>{@code exports |}</b> / <b>{@code opens |}</b> — the project's own source packages.</li>
 *   <li><b>{@code uses |}</b>, <b>{@code provides |}</b> and <b>{@code provides X with |}</b> —
 *       service/implementation types. Under a package qualifier the popup drills down through
 *       sub-packages and their types; at a bare position it offers the top-level packages plus a flat
 *       type catalog ({@code java.lang} on a plain {@code Ctrl+Space}, the whole classpath on a
 *       second one) whose entries insert the <em>fully qualified</em> name — module descriptors have
 *       no imports.</li>
 * </ul>
 *
 * <p>Dotted operands (module names, package names, qualified types) are inserted relative to the
 * completion anchor: only the segment after the already-typed qualifier is inserted, so
 * {@code requires java.ba|} correctly completes to {@code requires java.base}.</p>
 */
public final class JavaModuleInfoCompletionQueries {

    private static final int PRIORITY_MODULE_KEYWORD = 10;
    private static final int PRIORITY_MODULE = 20;
    private static final int PRIORITY_SERVICE_TYPE = 30;

    private static final String JAVA_LANG_PACKAGE = "java.lang";

    private static final ResourceBundle BUNDLE =
            ResourceBundle.getBundle("com.gluonhq.netbeans.nbfx.editor.completion.CompletionUI");

    /** File extension and base name identifying the Java source files that define packages. */
    private static final String JAVA_EXTENSION = "java";
    static final String MODULE_INFO_NAME = "module-info";

    /** Separator between path segments in package and module names. */
    private static final char SEGMENT_SEPARATOR = '.';

    /** Trailing space appended after a committed keyword so the caret lands on the next operand. */
    private static final String KEYWORD_SUFFIX = " ";

    private static final EnumSet<ClassIndex.SearchScope> SEARCH_SCOPE =
            EnumSet.of(ClassIndex.SearchScope.SOURCE, ClassIndex.SearchScope.DEPENDENCIES);

    private JavaModuleInfoCompletionQueries() {
    }

    /**
     * Produces completion items for the current caret position inside a module descriptor. Returns an
     * empty list (never {@code null}) when the caret is not in a completable directive slot, so the
     * caller can short-circuit every {@code module-info.java} request through this method instead of the
     * regular Java member/identifier detectors.
     */
    public static List<CompletionItem> query(CompletionContext context, CompletionCancellation cancellation) {
        ModuleCompletionContext moduleContext = JavaModuleInfoContextUtils.classify(context);
        return switch (moduleContext.kind()) {
            case NONE -> List.of();
            case HEADER -> keywordItems(context, HEADER_KEYWORDS);
            case DIRECTIVE -> keywordItems(context, DIRECTIVE_KEYWORDS);
            case REQUIRES_MODULE -> requiresItems(context, moduleContext, cancellation);
            case TARGET_MODULE -> moduleItems(context, cancellation);
            case PACKAGE -> packageItems(context, cancellation);
            case TO_CLAUSE -> keywordItems(context, TO_KEYWORDS);
            case WITH_CLAUSE -> keywordItems(context, WITH_KEYWORDS);
            case SERVICE_TYPE -> serviceTypeItems(context, cancellation);
        };
    }

    private static List<CompletionItem> keywordItems(CompletionContext context, List<String> keywords) {
        List<CompletionItem> items = new ArrayList<>();
        String typed = typedName(context);
        for (String keyword : keywords) {
            addKeyword(items, context, typed, keyword);
        }
        return items;
    }

    private static List<CompletionItem> requiresItems(CompletionContext context,
                                                      ModuleCompletionContext moduleContext,
                                                      CompletionCancellation cancellation) {
        List<CompletionItem> items = new ArrayList<>();
        String typed = typedName(context);
        if (moduleContext.allowStatic()) {
            addKeyword(items, context, typed, STATIC_KEYWORD);
        }
        if (moduleContext.allowTransitive()) {
            addKeyword(items, context, typed, TRANSITIVE_KEYWORD);
        }
        items.addAll(moduleItems(context, cancellation));
        return items;
    }

    private static List<CompletionItem> moduleItems(CompletionContext context,
                                                    CompletionCancellation cancellation) {
        String typed = typedName(context);
        return JavaSemanticQueryRunner.runQuery(context, cancellation, (controller, items) -> {
            for (String name : SourceUtils.getModuleNames(controller, SEARCH_SCOPE)) {
                if (cancellation.isCancelled()) {
                    return;
                }
                if (name == null || name.isEmpty() || !startsWithIgnoreCase(name, typed)) {
                    continue;
                }
                items.add(moduleItem(name, remainderInsert(context, name)));
            }
        });
    }

    private static List<CompletionItem> packageItems(CompletionContext context,
                                                     CompletionCancellation cancellation) {
        boolean qualified = context.hasCharBeforeAnchor(SEGMENT_SEPARATOR);
        String qualifier = qualified
                ? extractQualifier(context.documentText(), context.anchorOffset() - 1) : "";
        if (qualifier == null) {
            return List.of();
        }
        String prefix = context.prefix();
        return JavaSemanticQueryRunner.runQuery(context, cancellation, (controller, items) -> {
            Set<String> packages = new TreeSet<>();
            collectSourcePackages(controller.getClasspathInfo().getClassPath(ClasspathInfo.PathKind.SOURCE),
                    packages, cancellation);
            // next path segment under the already-typed qualifier
            Set<String> segments = new TreeSet<>();
            for (String pkg : packages) {
                if (cancellation.isCancelled()) {
                    return;
                }
                String segment = nextSegment(pkg, qualifier);
                if (segment != null && !segment.isEmpty() && startsWithIgnoreCase(segment, prefix)) {
                    segments.add(segment);
                }
            }
            for (String segment : segments) {
                items.add(JavaCompletionItems.packageItem(segment, prefix));
            }
        });
    }

    /**
     * Returns the next path segment of {@code fullPackage} immediately after {@code qualifier}
     * (the package prefix already typed before the caret), or {@code null} when {@code fullPackage}
     * is not under {@code qualifier}. With an empty qualifier this is the top-level segment.
     */
    private static String nextSegment(String fullPackage, String qualifier) {
        String remainder;
        if (qualifier.isEmpty()) {
            remainder = fullPackage;
        } else if (fullPackage.startsWith(qualifier + SEGMENT_SEPARATOR)) {
            remainder = fullPackage.substring(qualifier.length() + 1);
        } else {
            return null;
        }
        int dot = remainder.indexOf(SEGMENT_SEPARATOR);
        return dot < 0 ? remainder : remainder.substring(0, dot);
    }

    /**
     * Enumerates the project's own package names by walking the source roots directly, rather than
     * querying the persistent {@link ClassIndex}: the standalone editor does not run NetBeans'
     * background source indexer, so {@code ClassIndex.getPackageNames(SOURCE)} would come back empty.
     * Only folders that actually contain a {@code .java} file are reported as packages.
     */
    private static void collectSourcePackages(ClassPath sourcePath, Set<String> packages,
                                              CompletionCancellation cancellation) {
        if (sourcePath == null) {
            return;
        }
        for (FileObject root : sourcePath.getRoots()) {
            collectSourcePackages(root, root, packages, cancellation);
        }
    }

    private static void collectSourcePackages(FileObject root, FileObject folder, Set<String> packages,
                                              CompletionCancellation cancellation) {
        if (cancellation.isCancelled()) {
            return;
        }
        boolean hasJava = false;
        for (FileObject child : folder.getChildren()) {
            if (child.isFolder()) {
                collectSourcePackages(root, child, packages, cancellation);
            } else if (!hasJava && JAVA_EXTENSION.equals(child.getExt())
                    && !MODULE_INFO_NAME.equals(child.getName())) {
                hasJava = true;
            }
        }
        if (hasJava && folder != root) {
            String relative = FileUtil.getRelativePath(root, folder);
            if (relative != null && !relative.isEmpty()) {
                packages.add(relative.replace('/', SEGMENT_SEPARATOR));
            }
        }
    }

    private static List<CompletionItem> serviceTypeItems(CompletionContext context,
                                                         CompletionCancellation cancellation) {
        boolean showAllItems = context.queryType() == CompletionProvider.COMPLETION_ALL_QUERY_TYPE;
        boolean qualified = context.hasCharBeforeAnchor(SEGMENT_SEPARATOR);
        String qualifier = qualified
                ? extractQualifier(context.documentText(), context.anchorOffset() - 1) : null;
        return JavaSemanticQueryRunner.runQuery(context, cancellation, (controller, items) -> {
            if (qualifier != null && !qualifier.isBlank()) {
                // Under a package qualifier: go through sub-packages and the types they contain.
                items.addAll(collectPackagePathItems(controller, qualifier, context.prefix(), false, cancellation));
            } else {
                // Bare position: offer top-level packages, plus a flat type catalog.
                // `uses`/`provides` operands must always be fully qualified.
                items.addAll(serviceTypeCatalog(controller, context, showAllItems, cancellation));
                items.addAll(collectTopLevelPackageItems(controller, context.prefix(), cancellation));
            }
        });
    }

    /**
     * Flat, fully-qualified type catalog for the bare {@code uses}/{@code provides} position: always the
     * {@code java.lang} types, plus the whole classpath on a second {@code Ctrl+Space}
     * ({@code showAllItems}). Each item inserts the fully qualified name.
     */
    private static List<CompletionItem> serviceTypeCatalog(CompilationController controller,
                                                           CompletionContext context, boolean showAllItems,
                                                           CompletionCancellation cancellation) {
        String prefix = context.prefix();
        String lowerPrefix = lowerPrefix(prefix);
        Map<String, CompletionItem> types = new LinkedHashMap<>();

        PackageElement javaLang = controller.getElements().getPackageElement(JAVA_LANG_PACKAGE);
        if (javaLang != null) {
            for (Element child : javaLang.getEnclosedElements()) {
                if (cancellation.isCancelled()) {
                    return List.of();
                }
                if (child instanceof TypeElement type) {
                    addServiceType(context, type, lowerPrefix, types);
                }
            }
        }

        if (showAllItems) {
            Set<ElementHandle<TypeElement>> handles = controller.getClasspathInfo().getClassIndex()
                    .getDeclaredTypes(prefix == null ? "" : prefix, ClassIndex.NameKind.CASE_INSENSITIVE_PREFIX, SEARCH_SCOPE);
            if (handles != null) {
                for (ElementHandle<TypeElement> handle : handles) {
                    if (cancellation.isCancelled()) {
                        return List.of();
                    }
                    addServiceType(context, handle.getQualifiedName(), handleKindName(handle), lowerPrefix, types);
                }
            }
        }
        return new ArrayList<>(types.values());
    }

    private static void addServiceType(CompletionContext context, TypeElement type,
                                       String lowerPrefix, Map<String, CompletionItem> types) {
        addServiceType(context, type.getQualifiedName().toString(), type.getKind().name(), lowerPrefix, types);
    }

    private static void addServiceType(CompletionContext context, String fqcn, String kindName,
                                       String lowerPrefix, Map<String, CompletionItem> types) {
        if (fqcn == null || fqcn.isBlank() || fqcn.indexOf('$') >= 0) {
            return;
        }
        String packageName = packageName(fqcn);
        if (isInternalJdkPackage(packageName)) {
            return;
        }
        String simpleName = simpleName(fqcn);
        if (simpleName.isBlank() || isInvalidCompletionTypeName(simpleName) || prefixMismatch(simpleName, lowerPrefix)) {
            return;
        }
        types.putIfAbsent(fqcn, serviceTypeItem(context, fqcn, kindName));
    }

    private static String handleKindName(ElementHandle<TypeElement> handle) {
        return handle.getKind() == null ? "" : handle.getKind().name();
    }

    // Item factories

    private static void addKeyword(List<CompletionItem> items, CompletionContext context,
                                   String typed, String keyword) {
        if (!startsWithIgnoreCase(keyword, typed)) {
            return;
        }
        items.add(keywordItem(keyword, remainderInsert(context, keyword) + KEYWORD_SUFFIX));
    }

    private static CompletionItem keywordItem(String keyword, String insert) {
        return new SimpleCompletionItem(keyword, insert, keyword, PRIORITY_MODULE_KEYWORD,
                keyword, BUNDLE.getString("completion.popup.lexer.keyword"),
                CompletionItemKind.KEYWORD, CompletionTypeKind.OTHER, 0, false, false);
    }

    private static CompletionItem moduleItem(String name, String insert) {
        return new SimpleCompletionItem(name, insert, name, PRIORITY_MODULE,
                name, BUNDLE.getString("completion.popup.module"),
                CompletionItemKind.MODULE, CompletionTypeKind.OTHER, 0, false, false);
    }

    /**
     * Type proposal for a bare {@code uses}/{@code provides} operand. The label and sort key are the
     * simple name (so the popup reads and filters naturally), but the inserted text is the fully
     * qualified name relative to the anchor, and the package is shown as {@code rightText} to
     * disambiguate identically-named types.
     */
    private static CompletionItem serviceTypeItem(CompletionContext context, String fqcn, String kindName) {
        String simpleName = simpleName(fqcn);
        String packageName = packageName(fqcn);
        return new SimpleCompletionItem(simpleName, remainderInsert(context, fqcn), simpleName,
                PRIORITY_SERVICE_TYPE, simpleName, packageName,
                CompletionItemKind.TYPE, CompletionTypeKind.from(kindName), 0, false, false);
    }

    // Anchor-relative insertion helpers

    /**
     * The dotted operand text from its first segment up to the caret, e.g. {@code "java.ba"} for
     * {@code requires java.ba|}. Used to filter candidates by everything the user has typed, not just
     * the last segment (which is all {@link CompletionContext#prefix()} would give).
     */
    private static String typedName(CompletionContext context) {
        String source = context.documentText();
        return source.substring(nameStart(context), context.caretOffset());
    }

    /**
     * The text to insert for {@code candidate}, relative to the completion anchor: only the part after
     * the already-typed qualifier (the segments before the anchor) is inserted, so replacing
     * {@code [anchor, caret)} reconstructs the full candidate.
     */
    private static String remainderInsert(CompletionContext context, String candidate) {
        int qualifierLength = context.anchorOffset() - nameStart(context);
        return qualifierLength > 0 && qualifierLength <= candidate.length()
                ? candidate.substring(qualifierLength) : candidate;
    }

    /** Start of the dotted operand containing the anchor (scanning back over identifier chars and dots). */
    private static int nameStart(CompletionContext context) {
        String source = context.documentText();
        int start = context.anchorOffset();
        while (start > 0) {
            char c = source.charAt(start - 1);
            if (Character.isJavaIdentifierPart(c) || c == SEGMENT_SEPARATOR) {
                start--;
            } else {
                break;
            }
        }
        return start;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }
}
