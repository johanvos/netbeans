package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import org.netbeans.api.java.source.CompilationController;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.LinkedHashMap;
import java.util.List;

import static com.gluonhq.netbeans.nbfx.editor.completion.support.JavaSourceTextScanner.skipWhitespaceBackward;

/**
 * Method-invocation and constructor overload resolution helpers shared by the
 * argument-list semantic queries.
 */
final class JavaCompletionInvocationUtils {

    /**
     * Resolved invocation/constructor overload set with its active argument index and
     * (optionally) the receiver {@link DeclaredType} needed for generic-parameter
     * substitution. {@code receiverType} is {@code null} for the textual constructor
     * fallback path where no receiver is involved.
     */
    record InvocationResolutionRecord(List<ExecutableElement> overloads, int argIndex, DeclaredType receiverType) {
        InvocationResolutionRecord(int argIndex, List<ExecutableElement> overloads) {
            this(overloads, argIndex, null);
        }
    }

    private JavaCompletionInvocationUtils() {
    }

    // Public entry points

    /**
     * Returns the zero-based argument index the caret sits in for the invocation /
     * constructor call at {@code invocationPath}, or {@code -1} when the caret is
     * outside the argument list or the leaf is not an invocation.
     */
    static int resolveArgumentIndex(TreePath invocationPath,
                                    CompilationController controller,
                                    int caretOffset) {
        if (invocationPath == null) {
            return -1;
        }
        if (invocationPath.getLeaf() instanceof MethodInvocationTree invocation) {
            return resolveArgumentIndex(invocation, controller, caretOffset);
        }
        if (invocationPath.getLeaf() instanceof NewClassTree newClass) {
            return resolveArgumentIndex(newClass, controller, caretOffset);
        }
        return -1;
    }

    /**
     * Returns every overload of the method / constructor at {@code invocationPath}
     * whose parameter list can satisfy {@code argIndex}. Empty when the leaf is not
     * an invocation or no compatible overload exists.
     */
    static List<ExecutableElement> resolveInvocationOverloads(CompilationController controller,
                                                              TreePath invocationPath,
                                                              int argIndex) {
        if (invocationPath == null) {
            return List.of();
        }
        if (invocationPath.getLeaf() instanceof MethodInvocationTree invocation) {
            return resolveInvocationOverloads(controller, invocationPath, invocation, argIndex);
        }
        if (invocationPath.getLeaf() instanceof NewClassTree newClass) {
            return resolveConstructorOverloads(controller, invocationPath, newClass, argIndex);
        }
        return List.of();
    }

    /**
     * Textual fallback for the constructor-overload lookup used when the parser
     * couldn't produce a usable {@link NewClassTree}. Walks back from
     * {@code caretOffset} looking for {@code new SomeType(} and, if the type
     * resolves to a {@link TypeElement}, returns its constructors that accept an
     * argument at the caret's textual index. Returns {@code null} when no usable
     * {@code new …(} pattern is found.
     */
    static InvocationResolutionRecord resolveConstructorOverloadsFallback(CompilationController controller,
                                                                          String source,
                                                                          int caretOffset) {
        if (source == null || source.isBlank() || caretOffset <= 0) {
            return null;
        }
        int anchor = Math.clamp(caretOffset, 0, source.length());
        int nesting = 0;
        for (int i = anchor - 1; i >= 0; i--) {
            char ch = source.charAt(i);
            if (ch == ')') {
                nesting++;
                continue;
            }
            if (ch != '(') {
                continue;
            }
            if (nesting > 0) {
                nesting--;
                continue;
            }

            int nameEnd = skipWhitespaceBackward(source, i - 1);
            if (nameEnd < 0 || !Character.isJavaIdentifierPart(source.charAt(nameEnd))) {
                continue;
            }
            int nameStart = nameEnd;
            while (nameStart >= 0 && Character.isJavaIdentifierPart(source.charAt(nameStart))) {
                nameStart--;
            }
            String constructorType = source.substring(nameStart + 1, nameEnd + 1);

            int beforeName = skipWhitespaceBackward(source, nameStart);
            if (beforeName < 2 || !Character.isJavaIdentifierPart(source.charAt(beforeName))) {
                continue;
            }
            int keywordStart = beforeName;
            while (keywordStart >= 0 && Character.isJavaIdentifierPart(source.charAt(keywordStart))) {
                keywordStart--;
            }
            String keyword = source.substring(keywordStart + 1, beforeName + 1);
            if (!"new".equals(keyword)) {
                continue;
            }

            TypeElement ownerType = resolveTypeElementBySimpleName(controller, constructorType, source);
            if (ownerType == null) {
                return null;
            }
            int argIndex = JavaSourceTextScanner.countCommaArgIndex(source, i + 1, anchor);
            if (argIndex < 0) {
                return null;
            }

            LinkedHashMap<String, ExecutableElement> ctors = new LinkedHashMap<>();
            collectConstructorsForArg(ownerType, argIndex, ctors);
            if (ctors.isEmpty()) {
                return null;
            }
            return new InvocationResolutionRecord(argIndex, ctors.values().stream().toList());
        }
        return null;
    }

