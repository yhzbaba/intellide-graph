package cn.edu.pku.sei.intellide.graph.extraction.c_code.utils;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CFunctionInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CVariableInfo;
import org.eclipse.cdt.core.dom.ast.*;

import java.util.ArrayList;
import java.util.List;

public class VariableUtil {
    public static int SIZE_OF_VARIABLE_HASH_SET = 1111113;

    public static List<CVariableInfo>[] VARIABLE_HASH_LIST = new ArrayList[SIZE_OF_VARIABLE_HASH_SET];

    public static List<String> getVariableNameFromReturnStatement(IASTReturnStatement statement) {
        List<String> result = new ArrayList<>();
        for (IASTNode node : statement.getChildren()) {
            if (node instanceof IASTBinaryExpression) {
                result.addAll(getVariableNameFromBinaryExpression((IASTBinaryExpression) node));
            } else if (node instanceof IASTIdExpression) {
                result.addAll(getVariableNameFromIdExpression((IASTIdExpression) node));
            } else if (node instanceof IASTFunctionCallExpression) {
                result.addAll(getVariableNameFromFunctionCallExpression((IASTFunctionCallExpression) node));
            }
        }
        return result;
    }

    public static List<String> getVariableNameFromFunctionCallExpression(IASTFunctionCallExpression callExpression) {
        List<String> nameResult = new ArrayList<>();
        IASTInitializerClause[] arguments = callExpression.getArguments();
        for (IASTInitializerClause clause : arguments) {
            if (clause instanceof IASTFunctionCallExpression) {
                nameResult.addAll(getVariableNameFromFunctionCallExpression((IASTFunctionCallExpression) clause));
            } else if (clause instanceof IASTIdExpression) {
                nameResult.addAll(getVariableNameFromIdExpression((IASTIdExpression) clause));
            } else if (clause instanceof IASTBinaryExpression) {
                nameResult.addAll(getVariableNameFromBinaryExpression((IASTBinaryExpression) clause));
            } else if (clause instanceof IASTLiteralExpression) {
                nameResult.add(clause.getRawSignature());
            }
        }

        return nameResult;
    }

    public static List<String> getVariableNameFromBinaryExpression(IASTBinaryExpression binaryExpression) {
        List<String> nameResult = new ArrayList<>();
        for (IASTNode node : binaryExpression.getChildren()) {
            if (node instanceof IASTIdExpression) {
                nameResult.addAll(getVariableNameFromIdExpression((IASTIdExpression) node));
            }
        }

        return nameResult;
    }

    public static List<String> getVariableNameFromIdExpression(IASTIdExpression idExpression) {
        List<String> nameResult = new ArrayList<>();
        nameResult.add(idExpression.getRawSignature());
        return nameResult;
    }

    public static List<String> getVariableNameFromExpressionStatement(IASTExpressionStatement statement) {
        List<String> result = new ArrayList<>();
        for (IASTNode node : statement.getChildren()) {
            if (node instanceof IASTFunctionCallExpression) {
                IASTFunctionCallExpression functionCallExpression = (IASTFunctionCallExpression) node;
                // 直接的函数调用语句
                // 获取函数名
                result.addAll(getVariableNameFromFunctionCallExpression(functionCallExpression));
            } else if (node instanceof IASTBinaryExpression) {
                result.addAll(getVariableNameFromBinaryExpression((IASTBinaryExpression) node));
            }
        }
        return result;
    }

    public static List<String> getVariableNameFromDeclarationStatement(IASTDeclarationStatement declarationStatement) {
        List<String> nameResult = new ArrayList<>();
        for (IASTNode node : declarationStatement.getChildren()) {
            for (IASTNode node1 : node.getChildren()) {
                if (node1 instanceof IASTDeclarator) {
                    for (IASTNode node2 : node1.getChildren()) {
                        if (node2 instanceof IASTEqualsInitializer) {
                            for (IASTNode node3 : node2.getChildren()) {
                                // int local_kk = test(kk) + 1;的后半部分
                                if (node3 instanceof IASTBinaryExpression) {
                                    nameResult.addAll(getVariableNameFromBinaryExpression((IASTBinaryExpression) node3));
                                } else if (node3 instanceof IASTFunctionCallExpression) {
                                    nameResult.addAll(getVariableNameFromFunctionCallExpression((IASTFunctionCallExpression) node3));
                                } else if (node3 instanceof IASTIdExpression) {
                                    nameResult.addAll(getVariableNameFromIdExpression((IASTIdExpression) node3));
                                }
                            }
                        } else if (node2 instanceof IASTName) {
                            // int local_kk = test(kk) + 1;的左边的local_kk
                            nameResult.add(node2.getRawSignature());
                        }
                    }
                }
            }
        }
        return nameResult;
    }

    public static List<String> getVariableNameAndUpdate(IASTNode[] nodes, List<String> finalResult) {
        for (IASTNode node : nodes) {
            if (node instanceof IASTBinaryExpression) {
                finalResult.addAll(getVariableNameFromBinaryExpression((IASTBinaryExpression) node));
            } else if (node instanceof IASTCompoundStatement) {
                finalResult.addAll(getVariableNameFromCompoundStatement((IASTCompoundStatement) node));
            } else if (node instanceof IASTFunctionCallExpression) {
                finalResult.addAll(getVariableNameFromFunctionCallExpression((IASTFunctionCallExpression) node));
            }
        }
        return finalResult;
    }

    public static List<String> getVariableNameFromCompoundStatement(IASTCompoundStatement compoundStatement) {
        List<String> result = new ArrayList<>();
        IASTStatement[] statements = compoundStatement.getStatements();
        for (IASTStatement statement : statements) {
            if (statement instanceof IASTReturnStatement) {
                // return语句 可能出现函数调用 return fun1(2); return fun1(2) < fun2 (4);
                result.addAll(getVariableNameFromReturnStatement((IASTReturnStatement) statement));
            } else if (statement instanceof IASTDeclarationStatement) {
                // int res = test1();
                result.addAll(getVariableNameFromDeclarationStatement((IASTDeclarationStatement) statement));
            } else if (statement instanceof IASTExpressionStatement) {
                // fun1(2)
                result.addAll(getVariableNameFromExpressionStatement((IASTExpressionStatement) statement));
            } else if (statement instanceof IASTForStatement) {
                getVariableNameAndUpdate(statement.getChildren(), result);
            } else if (statement instanceof IASTWhileStatement) {
                getVariableNameAndUpdate(statement.getChildren(), result);
            } else if (statement instanceof IASTIfStatement) {
                getVariableNameAndUpdate(statement.getChildren(), result);
            }
        }
        return result;
    }

    public static int hashVariable(String key) {
        return ASTUtil.hashCode(key, SIZE_OF_VARIABLE_HASH_SET);
    }
}
