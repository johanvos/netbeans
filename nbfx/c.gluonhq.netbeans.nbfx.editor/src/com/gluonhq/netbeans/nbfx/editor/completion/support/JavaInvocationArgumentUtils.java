package com.gluonhq.netbeans.nbfx.editor.completion.support;

import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.TreePath;
import org.netbeans.api.java.source.CompilationController;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.List;

/**
 * Text- and tree-level helpers for completions that fire inside invocation argument lists
 * (plain method calls or constructor calls).
  */
public final class JavaInvocationArgumentUtils {

    private JavaInvocationArgumentUtils() {
    }

    /**
     * Returns the {@link DeclaredType} of the receiver expression for the given invocation
     * (e.g. {@code ObservableList<Node>} for {@code box.getChildren().add(...)}), or
     * {@code null} when the invocation is an unqualified call or the type cannot be
     * resolved. The substitution context is needed to map generic parameter type variables
     * to concrete types via {@link Types#asMemberOf}.
     */
    static DeclaredType resolveReceiverDeclaredType(CompilationController controller,
                                                    TreePath invocationPath) {
        if (invocationPath == null
                || !(invocationPath.getLeaf() instanceof MethodInvocationTree invocation)
                || !(invocation.getMethodSelect() instanceof MemberSelectTree memberSelect)) {
            return null;
        }
        TreePath exprPath = new TreePath(invocationPath, memberSelect.getExpression());
        TypeMirror tm = controller.getTrees().getTypeMirror(exprPath);
        return tm instanceof DeclaredType dt ? dt : null;
    }

    /**
     * Returns the parameter type of {@code overload} at {@code argIndex} after applying
     * type-argument substitution from {@code receiverType} (when available). Falls back to
     * the raw declaration parameter type when substitution isn't possible.
     */
    static TypeMirror resolveSubstitutedParameterType(Types types,
                                                      DeclaredType receiverType,
                                                      ExecutableElement overload,
                                                      int argIndex) {
        if (receiverType != null) {
            try {
                TypeMirror asMember = types.asMemberOf(receiverType, overload);
                if (asMember instanceof ExecutableType et) {
                    return extractParameterType(et.getParameterTypes(), overload.isVarArgs(), argIndex);
                }
            } catch (IllegalArgumentException ignored) {
                // overload not actually a member of receiverType (e.g. resolved via simple-name
                // fallback) — fall through to raw declaration.
            }
        }
        return JavaCompletionInvocationUtils.resolveParameterTypeForIndex(overload, argIndex);
    }

    private static TypeMirror extractParameterType(List<? extends TypeMirror> params,
                                                   boolean varArgs, int argIndex) {
        if (argIndex < params.size()) {
            TypeMirror t = params.get(argIndex);
            if (varArgs && argIndex == params.size() - 1 && t.getKind() == TypeKind.ARRAY) {
                return ((ArrayType) t).getComponentType();
            }
            return t;
        }
        if (varArgs && !params.isEmpty()) {
            TypeMirror t = params.getLast();
            return t.getKind() == TypeKind.ARRAY ? ((ArrayType) t).getComponentType() : t;
        }
        return null;
    }
}
