package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.gluonhq.netbeans.nbfx.api.completion.CompletionItem;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Utilities and helper methods
 */
public class JavaCompletionItemUtils {

    private JavaCompletionItemUtils() {
    }

    // Item-map utilities

    /**
     * Inserts {@code item} into the {@code items} map, key is {@code kind#leftText#rightText},
     * keeping the entry with the highest ranking (lowest {@code sortPriority}).
     */
    static void putItem(Map<String, CompletionItem> items, CompletionItem item) {
        String key = item.kind() + "#" + item.leftText() + "#" + item.rightText();
        CompletionItem previous = items.get(key);
        if (previous == null || item.sortPriority() < previous.sortPriority() ||
                (item.sortPriority() == previous.sortPriority() && !item.deprecated() && previous.deprecated())) {
            items.put(key, item);
        }
    }

    /** Returns {@code prefix.toLowerCase()}, or {@code ""} for null/blank. */
    static String lowerPrefix(String prefix) {
        return prefix == null || prefix.isBlank() ? "" : prefix.toLowerCase(Locale.ROOT);
    }

    /**
     * Returns {@code true} when {@code value} does <em>not</em> start with
     * {@code lowerPrefix} case-insensitively. A blank prefix never mismatches.
     */
    static boolean prefixMismatch(String value, String lowerPrefix) {
        return !lowerPrefix.isBlank() && !value.toLowerCase(Locale.ROOT).startsWith(lowerPrefix);
    }

    // Name parsing helpers

    /** Returns the package part of {@code fqcn} (everything before the last dot), or {@code ""} for unqualified names. */
    static String packageName(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) {
            return "";
        }
        int lastDot = fqcn.lastIndexOf('.');
        return lastDot > 0 ? fqcn.substring(0, lastDot) : "";
    }

    /** Returns the simple name part of {@code fqcn} (everything after the last dot). */
    static String simpleName(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) {
            return fqcn;
        }
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    /** Strips generic arguments and returns the simple type name — e.g. {@code "List<String>" -> "List"}. */
    static String simpleTypeName(String typeName) {
        int generic = typeName != null ? typeName.indexOf('<') : -1;
        return simpleName(generic >= 0 ? typeName.substring(0, generic) : typeName);
    }

    /** Returns the first dotted segment of {@code packageName} (e.g. {@code "javafx"} for {@code "javafx.scene"}). */
    static String topLevelPackage(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return "";
        }
        int dot = packageName.indexOf('.');
        return dot > 0 ? packageName.substring(0, dot) : packageName;
    }

    /**
     * Returns {@code true} for JDK-internal package roots ({@code sun}, {@code com.sun},
     * {@code jdk}, {@code apple}) that the popup should hide from the user.
     */
    static boolean isInternalJdkPackage(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return false;
        }
        // Match both fully-qualified ("sun.misc") and bare top-level segments ("sun")
        // so the same filter works for the dotted-path collector and the top-level
        // import-package collector.
        return matchesInternalRoot(packageName, "sun") || matchesInternalRoot(packageName, "com.sun") ||
                matchesInternalRoot(packageName, "jdk") || matchesInternalRoot(packageName, "apple");
    }

    private static boolean matchesInternalRoot(String packageName, String root) {
        return packageName != null && (packageName.equals(root) || packageName.startsWith(root + "."));
    }

    /**
     * Returns {@code true} for blank names, {@code package-info} / {@code module-info},
     * or any string that isn't a legal Java identifier.
     */
    static boolean isInvalidCompletionTypeName(String simpleName) {
        if (simpleName == null || simpleName.isBlank() ||
                "package-info".equals(simpleName) || "module-info".equals(simpleName) ||
                !Character.isJavaIdentifierStart(simpleName.charAt(0))) {
            return true;
        }
        for (int i = 1; i < simpleName.length(); i++) {
            if (!Character.isJavaIdentifierPart(simpleName.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    // Element metadata

    /** Converts a {@link Modifier} set into the equivalent {@link java.lang.reflect.Modifier} bitmask. */
    static int toModifierBits(Set<Modifier> modifiers) {
        int bits = 0;
        if (modifiers != null) {
            if (modifiers.contains(Modifier.PUBLIC)) bits |= java.lang.reflect.Modifier.PUBLIC;
            if (modifiers.contains(Modifier.PROTECTED)) bits |= java.lang.reflect.Modifier.PROTECTED;
            if (modifiers.contains(Modifier.PRIVATE)) bits |= java.lang.reflect.Modifier.PRIVATE;
            if (modifiers.contains(Modifier.STATIC)) bits |= java.lang.reflect.Modifier.STATIC;
            if (modifiers.contains(Modifier.FINAL)) bits |= java.lang.reflect.Modifier.FINAL;
            if (modifiers.contains(Modifier.ABSTRACT)) bits |= java.lang.reflect.Modifier.ABSTRACT;
        }
        return bits;
    }

    /** Null-safe {@link Elements#isDeprecated} wrapper. */
    static boolean isDeprecated(Elements elements, Element element) {
        return elements != null && element != null && elements.isDeprecated(element);
    }

    /**
     * Returns {@code true} when {@code type} cannot back a member-completion popup —
     * either {@code null} or one of the "non-expression" kinds that javac uses to signal
     * unresolved or non-value contexts ({@link TypeKind#ERROR}, {@link TypeKind#NONE},
     * {@link TypeKind#PACKAGE}). Used by the member query to decide whether to fall back
     * to a text-based qualifier resolution.
     */
    static boolean isInvalidExpressionType(TypeMirror type) {
        if (type == null) {
            return true;
        }
        TypeKind k = type.getKind();
        return k == TypeKind.ERROR || k == TypeKind.NONE || k == TypeKind.PACKAGE;
    }

}
