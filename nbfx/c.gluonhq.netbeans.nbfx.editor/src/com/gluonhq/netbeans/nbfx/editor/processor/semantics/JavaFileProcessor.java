package com.gluonhq.netbeans.nbfx.editor.processor.semantics;

import com.gluonhq.netbeans.nbfx.editor.decoration.TokenCategory;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.netbeans.api.java.lexer.JavaTokenId;
import org.netbeans.api.java.source.CancellableTask;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.api.java.source.JavaSource.Phase;
import org.netbeans.api.lexer.Token;
import org.netbeans.api.lexer.TokenSequence;
import org.openide.util.Exceptions;

/**
 * Java File Processor is a Java parser, that finds keywords in a given text source,
 * and returns {@link TextPosResult}s for those.
 */
public class JavaFileProcessor {

    private static final Logger LOG = Logger.getLogger(JavaFileProcessor.class.getName());

    private final JavaDiagnosticAnalyzer diagnosticAnalyzer;

    public JavaFileProcessor(SourceContext context) {
        this.diagnosticAnalyzer = new JavaDiagnosticAnalyzer(context.fileObject(), context.classpathInfo());
    }

    /**
     * Runs the analysis asynchronously against the given immutable
     * {@link SourceContext.Snapshot} source.
     *
     * @param snapshot the snapshot captured on the caller thread, or {@code null}
     * @param editLine 0-based line being edited, or -1
     * @return a CompletableFuture with the analysis results
     */
    public CompletableFuture<List<TextPosResult>> process(SourceContext.Snapshot snapshot, int editLine) {
        if (snapshot == null || snapshot.javaSource() == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> {
            long t0 = System.currentTimeMillis();
            List<TextPosResult> results = analyze(snapshot, editLine);
            LOG.fine(() -> getClass().getSimpleName() + " analysis took "
                    + (System.currentTimeMillis() - t0) + " ms, produced "
                    + results.size() + " decorations");
            return results;
        });
    }

    private List<TextPosResult> analyze(SourceContext.Snapshot snapshot, int editLine) {
        List<TextPosResult> results = new ArrayList<>();
        LOG.fine(() -> "Start java processing");

        CancellableTask<CompilationController> task = new CancellableTask<>() {
            @Override
            public void run(CompilationController controller) throws Exception {
                controller.toPhase(Phase.RESOLVED);

                results.addAll(diagnosticAnalyzer.analyze(controller, snapshot.source(), snapshot.lineStarts(), editLine));

                MyClassVisitor visitor = new MyClassVisitor(controller, results, snapshot.lineStarts());
                visitor.scan(controller.getCompilationUnit(), null);
            }

            @Override
            public void cancel() {
                LOG.info(() -> "Cancel request");
            }
        };

        try {
            boolean executed = SourceContext.runSemanticTask(snapshot, task, true);
            if (!executed) {
                return results;
            }
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
        }

        return results;
    }

    private static class MyClassVisitor extends TreePathScanner<Void, Void> {

        private final CompilationInfo info;
        private final SourcePositions positions;
        private final CompilationUnitTree cu;
        private final List<TextPosResult> results;
        private final int[] lineStarts;
        private final Set<String> fieldNames = new HashSet<>();

        public MyClassVisitor(CompilationInfo info, List<TextPosResult> results, int[] lineStarts) {
            this.info = info;
            this.results = results;
            this.lineStarts = lineStarts;
            Trees trees = info.getTrees();
            positions = trees.getSourcePositions();
            cu = info.getCompilationUnit();
        }

        @Override
        public Void visitClass(ClassTree node, Void p) {
            // Pre-collect field names so visitIdentifier can decorate usages
            for (Tree member : node.getMembers()) {
                if (member instanceof VariableTree varTree) {
                    fieldNames.add(varTree.getName().toString());
                }
            }
            long blockStart = positions.getStartPosition(cu, node);
            long blockEnd = positions.getEndPosition(cu, node);
            // Skip leading annotations/modifiers to find the actual class name token
            long searchStart = searchStartAfterModifiers(node.getModifiers(), blockStart);

            if (node.getKind() == Tree.Kind.RECORD) {
                // "record" is a context-sensitive keyword, not a reserved keyword
                // Decorate it as a keyword when the parser confirms this ClassTree is a record declaration.
                findAndDecorate("record", searchStart, JavaTokenId.LPAREN, TokenCategory.KEYWORD);
            }

            findAndDecorate(node.getSimpleName().toString(), searchStart, JavaTokenId.LBRACE, TokenCategory.CLASS);
            LOG.fine(() -> "Class " + node.getSimpleName() + " found at [" + blockStart + ", " + blockEnd + "]");
            return super.visitClass(node, p);
        }

