package cn.edu.pku.sei.intellide.graph.extraction.c_code.utils;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CFunctionInfo;
import org.eclipse.cdt.core.dom.ast.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class FunctionUtil {
    public static int SIZE_OF_FUNCTION_HASH_SET = 1111113;

    public static List<CFunctionInfo>[] FUNCTION_HASH_LIST = new ArrayList[SIZE_OF_FUNCTION_HASH_SET];

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
            }
        }
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

    public static List<String> getFunctionNameFromExpressionStatement(IASTExpressionStatement statement) {
        List<String> result = new ArrayList<>();
        for (IASTNode node : statement.getChildren()) {
            if (node instanceof IASTFunctionCallExpression) {
                IASTFunctionCallExpression functionCallExpression = (IASTFunctionCallExpression) node;
                // 直接的函数调用语句
                // 获取函数名
                return getFunctionNameFromFunctionCallExpression(functionCallExpression);
            }
        }
        return result;
    }

    public static List<String> getFunctionNameFromReturnStatement(IASTReturnStatement statement) {
        List<String> result = new ArrayList<>();
        for (IASTNode node : statement.getChildren()) {
            if (node instanceof IASTFunctionCallExpression) {
                // 直接的函数调用语句
                result.addAll(getFunctionNameFromFunctionCallExpression((IASTFunctionCallExpression) node));
            } else if (node instanceof IASTBinaryExpression) {
                List<String> tResult = getFunctionNameFromBinaryExpression((IASTBinaryExpression) node);
                result.addAll(tResult);
            }
        }
        return result;
    }

    public static List<String> getFunctionNameFromDeclarationStatement(IASTDeclarationStatement statement) {
        List<String> result = new ArrayList<>();
        for (IASTNode node : statement.getChildren()) {
            for (IASTNode node1 : node.getChildren()) {
                if (node1 instanceof IASTDeclarator) {
                    for (IASTNode node2 : node1.getChildren()) {
                        if (node2 instanceof IASTEqualsInitializer) {
                            for (IASTNode node3 : node2.getChildren()) {
                                if (node3 instanceof IASTFunctionCallExpression) {
                                    result.addAll(getFunctionNameFromFunctionCallExpression((IASTFunctionCallExpression) node3));
                                }
                            }
                            break;
                        }
                    }
                    break;
                }
            }
        }
        return result;
    }

    public static List<String> getFunctionNameFromCompoundStatement(IASTCompoundStatement compoundStatement) {
        List<String> result = new ArrayList<>();
        IASTStatement[] statements = compoundStatement.getStatements();
        for (IASTStatement statement : statements) {
            if (statement instanceof IASTReturnStatement) {
                // return语句 可能出现函数调用 return fun1(2); return fun1(2) < fun2 (4);
                result.addAll(getFunctionNameFromReturnStatement((IASTReturnStatement) statement));
            } else if (statement instanceof IASTDeclarationStatement) {
                // int res = test1();
                result.addAll(getFunctionNameFromDeclarationStatement((IASTDeclarationStatement) statement));
            } else if (statement instanceof IASTExpressionStatement) {
                // fun1(2)
                result.addAll(getFunctionNameFromExpressionStatement((IASTExpressionStatement) statement));
            } else if (statement instanceof IASTForStatement) {
                getFunctionNameAndUpdate(statement.getChildren(), result);
            } else if (statement instanceof IASTWhileStatement) {
                getFunctionNameAndUpdate(statement.getChildren(), result);
            } else if (statement instanceof IASTIfStatement) {
                getFunctionNameAndUpdate(statement.getChildren(), result);
            }
        }
        return result;
    }

    public static List<String> getFunctionNameAndUpdate(IASTNode[] nodes, List<String> finalResult) {
        for (IASTNode node : nodes) {
            if (node instanceof IASTBinaryExpression) {
                finalResult.addAll(getFunctionNameFromBinaryExpression((IASTBinaryExpression) node));
            } else if (node instanceof IASTCompoundStatement) {
                finalResult.addAll(getFunctionNameFromCompoundStatement((IASTCompoundStatement) node));
            } else if (node instanceof IASTFunctionCallExpression) {
                finalResult.addAll(getFunctionNameFromFunctionCallExpression((IASTFunctionCallExpression) node));
            }
        }
        return finalResult;
    }

    public static int hashFunc(String key) {
        int arraySize = SIZE_OF_FUNCTION_HASH_SET;            //数组大小一般取质数
        int hashCode = 0;
        for (int i = 0; i < key.length(); i++) {        //从字符串的左边开始计算
            int letterValue = key.charAt(i) - 40;//将获取到的字符串转换成数字，比如a的码值是97，则97-96=1 就代表a的值，同理b=2；
            hashCode = ((hashCode << 5) + letterValue + arraySize) % arraySize;//防止编码溢出，对每步结果都进行取模运算
        }
        return hashCode;
    }
}
