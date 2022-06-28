package cn.edu.pku.sei.intellide.graph.extraction.c_code.utils;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.*;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity.IsArgOfFunctionReturnClass;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity.NumedStatement;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity.PrimitiveClass;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity.StringAndStringList;
import org.eclipse.cdt.core.dom.ast.*;

import java.util.ArrayList;
import java.util.List;

public class FunctionPointerUtil {
    public static int SIZE_OF_FUNCTION_POINTER_HASH_SET = 11113;

    public static List<CVariableInfo>[] FUNCTION_POINTER_HASH_LIST = new ArrayList[SIZE_OF_FUNCTION_POINTER_HASH_SET];

    public static void init() {
        for (int i = 0; i < SIZE_OF_FUNCTION_POINTER_HASH_SET; i++) {
            FUNCTION_POINTER_HASH_LIST[i] = new ArrayList<>();
        }
    }

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

    /**
     * 声明的那个变量名
     *
     * @param statement 这句话
     * @return 左值
     */
    public static String getDeclarationLeftName(IASTDeclarationStatement statement) {
        IASTDeclaration declaration = statement.getDeclaration();
        if (declaration instanceof IASTSimpleDeclaration) {
            for (IASTDeclarator declarator : ((IASTSimpleDeclaration) declaration).getDeclarators()) {
                String classSpecifier = ((IASTSimpleDeclaration) declaration).getDeclSpecifier().getRawSignature();
                PrimitiveClass queryResult = PrimitiveMapUtil.query(classSpecifier);
                if (queryResult != null) {
                    if (PrimitiveMapUtil.query(classSpecifier).getIsFunPointer() || declarator instanceof IASTFunctionDeclarator) {
                        if (declarator.getNestedDeclarator() != null) {
                            return declarator.getNestedDeclarator().getRawSignature();
                        } else {
                            return declarator.getName().toString();
                        }
                    }
                }
            }
        }
        return "";
    }

    /**
     * @param statement 一条声明语句
     * @return 这条声明语句的右值
     */
    public static String getDeclarationRightName(IASTDeclarationStatement statement) {
        IASTDeclaration declaration = statement.getDeclaration();
        if (declaration instanceof IASTSimpleDeclaration) {
            for (IASTDeclarator declarator : ((IASTSimpleDeclaration) declaration).getDeclarators()) {
                if (declarator.getInitializer() != null) {
                    for (IASTNode node : declarator.getInitializer().getChildren()) {
                        if (node instanceof IASTUnaryExpression) {
                            return ((IASTUnaryExpression) node).getOperand().getRawSignature();
                        }
                    }
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

    /**
     * @param includeFileList 调用方所在文件的include列表
     * @param belongTo        调用方所在文件名
     * @param name            被调用函数的函数名
     * @return name对应的函数结点
     */
    public static List<CFunctionInfo> getInvokeFunctions(List<String> includeFileList,
                                                         String belongTo,
                                                         String name) {
        List<CFunctionInfo> res = new ArrayList<>();
        List<CFunctionInfo> tempList = FunctionUtil.FUNCTION_HASH_LIST[FunctionUtil.hashFunc(name)];
        if (tempList.size() > 1) {
            for (CFunctionInfo info : tempList) {
                if (belongTo.equals(info.getBelongTo())) {
                    // (2)
                    res.add(info);
                } else {
                    for (String includeFileName : includeFileList) {
                        if (includeFileName.contains(info.getBelongTo())) {
                            // (1)
                            res.add(info);
                        }
                    }
                }
            }
        } else if (tempList.size() == 1) {
            CFunctionInfo only = tempList.get(0);
            // 只查到了一个那就直接扔进去 不然也没啥意义了
            res.add(only);
        }

        return res;
    }

    /**
     * fun = &function;
     *
     * @param checkStatement  这句话
     * @param invokePoint     fun
     * @param tempInvokePoint *fun
     * @param includeFileList 这个文件的includeFileList
     * @param belongTo        所属文件名
     * @return function对应的函数实体
     */
    public static List<CFunctionInfo> getFunctionListFromExpressionStatement(IASTExpressionStatement checkStatement,
                                                                             String invokePoint,
                                                                             String tempInvokePoint,
                                                                             List<String> includeFileList,
                                                                             String belongTo) {
        List<CFunctionInfo> result = new ArrayList<>();
        for (IASTNode node : checkStatement.getChildren()) {
            if (node instanceof IASTBinaryExpression) {
                IASTBinaryExpression binaryNode = (IASTBinaryExpression) node;
                if (binaryNode.getOperator() == 17) {
                    // 17说明是赋值语句
                    String operand1 = binaryNode.getOperand1().getRawSignature();
                    if (!operand1.startsWith("*")) {
                        operand1 = "*" + operand1;
                    }
                    if (operand1.equals(invokePoint) || operand1.equals(tempInvokePoint)) {
                        String operand2 = binaryNode.getInitOperand2().getRawSignature();
                        // 确定右侧不以&开头
                        if (operand2.startsWith("&")) {
                            operand2 = operand2.substring(1);
                        }
                        result.addAll(FunctionPointerUtil.getInvokeFunctions(includeFileList, belongTo, operand2));
                    }
                }
            }
        }
        return result;
    }

    /**
     * 返回一个布尔值，参数2是否是在参数1这条语句上面的语句，包括同级、上级
     *
     * @param numedStatement
     * @param numedCheckDeclare
     * @return
     */
    public static Boolean isSameOrUpStat(NumedStatement numedStatement, NumedStatement numedCheckDeclare) {
        return (numedStatement.isSameLayer(numedCheckDeclare) && numedStatement.getSeqNum() > numedCheckDeclare.getSeqNum())
                || numedCheckDeclare.getLayer().size() < numedStatement.getLayer().size();
    }

    /**
     * 从这个句子中拿到函数，检查invokePoint是否为该函数的参数，如果是，则返回这个函数结点，否则返回null
     *
     * @param statement   需要检查的这句话
     * @param invokePoint 是否作为该函数的一个参数
     * @return 检查这句话中（可能存在的那个）函数结点
     */
    public static IsArgOfFunctionReturnClass isArgOfFunction(IASTStatement statement,
                                                             String invokePoint,
                                                             List<String> includeFileList,
                                                             String belongTo) {
        List<StringAndStringList> tool = new ArrayList<>();
        if (statement instanceof IASTReturnStatement) {
            tool.addAll(FunctionUtil.getFunctionNameAndArgsFromReturnStatement((IASTReturnStatement) statement));
        } else if (statement instanceof IASTDeclarationStatement) {
            tool.addAll(FunctionUtil.getFunctionNameAndArgsFromDeclarationStatement((IASTDeclarationStatement) statement));
        } else if (statement instanceof IASTExpressionStatement) {
            tool.addAll(FunctionUtil.getFunctionNameAndArgsFromExpressionStatement((IASTExpressionStatement) statement));
        }
        for (StringAndStringList list : tool) {
            for (int i = 0; i < list.getStringList().size(); i++) {
                if (list.getStringList().get(i).equals(invokePoint)) {
                    IsArgOfFunctionReturnClass returnClass = new IsArgOfFunctionReturnClass();
                    returnClass.setIndex(i);
                    returnClass.getFunctionList().addAll(getInvokeFunctions(includeFileList, belongTo, list.getName()));
                    return returnClass;
                }
            }
        }
        return null;
    }
}
