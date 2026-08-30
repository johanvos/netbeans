package com.gluonhq.netbeans.nbfx.editor.processor.semantics.detector;

import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.Stack;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import org.netbeans.api.java.lexer.JavaTokenId;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.api.lexer.TokenSequence;

/**
 * Detects method return points and throw sources, when the caret is on a method's return type, on one of its
 * declared {@code throws} exceptions, or on a {@code catch} parameter's type.
 *
 * <p>Ported from NetBeans' {@code org.netbeans.modules.java.editor.base.semantic.MethodExitDetector}.</p>
 */
final class MethodReturnDetector extends TreePathScanner<Boolean, Stack<Tree>> {

    private final CompilationInfo info;
    private List<HighlightSpan> highlights;
    private boolean doExitPoints;
    private Stack<Map<TypeMirror, List<Tree>>> exceptions2HighlightsStack;

    /**
     * {@code true} if the caret paths make either of the trigger
     * cases handled by {@link #process(TreePath, int)} possible.
     */
    static boolean isPotentialMethodReturnSite(TreePath typePath) {
        if (typePath == null) {
            return false;
        }
        TreePath parentPath = typePath.getParentPath();
        if (parentPath == null) {
            return false;
        }
        Tree.Kind parentKind = parentPath.getLeaf().getKind();
        return parentKind == Tree.Kind.METHOD
                || parentKind == Tree.Kind.VARIABLE
                || parentKind == Tree.Kind.UNION_TYPE;
    }

    MethodReturnDetector(CompilationInfo info) {
        this.info = info;
    }

    List<HighlightSpan> process(TreePath typePath, int caretOffset) {
        if (typePath == null) {
            return null;
        }
        TreePath parentPath = typePath.getParentPath();
        if (parentPath == null) {
            return null;
        }
        CompilationUnitTree compilationUnit = info.getCompilationUnit();
        SourcePositions positions = info.getTrees().getSourcePositions();

        if (parentPath.getLeaf().getKind() == Tree.Kind.METHOD) {
            MethodTree methodTree = (MethodTree) parentPath.getLeaf();
            // 1.1 Add all returns
            Tree returnType = methodTree.getReturnType();
            if (returnType != null && caretIsInTreeRange(compilationUnit, positions, returnType, caretOffset)) {
                List<HighlightSpan> spans = run(parentPath, null);
                if (spans != null && !spans.isEmpty()) {
                    return spans;
                }
            }
            // 1.2 Add all exceptions that are thrown by this method
            for (Tree exceptionTree : methodTree.getThrows()) {
                if (caretIsInTreeRange(compilationUnit, positions, exceptionTree, caretOffset)) {
                    List<HighlightSpan> spans = run(parentPath, Collections.singletonList(exceptionTree));
                    if (spans != null && !spans.isEmpty()) {
                        return spans;
                    }
                }
            }
        }

        // 1.3 Catch parameter type — caret on `catch (Foo e)` or on a single branch of a multi-catch `Foo | Bar`.
        Tree parent = parentPath.getLeaf();
        TreePath catchTypeRoot = null;
        if (parent.getKind() == Tree.Kind.UNION_TYPE
                && parentPath.getParentPath() != null
                && parentPath.getParentPath().getLeaf().getKind() == Tree.Kind.VARIABLE
                && parentPath.getParentPath().getParentPath() != null
                && parentPath.getParentPath().getParentPath().getLeaf().getKind() == Tree.Kind.CATCH) {
            catchTypeRoot = typePath;
        } else if (parent.getKind() == Tree.Kind.VARIABLE
                && parentPath.getParentPath() != null
                && parentPath.getParentPath().getLeaf().getKind() == Tree.Kind.CATCH) {
            catchTypeRoot = typePath;
        }
        if (catchTypeRoot != null) {
            TreePath tryPath = info.getTreeUtilities().getPathElementOfKind(Tree.Kind.TRY, catchTypeRoot);
            if (tryPath != null) {
                TryTree tryTree = (TryTree) tryPath.getLeaf();
                TreePath tryBlock = new TreePath(tryPath, tryTree.getBlock());
                List<HighlightSpan> spans = run(tryBlock, Collections.singletonList(catchTypeRoot.getLeaf()));
                if (spans != null && !spans.isEmpty()) {
                    return spans;
                }
            }
        }
        return null;
    }

