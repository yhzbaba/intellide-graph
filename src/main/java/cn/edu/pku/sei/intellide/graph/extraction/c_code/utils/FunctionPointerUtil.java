package cn.edu.pku.sei.intellide.graph.extraction.c_code.utils;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CVariableInfo;
import org.eclipse.cdt.core.dom.ast.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FunctionPointerUtil {
    public static int SIZE_OF_FUNCTION_POINTER_HASH_SET = 11113;

    public static List<CVariableInfo>[] FUNCTION_POINTER_HASH_LIST = new ArrayList[SIZE_OF_FUNCTION_POINTER_HASH_SET];

    public static int hashFunctionPointer(String key) {
        return ASTUtil.hashCode(key, SIZE_OF_FUNCTION_POINTER_HASH_SET);
    }

    public static List<String> getFunctionNameFromExpressionStatement(IASTExpressionStatement statement) {
        List<String> result = new ArrayList<>();
        for (IASTNode node : statement.getChildren()) {
            if (node instanceof IASTFunctionCallExpression) {
                IASTFunctionCallExpression functionCallExpression = (IASTFunctionCallExpression) node;
                // 直接的函数调用语句
                // 获取函数名
                return getFunctionNameFromFunctionCallExpression(functionCallExpression);
            } else if (node instanceof IASTBinaryExpression) {
                return getFunctionNameFromBinaryExpression((IASTBinaryExpression) node);
            }
        }
        return result;
    }

    /**
     * 同时考虑了fun1() book.fun1() bookPtr->fun1() printf(fun1())
     *
     * @param expression
     * @return
     */
    public static List<String> getFunctionNameFromFunctionCallExpression(IASTFunctionCallExpression expression) {
        List<String> result = new ArrayList<>();
        IASTInitializerClause[] arguments = expression.getArguments();
        // 处理参数
        for (IASTInitializerClause clause : arguments) {
            if (clause instanceof IASTFunctionCallExpression) {
                result.addAll(getFunctionNameFromFunctionCallExpression((IASTFunctionCallExpression) clause));
            }
        }
        for (IASTNode node : expression.getChildren()) {
            if (node instanceof IASTIdExpression) {
                result.add(node.getRawSignature());
            } else if (node instanceof IASTFieldReference) {
                for (IASTNode node1 : node.getChildren()) {
                    if (node1 instanceof IASTName) {
                        result.add(node1.getRawSignature());
                    }
                }
            } else if (node instanceof IASTUnaryExpression) {
                result.addAll(getFunctionNameFromUnaryExpression((IASTUnaryExpression) node));
            }
        }
        return result;
    }

    public static List<String> getFunctionNameFromUnaryExpression(IASTUnaryExpression unaryExpression) {
        List<String> result = new ArrayList<>();
        result.add(unaryExpression.getOperand().getRawSignature());

        return result;
    }

    public static List<String> getFunctionNameFromBinaryExpression(IASTBinaryExpression binaryExpression) {
        List<String> nameResult = new ArrayList<>();
        for (IASTNode node : binaryExpression.getChildren()) {
            if (node instanceof IASTFunctionCallExpression) {
                nameResult.addAll(getFunctionNameFromFunctionCallExpression((IASTFunctionCallExpression) node));
            }
        }

        return nameResult;
    }

    public static String getDeclarationName(IASTDeclarationStatement statement) {
        IASTDeclaration declaration = statement.getDeclaration();
        if (declaration instanceof IASTSimpleDeclaration) {
            for (IASTDeclarator declarator : ((IASTSimpleDeclaration) declaration).getDeclarators()) {
                if (declarator.getNestedDeclarator() != null) {
                    return declarator.getNestedDeclarator().getRawSignature();
                } else {
                    return declarator.getName().toString();
                }
            }
        }
        return "";
    }

    /**
     * 接收一个一个variableName，和本文件文件名，
     * 本文件有，直接返回，不管有没有初始化
     * 本文件没有，优先找一遍看有没有已初始化的，否则再找一遍看有没有没初始化的
     */
    public static CVariableInfo isIncludeVariable(String variableName, String belongTo) {
        for (CVariableInfo info : FUNCTION_POINTER_HASH_LIST[hashFunctionPointer(variableName)]) {
            if (info.getName().equals(variableName)) {
                if (info.getBelongTo().equals(belongTo)) {
                    return info;
                }
            }
        }
        for (CVariableInfo info : FUNCTION_POINTER_HASH_LIST[hashFunctionPointer(variableName)]) {
            if (info.getName().equals(variableName)) {
                if (info.getEqualsInitializer() != null) {
                    return info;
                }
            }
        }
        for (CVariableInfo info : FUNCTION_POINTER_HASH_LIST[hashFunctionPointer(variableName)]) {
            if (info.getName().equals(variableName)) {
                return info;
            }
        }
        return VariableUtil.isIncludeVariable(variableName, belongTo);
    }
}