        @Override
        public Void visitMethod(MethodTree node, Void p) {
            long blockStart = positions.getStartPosition(cu, node);
            long blockEnd = positions.getEndPosition(cu, node);
            String name = node.getName().toString();
            if ("<init>".equals(name)) {
                // Constructor: the source token is the class name, not "<init>"
                Tree parent = getCurrentPath().getParentPath().getLeaf();
                if (parent instanceof ClassTree classTree) {
                    name = classTree.getSimpleName().toString();
                }
            }
            // MethodTree's start position covers the leading annotations and modifiers.
            long searchStart = searchStartAfterModifiers(node.getModifiers(), blockStart);
            findAndDecorate(name, searchStart, JavaTokenId.LPAREN, TokenCategory.METHOD);
            LOG.fine(() -> "Method " + node.getName() + " found at [" + blockStart + ", " + blockEnd + "]");
            return super.visitMethod(node, p);
        }

        @Override
        public Void visitVariable(VariableTree node, Void p) {
            // Only decorate class-level fields, not local variables or parameters
            Tree parent = getCurrentPath().getParentPath().getLeaf();
            if (parent instanceof ClassTree) {
                long blockStart = positions.getStartPosition(cu, node);
                long blockEnd = positions.getEndPosition(cu, node);
                long searchStart = searchStartAfterModifiers(node.getModifiers(), blockStart);
                findAndDecorate(node.getName().toString(), searchStart, JavaTokenId.SEMICOLON, TokenCategory.FIELD);
                LOG.fine(() -> "Field " + node.getName() + " found at [" + blockStart + ", " + blockEnd + "]");
            }
            return super.visitVariable(node, p);
        }

        /**
         * Returns a token-scan start offset that sits after the modifiers/annotations
         * of a declaration, falling back to {@code declarationStart} when the
         * modifiers tree has no usable end position (e.g. synthetic/empty modifiers).
         */
        private long searchStartAfterModifiers(com.sun.source.tree.ModifiersTree modifiers, long declarationStart) {
            if (modifiers == null) {
                return declarationStart;
            }
            long end = positions.getEndPosition(cu, modifiers);
            if (end < 0 || end < declarationStart) {
                return declarationStart;
            }
            return end;
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void p) {
            String name = node.getName().toString();
            if (fieldNames.contains(name) && !isFieldShadowed(name)) {
                long pos = positions.getStartPosition(cu, node);
                findAndDecorate(name, pos, null, TokenCategory.FIELD);
                LOG.fine(() -> "Field " + name + " found at [" + pos + "]");
            }
            return super.visitIdentifier(node, p);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void p) {
            // Handle this.field references
            String name = node.getIdentifier().toString();
            if (fieldNames.contains(name) && node.getExpression() instanceof IdentifierTree id
                    && "this".equals(id.getName().toString())) {
                // Decorate the field identifier after the dot, not "this"
                long exprEnd = positions.getEndPosition(cu, node);
                findAndDecorate(name, exprEnd - name.length(), null, TokenCategory.FIELD);
                LOG.fine(() -> "Field this." + name + " found at [" + (exprEnd - name.length()) + "]");
            }
            return super.visitMemberSelect(node, p);
        }

        private void findAndDecorate(String targetName, long blockStart,
                                     JavaTokenId stopToken, TokenCategory category) {
            if (targetName == null || targetName.isEmpty()) {
                return;
            }
            TokenSequence<JavaTokenId> seq = info.getTokenHierarchy().tokenSequence(JavaTokenId.language());
            seq.move((int) blockStart);
            while (seq.moveNext()) {
                Token<JavaTokenId> token = seq.token();
                JavaTokenId id = token.id();
                if (stopToken != null && id == stopToken) {
                    break;
                }
                if (id == JavaTokenId.IDENTIFIER && token.text().toString().equals(targetName)) {
                    int start = seq.offset();
                    int end = start + token.length();
                    results.add(TextPosResult.from(start, end, lineStarts, category.style()));
                    break;
                }
                if (stopToken == null) {
                    break;
                }
            }
        }

        // walk up the tree to check if there's a local variable or parameter with the same name,
        // which would shadow the field
        private boolean isFieldShadowed(String name) {
            TreePath path = getCurrentPath().getParentPath();
            while (path != null) {
                Tree leaf = path.getLeaf();
                if (leaf instanceof MethodTree method) {
                    // Check method parameters
                    if (method.getParameters().stream().anyMatch(param ->
                            param.getName().toString().equals(name))) {
                        return true;
                    }
                    // Check local variables in the method body
                    if (method.getBody() != null &&
                            method.getBody().getStatements().stream().anyMatch(stmt ->
                                    stmt instanceof VariableTree local && local.getName().toString().equals(name))) {
                        return true;
                    }
                    break; // reached end of method, no shadowing
                } else if (leaf instanceof ClassTree) {
                    break; // reached class level, no shadowing
                }
                path = path.getParentPath();
            }
            return false;
        }
    }
}
