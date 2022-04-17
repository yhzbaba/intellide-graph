package cn.edu.pku.sei.intellide.graph.extraction.c_code.utils;

import org.eclipse.cdt.core.dom.ast.*;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPASTCompositeTypeSpecifier;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPMethod;

public class ASTUtil {
    public static String getClassStructName(IASTSimpleDeclaration simpleDeclaration) {
        IASTDeclSpecifier declSpecifier = simpleDeclaration.getDeclSpecifier();
        if (declSpecifier instanceof ICPPASTCompositeTypeSpecifier) {
            return ((ICPPASTCompositeTypeSpecifier) declSpecifier).getName().toString();
        }
        return "";
    }

    public static Boolean isTypeDef(IASTDeclSpecifier declSpecifier) {
        if (declSpecifier.getStorageClass() == IASTDeclSpecifier.sc_typedef) {
            return true;
        }
        return false;
    }

    public static boolean hasPointerType(final IASTDeclarator declarator) {
        if (declarator == null) {
            return false;
        }

        if (declarator instanceof IASTArrayDeclarator) {
            final IASTArrayDeclarator arrayDecl = (IASTArrayDeclarator) declarator;
            return arrayDecl.getPointerOperators().length > 0;
        }

        if (declarator.getPointerOperators().length > 0) {
            return true;
        }

        final IBinding declBinding = declarator.getName().resolveBinding();

        if (declBinding instanceof IVariable) {
            return ((IVariable) declBinding).getType() instanceof IPointerType;
        } else if (declBinding instanceof ICPPMethod) {
            return ((ICPPMethod) declBinding).getType().getReturnType() instanceof IPointerType;
        }

        return false;
    }

    public static int hashCode(String key, int arraySize) {
        int hashCode = 0;
        for (int i = 0; i < key.length(); i++) {
            int letterValue = key.charAt(i) - 40;
            hashCode = ((hashCode << 5) + letterValue + arraySize) % arraySize;
        }
        return hashCode;
    }
}