    private List<HighlightSpan> run(TreePath methodOrBlock, Collection<Tree> exceptions) {
        highlights = new ArrayList<>();
        exceptions2HighlightsStack = new Stack<>();
        exceptions2HighlightsStack.push(null);

        CompilationUnitTree compilationUnit = info.getCompilationUnit();

        // Return statements are exit-points, unless filtering by exception type.
        doExitPoints = exceptions == null;

        Boolean wasReturn = scan(methodOrBlock, null);

        if (doExitPoints && wasReturn != Boolean.TRUE) {
            // Highlight the method body's closing brace
            findClosingBrace(methodOrBlock.getLeaf())
                    .ifPresent(pos -> highlights.add(new HighlightSpan(pos, pos + 1)));
        }

        List<TypeMirror> targetExceptions = null;
        if (exceptions != null) {
            targetExceptions = new ArrayList<>();
            for (Tree t : exceptions) {
                TypeMirror m = info.getTrees().getTypeMirror(TreePath.getPath(compilationUnit, t));
                if (m != null) {
                    targetExceptions.add(m);
                }
            }
        }

        Types types = info.getTypes();

        Map<TypeMirror, List<Tree>> finalMap = exceptions2HighlightsStack.peek();
        if (finalMap != null) {
            for (Map.Entry<TypeMirror, List<Tree>> entry : finalMap.entrySet()) {
                TypeMirror thrown = entry.getKey();
                boolean add = true;
                if (targetExceptions != null) {
                    add = false;
                    for (TypeMirror target : targetExceptions) {
                        add |= types.isAssignable(thrown, target);
                    }
                }
                if (add) {
                    for (Tree tree : entry.getValue()) {
                        HighlightSpan.ofTree(tree, info).ifPresent(highlights::add);
                    }
                }
            }
        }

        return highlights;
    }

    private static boolean caretIsInTreeRange(CompilationUnitTree cu, SourcePositions sp, Tree tree, int caret) {
        long start = sp.getStartPosition(cu, tree);
        long end = sp.getEndPosition(cu, tree);
        return start <= caret && caret <= end;
    }

    private OptionalInt findClosingBrace(Tree tree) {
        int end = (int) info.getTrees().getSourcePositions().getEndPosition(info.getCompilationUnit(), tree);
        if (end <= 0) {
            return OptionalInt.empty();
        }
        TokenSequence<JavaTokenId> ts = info.getTokenHierarchy().tokenSequence(JavaTokenId.language());
        if (ts == null) {
            return OptionalInt.empty();
        }
        ts.move(end);
        if (ts.movePrevious() && ts.token().id() == JavaTokenId.RBRACE) {
            return OptionalInt.of(ts.offset());
        }
        return OptionalInt.empty();
    }

    private void addToExceptionsMap(TypeMirror key, Tree value) {
        if (key == null || value == null) {
            return;
        }
        Map<TypeMirror, List<Tree>> top = exceptions2HighlightsStack.peek();
        if (top == null) {
            top = new HashMap<>();
            exceptions2HighlightsStack.pop();
            exceptions2HighlightsStack.push(top);
        }
        top.computeIfAbsent(key, _ -> new ArrayList<>()).add(value);
    }

    private void doPopup() {
        Map<TypeMirror, List<Tree>> top = exceptions2HighlightsStack.pop();
        if (top == null) {
            return;
        }
        Map<TypeMirror, List<Tree>> result = exceptions2HighlightsStack.pop();
        if (result == null) {
            exceptions2HighlightsStack.push(top);
            return;
        }
        for (Map.Entry<TypeMirror, List<Tree>> entry : top.entrySet()) {
            List<Tree> existing = result.get(entry.getKey());
            if (existing == null) {
                result.put(entry.getKey(), entry.getValue());
            } else {
                existing.addAll(entry.getValue());
            }
        }
        exceptions2HighlightsStack.push(result);
    }

