package cn.edu.pku.sei.intellide.graph.extraction.c_code.utils;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CFunctionInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity.NumedStatement;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity.StringAndStringList;
import org.eclipse.cdt.core.dom.ast.*;

import java.util.ArrayList;
import java.util.List;

public class FunctionUtil {
    public static int SIZE_OF_FUNCTION_HASH_SET = 1111113;

    public static List<CFunctionInfo>[] FUNCTION_HASH_LIST = new ArrayList[SIZE_OF_FUNCTION_HASH_SET];

    public static void init() {
        for (int i = 0; i < FunctionUtil.SIZE_OF_FUNCTION_HASH_SET; i++) {
            FunctionUtil.FUNCTION_HASH_LIST[i] = new ArrayList<>();
        }
    }

    /**
     * 同时考虑了fun1() book.fun1() bookPtr->fun1() printf(fun1())
     *
     * @param expression 函数调用那个表达式
     * @return 表达式中递归出现的函数名以及参数名
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

    /**
     * 同时考虑了fun1() book.fun1() bookPtr->fun1() printf(fun1())
     *
     * @param expression
     * @return
     */
    public static List<StringAndStringList> getFunctionNameAndArgsFromFunctionCallExpression(IASTFunctionCallExpression expression) {
        List<StringAndStringList> result = new ArrayList<>();
        IASTInitializerClause[] arguments = expression.getArguments();
        // 处理参数
        for (IASTInitializerClause clause : arguments) {
            if (clause instanceof IASTFunctionCallExpression) {
                result.addAll(getFunctionNameAndArgsFromFunctionCallExpression((IASTFunctionCallExpression) clause));
            }
        }
        int k = 0;
        String functionName = "";
        List<String> args = new ArrayList<>();
        for (IASTNode node : expression.getChildren()) {
            if (node instanceof IASTIdExpression) {
                if (k == 0) {
                    functionName = node.getRawSignature();
                    k = 1;
                    continue;
                }
                args.add(node.getRawSignature());
            } else if (node instanceof IASTFieldReference) {
                for (IASTNode node1 : node.getChildren()) {
                    if (node1 instanceof IASTName) {
                        args.add(node1.getRawSignature());
                    }
                }
            } else if (node instanceof IASTUnaryExpression) {
                args.addAll(getFunctionNameFromUnaryExpression((IASTUnaryExpression) node));
            }
        }
        result.add(new StringAndStringList(functionName, args));
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

    public static List<StringAndStringList> getFunctionNameAndArgsFromBinaryExpression(IASTBinaryExpression binaryExpression) {
        List<StringAndStringList> nameResult = new ArrayList<>();
        for (IASTNode node : binaryExpression.getChildren()) {
            if (node instanceof IASTFunctionCallExpression) {
                nameResult.addAll(getFunctionNameAndArgsFromFunctionCallExpression((IASTFunctionCallExpression) node));
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
            } else if (node instanceof IASTBinaryExpression) {
                return getFunctionNameFromBinaryExpression((IASTBinaryExpression) node);
            }
        }
        return result;
    }

    public static List<StringAndStringList> getFunctionNameAndArgsFromExpressionStatement(IASTExpressionStatement statement) {
        List<StringAndStringList> result = new ArrayList<>();
        for (IASTNode node : statement.getChildren()) {
            if (node instanceof IASTFunctionCallExpression) {
                IASTFunctionCallExpression functionCallExpression = (IASTFunctionCallExpression) node;
                // 直接的函数调用语句
                // 获取函数名
                return getFunctionNameAndArgsFromFunctionCallExpression(functionCallExpression);
            } else if (node instanceof IASTBinaryExpression) {
                return getFunctionNameAndArgsFromBinaryExpression((IASTBinaryExpression) node);
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

    public static List<StringAndStringList> getFunctionNameAndArgsFromReturnStatement(IASTReturnStatement statement) {
        List<StringAndStringList> result = new ArrayList<>();
        for (IASTNode node : statement.getChildren()) {
            if (node instanceof IASTFunctionCallExpression) {
                // 直接的函数调用语句
                result.addAll(getFunctionNameAndArgsFromFunctionCallExpression((IASTFunctionCallExpression) node));
            } else if (node instanceof IASTBinaryExpression) {
                result.addAll(getFunctionNameAndArgsFromBinaryExpression((IASTBinaryExpression) node));
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

    public static List<StringAndStringList> getFunctionNameAndArgsFromDeclarationStatement(IASTDeclarationStatement statement) {
        List<StringAndStringList> result = new ArrayList<>();
        for (IASTNode node : statement.getChildren()) {
            for (IASTNode node1 : node.getChildren()) {
                if (node1 instanceof IASTDeclarator) {
                    for (IASTNode node2 : node1.getChildren()) {
                        if (node2 instanceof IASTEqualsInitializer) {
                            for (IASTNode node3 : node2.getChildren()) {
                                if (node3 instanceof IASTFunctionCallExpression) {
                                    result.addAll(getFunctionNameAndArgsFromFunctionCallExpression((IASTFunctionCallExpression) node3));
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
//        int arraySize = SIZE_OF_FUNCTION_HASH_SET;            //数组大小取质数
//        int hashCode = 0;
//        for (int i = 0; i < key.length(); i++) {        //从字符串的左边开始计算
//            int letterValue = key.charAt(i) - 40;//将获取到的字符串转换成数字，比如a的码值是97，则97-96=1 就代表a的值，同理b=2；
//            hashCode = ((hashCode << 5) + letterValue + arraySize) % arraySize;//防止编码溢出，对每步结果都进行取模运算
//        }
//        return hashCode;
        return ASTUtil.hashCode(key, SIZE_OF_FUNCTION_HASH_SET);
    }

    /**
     *
     *
     * 以下都是函数指针分析相关--------------------------------------------------------------------------------------
     *
     *
     */

    /**
     * @param compound   就是一个语句簇
     * @param startLayer 这个compound作为整体在函数中的层的一个list
     * @return 一个NumedStatement序列
     */
    public static List<NumedStatement> getStatementsFromCompound(IASTCompoundStatement compound,
                                                                 List<Integer> startLayer) {
        List<NumedStatement> result = new ArrayList<>();
        IASTStatement[] statements = compound.getStatements();
        // seqNum就是这个块里面的语句的序号，每次递增就行
        int seqNum = 1;
        // layerNum就是计算同一层数到第几块了
        int layerNum = 1;
        for (IASTStatement statement : statements) {
            if (haveCompoundNode(statement)) {
                // 说明这是个复合语句体，例如if、for
                List<IASTCompoundStatement> tempCompoundList = getCompoundFromStatement(statement);
                for (IASTCompoundStatement singleCompound : tempCompoundList) {
                    // if的多个分支不能当成同一层级的同一块，必须当成同一层级的不同的块
                    // 外面有定义a，if里面定义a，else里面的a应该是外面的，
                    // 我的判断定义点逻辑是只判断自己这块和上层的，同一块的其他层中的定义我不会看
                    List<Integer> tempLayer = new ArrayList<>(startLayer);
                    tempLayer.add(layerNum);
                    result.addAll(getStatementsFromCompound(singleCompound, tempLayer));
                    layerNum++;
                }
            } else if (statement instanceof IASTCompoundStatement) {
                // 说明这是个简单的块，直接拿大括号括起来那种
                List<Integer> tempLayer = new ArrayList<>(startLayer);
                tempLayer.add(layerNum);
                result.addAll(getStatementsFromCompound((IASTCompoundStatement) statement, tempLayer));
                layerNum++;
            } else {
                // 说明是简单语句，可以标识layer结尾
                NumedStatement simpleStatement = new NumedStatement(statement);
                simpleStatement.addLayer(startLayer);
                simpleStatement.addLayer(-1);
                simpleStatement.setSeqNum(seqNum);
                result.add(simpleStatement);
            }

            seqNum++;
        }

        return result;
    }

    public static void giveFunctionSeq(List<NumedStatement> numedStatements) {
        for (int i = 0; i < numedStatements.size(); i++) {
            numedStatements.get(i).setFunSeq(i);
        }
    }

    /**
     * @param statement 一个语句，如果是if，那么会包含if里的那个块
     * @return 如果语句里包含嵌套Compound，返回true
     */
    public static Boolean haveCompoundNode(IASTStatement statement) {
        for (IASTNode node : statement.getChildren()) {
            if (node instanceof IASTCompoundStatement) {
                return true;
            }
        }
        return false;
    }

    /**
     * 如果是含有compound的，把compound揪出来
     *
     * @param statement
     * @return
     */
    public static List<IASTCompoundStatement> getCompoundFromStatement(IASTStatement statement) {
        List<IASTCompoundStatement> result = new ArrayList<>();
        for (IASTNode node : statement.getChildren()) {
            if (node instanceof IASTCompoundStatement) {
                result.add((IASTCompoundStatement) node);
            } else if (node instanceof IASTIfStatement) {
                result.addAll(getCompoundFromStatement((IASTStatement) node));
            }
        }
        return result;
    }
}