    /**
     * Returns the parameter type {@code method} expects at {@code argIndex}, unwrapping
     * the varargs component type for the trailing slot ({@code Node[]} → {@code Node}).
     * Returns {@code null} when {@code argIndex} is out of range.
     */
    static TypeMirror resolveParameterTypeForIndex(ExecutableElement method, int argIndex) {
        List<? extends VariableElement> parameters = method.getParameters();
        if (parameters.isEmpty()) {
            return null;
        }
        boolean varArgs = method.isVarArgs();
        if (argIndex < parameters.size()) {
            TypeMirror type = parameters.get(argIndex).asType();
            // Inside a varargs slot the caller passes the component type, so unwrap
            // `Node[]` -> `Node` for `new VBox(|)` etc.
            if (varArgs && argIndex == parameters.size() - 1 && type.getKind() == TypeKind.ARRAY) {
                return ((ArrayType) type).getComponentType();
            }
            return type;
        }
        if (varArgs) {
            TypeMirror last = parameters.getLast().asType();
            return last.getKind() == TypeKind.ARRAY ? ((ArrayType) last).getComponentType() : last;
        }
        return null;
    }

    // Argument index resolution

    private static int resolveArgumentIndex(MethodInvocationTree invocation,
                                            CompilationController controller, int caretOffset) {
        return resolveArgumentIndex(controller, caretOffset,
                invocation.getArguments(), invocation.getMethodSelect());
    }

    private static int resolveArgumentIndex(NewClassTree newClass,
                                            CompilationController controller, int caretOffset) {
        return resolveArgumentIndex(controller, caretOffset,
                newClass.getArguments(), newClass.getIdentifier());
    }

    private static int resolveArgumentIndex(CompilationController controller, int caretOffset,
                                            List<? extends ExpressionTree> args, Tree leadingTree) {
        SourcePositions positions = controller.getTrees().getSourcePositions();
        CompilationUnitTree unit = controller.getCompilationUnit();
        long leadingEnd = elementEndWithIdentifierFallback(positions, unit, leadingTree,
                leadingTree == null ? null : leadingTree.toString());
        if (args.isEmpty()) {
            if (leadingEnd >= 0 && caretOffset <= leadingEnd) {
                return -1;
            }
            return 0;
        }
        for (int i = 0; i < args.size(); i++) {
            long start = positions.getStartPosition(unit, args.get(i));
            long end = positions.getEndPosition(unit, args.get(i));
            if (start < 0) continue;
            if (caretOffset < start || end < 0 || caretOffset <= end + 1) {
                return i;
            }
        }
        if (leadingEnd >= 0 && caretOffset >= leadingEnd) {
            return args.size() - 1;
        }
        return -1;
    }