    @Override
    public Boolean visitTry(TryTree tree, Stack<Tree> d) {
        exceptions2HighlightsStack.push(null);

        // Try-with-resources (Not in NetBeans MethodExitDetector)
        scan(tree.getResources(), d);

        Boolean returnInTry = scan(tree.getBlock(), d);

        boolean returnInAllCatches = true;
        for (Tree c : tree.getCatches()) {
            Boolean b = scan(c, d);
            returnInAllCatches &= b == Boolean.TRUE;
        }

        Boolean returnInFinally = scan(tree.getFinallyBlock(), d);

        doPopup();

        if (returnInTry == Boolean.TRUE && returnInAllCatches) {
            return Boolean.TRUE;
        }
        return returnInFinally;
    }

    @Override
    public Boolean visitReturn(ReturnTree tree, Stack<Tree> d) {
        if (doExitPoints) {
            HighlightSpan.ofTree(tree, info).ifPresent(highlights::add);
        }
        super.visitReturn(tree, d);
        return Boolean.TRUE;
    }

    @Override
    public Boolean visitCatch(CatchTree tree, Stack<Tree> d) {
        TypeMirror caught = info.getTrees().getTypeMirror(
                new TreePath(new TreePath(getCurrentPath(), tree.getParameter()), tree.getParameter().getType()));
        Types types = info.getTypes();

        if (caught != null) {
            Map<TypeMirror, List<Tree>> top = exceptions2HighlightsStack.peek();
            if (top != null) {
                Set<TypeMirror> toRemove = new HashSet<>();
                for (TypeMirror thrown : top.keySet()) {
                    if (types.isAssignable(thrown, caught)) {
                        toRemove.add(thrown);
                    }
                }
                top.keySet().removeAll(toRemove);
            }
        }
        scan(tree.getParameter(), d);
        return scan(tree.getBlock(), d);
    }

    @Override
    public Boolean visitMethodInvocation(MethodInvocationTree tree, Stack<Tree> d) {
        Element el = info.getTrees().getElement(new TreePath(getCurrentPath(), tree.getMethodSelect()));
        if (el instanceof ExecutableElement ee && ee.getKind() == ElementKind.METHOD) {
            for (TypeMirror m : ee.getThrownTypes()) {
                addToExceptionsMap(m, tree);
            }
        }
        super.visitMethodInvocation(tree, d);
        return null;
    }

    @Override
    public Boolean visitThrow(ThrowTree tree, Stack<Tree> d) {
        addToExceptionsMap(info.getTrees().getTypeMirror(new TreePath(getCurrentPath(), tree.getExpression())), tree);
        super.visitThrow(tree, d);
        return Boolean.TRUE;
    }

    @Override
    public Boolean visitNewClass(NewClassTree tree, Stack<Tree> d) {
        Element el = info.getTrees().getElement(getCurrentPath());
        if (el instanceof ExecutableElement ee && ee.getKind() == ElementKind.CONSTRUCTOR) {
            for (TypeMirror m : ee.getThrownTypes()) {
                addToExceptionsMap(m, tree);
            }
        }
        // Traverse constructor arguments / anonymous class bodies.
        // (NetBeans MethodExitDetector does not call super)
        super.visitNewClass(tree, d);
        return null;
    }

    @Override
    public Boolean visitMethod(MethodTree node, Stack<Tree> p) {
        scan(node.getModifiers(), p);
        scan(node.getReturnType(), p);
        scan(node.getTypeParameters(), p);
        scan(node.getParameters(), p);
        scan(node.getThrows(), p);
        return scan(node.getBody(), p);
    }

    @Override
    public Boolean visitIf(IfTree node, Stack<Tree> p) {
        scan(node.getCondition(), p);
        Boolean thenResult = scan(node.getThenStatement(), p);
        Boolean elseResult = scan(node.getElseStatement(), p);
        if (thenResult == Boolean.TRUE && elseResult == Boolean.TRUE) {
            return Boolean.TRUE;
        }
        return null;
    }

    @Override
    public Boolean visitClass(ClassTree node, Stack<Tree> p) {
        return null;
    }

    @Override
    public Boolean visitLambdaExpression(LambdaExpressionTree node, Stack<Tree> p) {
        return null;
    }
}

