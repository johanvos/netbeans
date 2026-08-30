package com.gluonhq.netbeans.nbfx.editor.processor.semantics.detector;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import org.netbeans.api.java.lexer.JavaTokenId;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.api.java.source.TreeUtilities;
import org.netbeans.api.lexer.TokenSequence;

/**
 * Highlights every method in a class declaration that overrides (or implements) a method from one of
 * its supertypes.
 *
 * <p>Ported from NetBeans' {@code MarkOccurrencesHighlighterBase.detectMethodsForClass}.</p>
 */
final class OverridesDetector {

    private OverridesDetector() {}

    /**
     * {@code true} if the caret paths make either of the trigger
     * cases handled by {@link #process(CompilationInfo, TreePath, TreePath, int)} possible.
     */
    static boolean isPotentialOverrideSite(TreePath treePath, TreePath typePath) {
        return (typePath != null && typePath.getParentPath() != null
                && TreeUtilities.CLASS_TREE_KINDS.contains(typePath.getParentPath().getLeaf().getKind()))
                || TreeUtilities.CLASS_TREE_KINDS.contains(treePath.getLeaf().getKind());
    }

    /**
     * Runs the detector if the caret resolves to one of the supported trigger cases.
     *
     * @param info  a resolved {@link CompilationInfo}
     * @param treePath path to the tree at the caret
     * @param typePath the outermost type-path enclosing the caret, or null
     * @param caretOffset the caret offset
     * @return identifier-token spans of the matching methods, or {@code null} if none of the
     *         trigger cases applies
     */
    static List<HighlightSpan> process(CompilationInfo info, TreePath treePath, TreePath typePath, int caretOffset) {
        // (a) Caret on a supertype identifier.
        if (typePath != null && typePath.getParentPath() != null
                && TreeUtilities.CLASS_TREE_KINDS.contains(typePath.getParentPath().getLeaf().getKind())) {
            TreePath classPath = typePath.getParentPath();
            ClassTree classTree = (ClassTree) classPath.getLeaf();
            Tree leaf = typePath.getLeaf();
            boolean isExtends = classTree.getExtendsClause() == leaf;
            boolean isImplements = !isExtends && classTree.getImplementsClause().contains(leaf);
            if (isExtends || isImplements) {
                Element superType = info.getTrees().getElement(typePath);
                Element thisType = info.getTrees().getElement(classPath);
                if (isClass(superType) && isClass(thisType)) {
                    return scanMembers(info, classPath, List.of((TypeElement) superType), (TypeElement) thisType);
                }
            }
        }

        // (b) Caret on the `extends` or `implements` keyword.
        if (TreeUtilities.CLASS_TREE_KINDS.contains(treePath.getLeaf().getKind())) {
            ClassTree classTree = (ClassTree) treePath.getLeaf();
            int bodyStart = findBodyStart(info, classTree);
            if (caretOffset >= bodyStart) {
                return null;
            }
            TokenSequence<JavaTokenId> ts = info.getTokenHierarchy().tokenSequence(JavaTokenId.language());
            if (ts == null) {
                return null;
            }
            ts.move(caretOffset);
            if (!ts.moveNext()) {
                return null;
            }
            JavaTokenId id = ts.token().id();
            if (id == JavaTokenId.EXTENDS) {
                Tree superClass = classTree.getExtendsClause();
                if (superClass != null) {
                    Element superType = info.getTrees().getElement(new TreePath(treePath, superClass));
                    Element thisType = info.getTrees().getElement(treePath);
                    if (isClass(superType) && isClass(thisType)) {
                        return scanMembers(info, treePath, List.of((TypeElement) superType), (TypeElement) thisType);
                    }
                }
            } else if (id == JavaTokenId.IMPLEMENTS) {
                List<? extends Tree> superClasses = classTree.getImplementsClause();
                if (superClasses != null && !superClasses.isEmpty()) {
                    List<TypeElement> superTypes = new ArrayList<>();
                    for (Tree superTypeTree : superClasses) {
                        Element e = info.getTrees().getElement(new TreePath(treePath, superTypeTree));
                        if (isClass(e)) {
                            superTypes.add((TypeElement) e);
                        }
                    }
                    Element thisType = info.getTrees().getElement(treePath);
                    if (!superTypes.isEmpty() && isClass(thisType)) {
                        return scanMembers(info, treePath, superTypes, (TypeElement) thisType);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Walks the members of {@code classPath}'s {@link ClassTree} and collects the identifier-token span
     * of every {@code METHOD} that overrides a method exposed by one of {@code superTypes}.
     */
    private static List<HighlightSpan> scanMembers(CompilationInfo info, TreePath classPath,
                                                   List<TypeElement> superTypes, TypeElement thisType) {
        List<HighlightSpan> spans = new ArrayList<>();
        ClassTree classTree = (ClassTree) classPath.getLeaf();
        Elements elements = info.getElements();
        TypeElement jlObject = elements.getTypeElement("java.lang.Object");
        Trees trees = info.getTrees();
        SourcePositions positions = trees.getSourcePositions();
        CompilationUnitTree cu = info.getCompilationUnit();

        OUTER: for (Tree member : classTree.getMembers()) {
            if (member.getKind() != Kind.METHOD) {
                continue;
            }
            TreePath memberPath = new TreePath(classPath, member);
            Element el = trees.getElement(memberPath);
            if (!(el instanceof ExecutableElement ee) || el.getKind() != ElementKind.METHOD) {
                continue;
            }
            MethodTree methodTree = (MethodTree) member;
            int startPos = (int) positions.getStartPosition(cu, methodTree);
            for (TypeElement superType : superTypes) {
                for (ExecutableElement candidate : ElementFilter.methodsIn(elements.getAllMembers(superType))) {
                    // Ignore Object's methods when the supertype is an interface (matches NetBeans behaviour).
                    if (elements.overrides(ee, candidate, thisType)
                            && (superType.getKind().isClass() || !candidate.getEnclosingElement().equals(jlObject))) {
                        HighlightSpan.ofDeclarationName(info, methodTree.getName().toString(), startPos)
                                .ifPresent(spans::add);
                        continue OUTER;
                    }
                }
            }
        }
        return spans;
    }

    private static boolean isClass(Element e) {
        return e != null && (e.getKind().isClass() || e.getKind().isInterface());
    }

    /**
     * Returns the offset of the class body's opening brace, or the class-tree end position if no
     * {@code '{'} is found.
     */
    private static int findBodyStart(CompilationInfo info, ClassTree classTree) {
        int start = (int) info.getTrees().getSourcePositions().getStartPosition(info.getCompilationUnit(), classTree);
        int end = (int) info.getTrees().getSourcePositions().getEndPosition(info.getCompilationUnit(), classTree);
        TokenSequence<JavaTokenId> ts = info.getTokenHierarchy().tokenSequence(JavaTokenId.language());
        if (ts == null) {
            return end;
        }
        ts.move(start);
        while (ts.moveNext() && ts.offset() < end) {
            if (ts.token().id() == JavaTokenId.LBRACE) {
                return ts.offset();
            }
        }
        return end;
    }
}