    // Argument index resolution

    private static List<ExecutableElement> resolveInvocationOverloads(CompilationController controller,
                                                                      TreePath invocationPath,
                                                                      MethodInvocationTree invocation,
                                                                      int argIndex) {
        ExpressionTree methodSelect = invocation.getMethodSelect();
        String methodName = extractInvokedMethodName(methodSelect);
        if (methodName == null) {
            return List.of();
        }

        TypeElement ownerType = resolveInvocationOwnerType(controller, invocationPath, methodSelect);
        LinkedHashMap<String, ExecutableElement> methods = new LinkedHashMap<>();

        Element resolved = controller.getTrees().getElement(invocationPath);
        if (resolved instanceof ExecutableElement executable
                && executable.getSimpleName().contentEquals(methodName)
                && resolveParameterTypeForIndex(executable, argIndex) != null) {
            methods.put(methodSignatureKey(executable), executable);
        }

        if (ownerType != null) {
            for (Element element : controller.getElements().getAllMembers(ownerType)) {
                if (element.getKind() != ElementKind.METHOD) {
                    continue;
                }
                ExecutableElement method = (ExecutableElement) element;
                if (!method.getSimpleName().contentEquals(methodName)) {
                    continue;
                }
                if (resolveParameterTypeForIndex(method, argIndex) == null) {
                    continue;
                }
                methods.putIfAbsent(methodSignatureKey(method), method);
            }
        }

        return methods.values().stream().toList();
    }

    private static List<ExecutableElement> resolveConstructorOverloads(CompilationController controller,
                                                                       TreePath invocationPath,
                                                                       NewClassTree newClass,
                                                                       int argIndex) {
        LinkedHashMap<String, ExecutableElement> ctors = new LinkedHashMap<>();

        Element resolved = controller.getTrees().getElement(invocationPath);
        if (resolved instanceof ExecutableElement executable
                && executable.getKind() == ElementKind.CONSTRUCTOR
                && resolveParameterTypeForIndex(executable, argIndex) != null) {
            ctors.put(methodSignatureKey(executable), executable);
        }

        TypeElement ownerType = resolveConstructorOwnerType(controller, invocationPath, newClass);
        if (ownerType != null) {
            collectConstructorsForArg(ownerType, argIndex, ctors);
        }

        return ctors.values().stream().toList();
    }

    private static TypeElement resolveConstructorOwnerType(CompilationController controller,
                                                           TreePath invocationPath,
                                                           NewClassTree newClass) {
        TreePath identifierPath = new TreePath(invocationPath, newClass.getIdentifier());
        Element identifierElement = controller.getTrees().getElement(identifierPath);
        if (identifierElement instanceof TypeElement typeElement) {
            return typeElement;
        }
        TypeMirror identifierType = controller.getTrees().getTypeMirror(identifierPath);
        TypeElement byTypeMirror = JavaCompletionContextUtils.asTypeElement(identifierType);
        if (byTypeMirror != null) {
            return byTypeMirror;
        }
        if (newClass.getIdentifier() instanceof IdentifierTree identifierTree) {
            return resolveTypeElementBySimpleName(controller, identifierTree.getName().toString(), null);
        }
        return null;
    }

