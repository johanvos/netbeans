package com.gluonhq.netbeans.nbfx.editor.processor.semantics.detector;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.TypeKind;

import org.netbeans.api.java.source.CompilationInfo;

/**
 * Mark-occurrences for program elements — identifiers, member selects, class /
 * method / variable declarations, and {@code new Foo()} expressions.
 *
 * <p>Given a caret position, resolves the javac {@link Element} at that position, and then walks the entire
 * compilation unit collecting every identifier, member-select or declaration that resolves to the same element.
 * </p>
 */
final class MethodVariableDetector {

    private static final Logger LOG = Logger.getLogger(MethodVariableDetector.class.getName());

    private MethodVariableDetector() {}

    /**
     * Runs the detector for the tree at the given caret position.
     *
     * @return a list of {@link HighlightSpan}s
     */
    static List<HighlightSpan> process(CompilationInfo info, TreePath tp) {
        Trees trees = info.getTrees();
        SourcePositions positions = trees.getSourcePositions();
        CompilationUnitTree cu = info.getCompilationUnit();

        Element target = resolveTargetElement(trees, tp);
        if (!isSupportedElement(target)) {
            return List.of();
        }

        List<HighlightSpan> spans = new ArrayList<>();
        new Scanner(trees, target, positions, cu, info, spans).scan(cu, null);
        return spans;
    }

    private static Element resolveTargetElement(Trees trees, TreePath tp) {
        Element e = trees.getElement(tp);
        if (e != null && e.getKind() == ElementKind.CONSTRUCTOR) {
            Element enclosing = e.getEnclosingElement();
            if (enclosing != null) {
                return enclosing;
            }
        }
        return e;
    }

    private static boolean isSupportedElement(Element el) {
        if (el == null || el.asType().getKind() == TypeKind.OTHER) {
            return false;
        }
        ElementKind k = el.getKind();
        return switch (k) {
            case ANNOTATION_TYPE, CLASS, ENUM, INTERFACE, RECORD, TYPE_PARAMETER -> true;
            case CONSTRUCTOR, METHOD -> true;
            case ENUM_CONSTANT -> true;
            case FIELD -> true;
            case LOCAL_VARIABLE, RESOURCE_VARIABLE, PARAMETER, EXCEPTION_PARAMETER -> true;
            case MODULE, PACKAGE -> false;
            default -> {
                LOG.warning("Unexpected element kind for MarkOccurrences: " + k);
                yield false;
            }
        };
    }

    private static final class Scanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final Element target;
        private final SourcePositions positions;
        private final CompilationUnitTree cu;
        private final CompilationInfo info;
        private final List<HighlightSpan> spans;

        Scanner(Trees trees, Element target, SourcePositions positions, CompilationUnitTree cu,
                CompilationInfo info, List<HighlightSpan> spans) {
            this.trees = trees;
            this.target = target;
            this.positions = positions;
            this.cu = cu;
            this.info = info;
            this.spans = spans;
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void p) {
            if (matches()) {
                add((int) positions.getStartPosition(cu, node), (int) positions.getEndPosition(cu, node));
            }
            return super.visitIdentifier(node, p);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void p) {
            if (matches()) {
                int end = (int) positions.getEndPosition(cu, node);
                add(end - node.getIdentifier().length(), end);
            }
            return super.visitMemberSelect(node, p);
        }

        @Override
        public Void visitClass(ClassTree node, Void p) {
            if (matches()) {
                addDeclarationName(node.getSimpleName().toString(), (int) positions.getStartPosition(cu, node));
            }
            return super.visitClass(node, p);
        }

        @Override
        public Void visitMethod(MethodTree node, Void p) {
            Element e = trees.getElement(getCurrentPath());
            boolean isConstructor = e != null && e.getKind() == ElementKind.CONSTRUCTOR
                    && Objects.equals(e.getEnclosingElement(), target);
            if (isConstructor || Objects.equals(e, target)) {
                String name = node.getName().toString();
                if ("<init>".equals(name)) {
                    Tree parent = getCurrentPath().getParentPath().getLeaf();
                    if (parent instanceof ClassTree ct) {
                        name = ct.getSimpleName().toString();
                    }
                }
                addDeclarationName(name, (int) positions.getStartPosition(cu, node));
            }
            return super.visitMethod(node, p);
        }

        @Override
        public Void visitVariable(VariableTree node, Void p) {
            if (matches()) {
                addDeclarationName(node.getName().toString(), (int) positions.getStartPosition(cu, node));
            }
            return super.visitVariable(node, p);
        }

        private boolean matches() {
            Element e = trees.getElement(getCurrentPath());
            return e != null && Objects.equals(e, target);
        }

        private void add(int start, int end) {
            HighlightSpan.of(start, end).ifPresent(spans::add);
        }

        private void addDeclarationName(String name, int start) {
            HighlightSpan.ofDeclarationName(info, name, start).ifPresent(spans::add);
        }
    }
}