    private static TypeElement resolveTypeElementBySimpleName(CompilationController controller,
                                                              String simpleName,
                                                              String sourceTextFallback) {
        if (simpleName == null || simpleName.isBlank()) {
            return null;
        }

        String normalized = JavaCompletionTypeUtils.normalizeDeclaredType(simpleName);
        TypeElement direct = controller.getElements().getTypeElement(normalized);
        if (direct != null) {
            return direct;
        }

        for (ImportTree importTree : controller.getCompilationUnit().getImports()) {
            if (importTree.isStatic()) {
                continue;
            }
            String importText = importTree.getQualifiedIdentifier().toString();
            if (importText.endsWith("." + normalized)) {
                TypeElement explicit = controller.getElements().getTypeElement(importText);
                if (explicit != null) {
                    return explicit;
                }
                continue;
            }
            if (importText.endsWith(".*")) {
                String pkg = importText.substring(0, importText.length() - 2);
                TypeElement wildcard = controller.getElements().getTypeElement(pkg + "." + normalized);
                if (wildcard != null) {
                    return wildcard;
                }
            }
        }

        if (controller.getCompilationUnit().getPackageName() != null) {
            String packageName = controller.getCompilationUnit().getPackageName().toString();
            if (!packageName.isBlank()) {
                TypeElement samePackage = controller.getElements().getTypeElement(packageName + "." + normalized);
                if (samePackage != null) {
                    return samePackage;
                }
            }
        }

        TypeElement javaLang = controller.getElements().getTypeElement("java.lang." + normalized);
        if (javaLang != null) {
            return javaLang;
        }

        if (sourceTextFallback != null) {
            String resolvedName = JavaCompletionTypeUtils.resolveQualifiedTypeName(normalized, sourceTextFallback);
            if (resolvedName != null) {
                return controller.getElements().getTypeElement(resolvedName);
            }
        }
        return null;
    }

    private static String extractInvokedMethodName(ExpressionTree methodSelect) {
        if (methodSelect instanceof MemberSelectTree memberSelect) {
            return memberSelect.getIdentifier().toString();
        }
        if (methodSelect instanceof IdentifierTree identifier) {
            return identifier.getName().toString();
        }
        return null;
    }

    private static TypeElement resolveInvocationOwnerType(CompilationController controller,
                                                          TreePath invocationPath,
                                                          ExpressionTree methodSelect) {
        if (methodSelect instanceof MemberSelectTree memberSelect) {
            TreePath expressionPath = new TreePath(invocationPath, memberSelect.getExpression());
            return JavaCompletionContextUtils.asTypeElement(controller.getTrees().getTypeMirror(expressionPath));
        }
        if (methodSelect instanceof IdentifierTree) {
            TreePath enclosingClass = findEnclosingClassPath(invocationPath);
            if (enclosingClass == null) {
                return null;
            }
            Element type = controller.getTrees().getElement(enclosingClass);
            if (type instanceof TypeElement ownerType) {
                return ownerType;
            }
        }
        return null;
    }

    private static String methodSignatureKey(ExecutableElement method) {
        String params = method.getParameters().stream()
                .map(param -> param.asType().toString())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return method.getSimpleName() + "(" + params + "):" + method.getReturnType();
    }

    private static void collectConstructorsForArg(TypeElement ownerType,
                                                  int argIndex,
                                                  LinkedHashMap<String, ExecutableElement> ctors) {
        for (Element element : ownerType.getEnclosedElements()) {
            if (element.getKind() != ElementKind.CONSTRUCTOR) {
                continue;
            }
            ExecutableElement ctor = (ExecutableElement) element;
            if (resolveParameterTypeForIndex(ctor, argIndex) == null) {
                continue;
            }
            ctors.putIfAbsent(methodSignatureKey(ctor), ctor);
        }
    }

    // Low-level text/tree helpers

    private static long elementEndWithIdentifierFallback(SourcePositions positions,
                                                         CompilationUnitTree unit,
                                                         Tree tree,
                                                         String identifierText) {
        if (tree == null) {
            return -1;
        }
        long end = positions.getEndPosition(unit, tree);
        if (end >= 0) {
            return end;
        }
        long start = positions.getStartPosition(unit, tree);
        if (start < 0 || identifierText == null || identifierText.isBlank()) {
            return -1;
        }
        return start + identifierText.length();
    }

    private static TreePath findEnclosingClassPath(TreePath path) {
        for (TreePath current = path; current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof ClassTree) {
                return current;
            }
        }
        return null;
    }
}
