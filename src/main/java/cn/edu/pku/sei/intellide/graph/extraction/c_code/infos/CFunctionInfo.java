package cn.edu.pku.sei.intellide.graph.extraction.c_code.infos;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.FunctionPointerUtil;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.FunctionUtil;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.VariableUtil;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.cdt.core.dom.ast.*;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.util.*;
import java.util.stream.Collectors;

@Data
public class CFunctionInfo {
    @Getter
    private long id = -1;
    @Getter
    @Setter
    private String name;
    @Getter
    @Setter
    private String fullName;
    @Getter
    @Setter
    private String belongTo;
    @Getter
    @Setter
    private List<String> fullParams = new ArrayList<>();
    @Getter
    @Setter
    private Boolean isInline;
    @Getter
    @Setter
    private Boolean isConst;
    @Getter
    @Setter
    private Boolean isDefine;
    @Getter
    @Setter
    private String belongToName;

    private BatchInserter inserter = null;

    /**
     * 当isDefine为true此属性才有效
     */
    @Getter
    @Setter
    private IASTPreprocessorFunctionStyleMacroDefinition macroDefinition;

    /**
     * 当isDefine为false此属性才有效
     */
    @Getter
    @Setter
    private IASTFunctionDefinition functionDefinition;

    /**
     * 记录调用时被调用的地方在哪里，文件名+函数名+函数内位置，有多个就直接往后面add
     */
    @Getter
    @Setter
    private String invokePoint = "";

    /**
     * 这个函数所调用的函数名的列表,初始化里面装的是调用的函数名列表，二次装的是belongToName
     */
    @Getter
    @Setter
    private List<String> callFunctionNameList = new ArrayList<>();

    private List<String> callVariableNameList = new ArrayList<>();

    private List<NameFunctionStack> callFunctionNameStacks = new ArrayList<>();

    private List<String> includeFileList = new ArrayList<>();

    /**
     * 按递归顺序拆分语句
     */
    private List<NumedStatement> statementList = new ArrayList<>();

    /**
     * statementList的长度
     */
    private int numOfStatements = 0;

    /**
     * 初始化语句列表
     */
    public void initNumberedStatementList() {
        if (!isDefine) {
            List<Integer> startLayer = new ArrayList<>();
            startLayer.add(1);
            IASTCompoundStatement compoundStatement = (IASTCompoundStatement) functionDefinition.getBody();
            statementList.addAll(FunctionUtil.getStatementsFromCompound(compoundStatement, startLayer));
            numOfStatements = statementList.size();
//            for (int i = numOfStatements - 1; i >= 0; i--) {
//                System.out.println(statementList.get(i));
//            }
        }
    }

    public CImplicitInvokePoint buildImpInvoke(String invokePoint, NumedStatement numedStatement) {
        CImplicitInvokePoint point = new CImplicitInvokePoint(invokePoint,
                numedStatement.getLayer(),
                numedStatement.getSeqNum());
        if (inserter != null) {
            long pointId = point.createNode(inserter);
            inserter.createRelationship(getId(), pointId, CExtractor.has_imp, new HashMap<>());
        }
        return point;
    }

    public void processImplicitInvoke() {
        if (!isDefine) {
            for (int i = numOfStatements - 1; i >= 0; i--) {
                // 先从后往前检查每个小句子，得到可能的隐式调用点
                NumedStatement numedStatement = statementList.get(i);
                IASTStatement statement = numedStatement.getStatement();
                List<String> invokePoints = new ArrayList<>();
                if (statement instanceof IASTReturnStatement) {
                    invokePoints.addAll(FunctionUtil.getFunctionNameFromReturnStatement((IASTReturnStatement) statement));
                } else if (statement instanceof IASTDeclarationStatement) {
                    invokePoints.addAll(FunctionUtil.getFunctionNameFromDeclarationStatement((IASTDeclarationStatement) statement));
                } else if (statement instanceof IASTExpressionStatement) {
                    invokePoints.addAll(FunctionPointerUtil.getFunctionNameFromExpressionStatement((IASTExpressionStatement) statement));
                }
                // 这句话的可能隐式调用点拿到了，遍历它们，再从这个位置往前寻找定义点(1)
                // 如果找不到找全局(2)
                // 再找不到说明显式调用，交给下一步去做就行了
                // 找定义点的时候只检查本块 + 上层，本层其他块不检查
                for (String invokePoint : invokePoints) {
                    for (int j = i - 1; j >= 0; j--) {
                        NumedStatement numedCheckDeclare = statementList.get(j);
                        if ((numedStatement.isSameLayer(numedCheckDeclare) && numedStatement.getSeqNum() > numedCheckDeclare.getSeqNum())
                                || numedCheckDeclare.getLayer().size() < numedStatement.getLayer().size()) {
                            IASTStatement checkDeclare = numedCheckDeclare.getStatement();
                            if (checkDeclare instanceof IASTDeclarationStatement) {
                                String declareResult = FunctionPointerUtil.getDeclarationLeftName((IASTDeclarationStatement) checkDeclare);
                                String tempInvokePoint = invokePoint;
                                String tempDeclareResult = declareResult;
                                if (!tempInvokePoint.startsWith("*")) {
                                    tempInvokePoint = "*" + invokePoint;
                                }
                                if (!tempDeclareResult.startsWith("*")) {
                                    tempDeclareResult = "*" + declareResult;
                                }
                                if (tempDeclareResult.equals(tempInvokePoint)) {
                                    // 用函数名匹配到变量定义了，那么持久化这个隐式调用点，然后直接检查下一个invokePoint
                                    CImplicitInvokePoint point = buildImpInvoke(invokePoint, numedStatement);
                                    List<CFunctionInfo> probInvokeList = getProbInvokeList(numedCheckDeclare, numedStatement, invokePoint);
                                    point.setProbInvokeFunctions(probInvokeList);
                                    point.getProbInvokeFunctions().forEach(invokeFunc -> {
                                        inserter.createRelationship(point.getId(), invokeFunc.getId(), CExtractor.imp_invoke, new HashMap<>());
                                    });
                                    break;
                                }
                            }
                        }
                        if (j == 0) {
                            // 到这检查完函数的第一句话了，没有，那么就检查全局指针
                            // 匹配到了，那么持久化这个隐式调用点
                            if (invokePoint.startsWith("*")) {
                                // (*fun)();
                                CVariableInfo info = FunctionPointerUtil.isIncludeVariable(invokePoint, belongTo);
                                if (info != null) {
                                    buildAndInvoke(numedStatement, invokePoint, info);
                                } else {
                                    String temp = invokePoint.substring(1);
                                    info = FunctionPointerUtil.isIncludeVariable(temp, belongTo);
                                    if (info != null) {
                                        buildAndInvoke(numedStatement, invokePoint, info);
                                    }
                                }
                            } else {
                                // fun();
                                CVariableInfo info = FunctionPointerUtil.isIncludeVariable(invokePoint, belongTo);
                                if (info != null) {
                                    buildAndInvoke(numedStatement, invokePoint, info);
                                } else {
                                    String temp = "*" + invokePoint;
                                    info = FunctionPointerUtil.isIncludeVariable(temp, belongTo);
                                    if (info != null) {
                                        buildAndInvoke(numedStatement, invokePoint, info);
                                    }
                                }
                            }
                            break;
                        }
                    }
                }
            }
        }
    }

    private void buildAndInvoke(NumedStatement numedStatement, String invokePoint, CVariableInfo info) {
        CImplicitInvokePoint point = buildImpInvoke(invokePoint, numedStatement);
        if (info.getEqualsInitializer() != null) {
            for (IASTNode node : info.getEqualsInitializer().getChildren()) {
                if (node instanceof IASTUnaryExpression) {
                    point.setProbInvokeFunctions(FunctionPointerUtil.getInvokeFunctions(
                            includeFileList,
                            belongTo,
                            ((IASTUnaryExpression) node).getOperand().getRawSignature()));
                    point.getProbInvokeFunctions().forEach(invokeFunc -> {
                        inserter.createRelationship(point.getId(), invokeFunc.getId(), CExtractor.imp_invoke, new HashMap<>());
                    });
                }
            }
        }
    }

    /**
     * 目前解决的挂钩情况：[直接的函数地址挂钩]
     * 从后往前检查挂钩情况：
     * 同一块，若有检查到就直接停（1）
     * 若（1）中没有，起点终点间整个遍历，有就算（2）
     *
     * @param startStatement 从哪一句开始检查起
     * @param endStatement   检查到哪句话之前
     * @return 可能的被调用的函数结点
     */
    public List<CFunctionInfo> getProbInvokeList(
            NumedStatement startStatement,
            NumedStatement endStatement,
            String invokePoint) {
        List<CFunctionInfo> result = new ArrayList<>();
        String tempInvokePoint = "";
        // 让tempInvokePoint确保是*开头
        if (!invokePoint.startsWith("*")) {
            tempInvokePoint = "*" + invokePoint;
        }
        // 先检查同一块
        int t1 = 0;
        for (int i = numOfStatements - 1; i >= 0; i--) {
            NumedStatement check = statementList.get(i);
            if (!(check.isSameLayer(endStatement) && check.getSeqNum() == endStatement.getSeqNum()) && t1 == 0) {
                continue;
            } else {
                t1 = 1;
            }
            if (check.isSameLayer(startStatement) && check.getSeqNum() == startStatement.getSeqNum()) {
                // 说明在同一块检查到declare那句话了
                IASTStatement statement = check.getStatement();
                if (statement instanceof IASTDeclarationStatement) {
                    List<CFunctionInfo> list = FunctionPointerUtil.getInvokeFunctions(
                            includeFileList,
                            belongTo,
                            FunctionPointerUtil.getDeclarationRightName((IASTDeclarationStatement) statement)
                    );
                    if (list.size() > 0) {
                        result.addAll(list);
                        return result;
                    }
                }
                break;
            }
            if (endStatement.isSameLayer(check) && endStatement.getSeqNum() > check.getSeqNum()) {
                // 这里的check都是同一块的
                IASTStatement checkStatement = check.getStatement();
                if (checkStatement instanceof IASTExpressionStatement) {
                    List<CFunctionInfo> list = FunctionPointerUtil.getFunctionListFromExpressionStatement(
                            (IASTExpressionStatement) checkStatement,
                            invokePoint,
                            tempInvokePoint,
                            includeFileList,
                            belongTo);
                    if (list.size() > 0) {
                        result.addAll(list);
                        return result;
                    }
                }
            }
        }
        t1 = 0;
        for (int i = numOfStatements - 1; i >= 0; i--) {
            NumedStatement check = statementList.get(i);
            if (!(check.isSameLayer(endStatement) && check.getSeqNum() == endStatement.getSeqNum()) && t1 == 0) {
                continue;
            } else {
                t1 = 1;
            }
            if (check.isSameLayer(startStatement) && check.getSeqNum() == startStatement.getSeqNum()) {
                // 说明检查到declare那句话了
                IASTStatement statement = check.getStatement();
                if (statement instanceof IASTDeclarationStatement) {
                    List<CFunctionInfo> list = FunctionPointerUtil.getInvokeFunctions(
                            includeFileList,
                            belongTo,
                            FunctionPointerUtil.getDeclarationRightName((IASTDeclarationStatement) statement)
                    );
                    result.addAll(list);
                    return result;
                }
                break;
            }
            if (!check.isSameLayer(endStatement)) {
                IASTStatement checkStatement = check.getStatement();
                if (checkStatement instanceof IASTExpressionStatement) {
                    List<CFunctionInfo> list = FunctionPointerUtil.getFunctionListFromExpressionStatement(
                            (IASTExpressionStatement) checkStatement,
                            invokePoint,
                            tempInvokePoint,
                            includeFileList,
                            belongTo);
                    if (list.size() > 0) {
                        // 第二个循环是有多少就算多少，不用break
                        result.addAll(list);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 处理调用函数名列表
     * c05de99后更新：处理调用函数名列表和variable列表
     */
    public void initCallFunctionNameAndVariableNameList() {
        if (!isDefine) {
            // 这不是宏函数, 也不是声明
            IASTCompoundStatement compoundStatement = (IASTCompoundStatement) functionDefinition.getBody();
            IASTStatement[] statements = compoundStatement.getStatements();
            List<String> callFunctionNameList = new ArrayList<>();
            List<String> callVariableNameList = new ArrayList<>();
            for (IASTStatement statement : statements) {
                if (statement instanceof IASTReturnStatement) {
                    // return语句 可能出现函数调用 return fun1(2); return fun1(2) < fun2 (4);
                    callFunctionNameList.addAll(FunctionUtil.getFunctionNameFromReturnStatement((IASTReturnStatement) statement));
                    callVariableNameList.addAll(VariableUtil.getVariableNameFromReturnStatement((IASTReturnStatement) statement));
                } else if (statement instanceof IASTDeclarationStatement) {
                    // int res = test1();
                    // CASTSimpleDeclaration->void (*fun)();
                    // CASTSimpleDeclaration->void (*fun)() = PFB_1;
                    // 后续：返回类型为typedef名字，在ExpressionStatement那里!!!(haven't done yet)
//                    System.out.println(((IASTDeclarationStatement) statement).getDeclaration().getClass());
//                    System.out.println(((IASTDeclarationStatement) statement).getDeclaration().getClass() + "->"
//                            + ((IASTDeclarationStatement) statement).getDeclaration().getRawSignature());
                    callFunctionNameList.addAll(FunctionUtil.getFunctionNameFromDeclarationStatement((IASTDeclarationStatement) statement));
                    callVariableNameList.addAll(VariableUtil.getVariableNameFromDeclarationStatement((IASTDeclarationStatement) statement));
                } else if (statement instanceof IASTExpressionStatement) {
                    // fun1(2)
                    callFunctionNameList.addAll(FunctionUtil.getFunctionNameFromExpressionStatement((IASTExpressionStatement) statement));
                    callVariableNameList.addAll(VariableUtil.getVariableNameFromExpressionStatement((IASTExpressionStatement) statement));
                } else if (statement instanceof IASTForStatement) {
                    FunctionUtil.getFunctionNameAndUpdate(statement.getChildren(), callFunctionNameList);
                    VariableUtil.getVariableNameAndUpdate(statement.getChildren(), callVariableNameList);
                } else if (statement instanceof IASTWhileStatement) {
                    FunctionUtil.getFunctionNameAndUpdate(statement.getChildren(), callFunctionNameList);
                    VariableUtil.getVariableNameAndUpdate(statement.getChildren(), callVariableNameList);
                } else if (statement instanceof IASTIfStatement) {
                    FunctionUtil.getFunctionNameAndUpdate(statement.getChildren(), callFunctionNameList);
                    VariableUtil.getVariableNameAndUpdate(statement.getChildren(), callVariableNameList);
                } else if (statement instanceof IASTSwitchStatement) {
                    FunctionUtil.getFunctionNameAndUpdate(statement.getChildren(), callFunctionNameList);
                    VariableUtil.getVariableNameAndUpdate(statement.getChildren(), callVariableNameList);
                }
            }
            List<String> filteredFunctionNameList = callFunctionNameList.stream().filter(string -> !string.isEmpty()).collect(Collectors.toList());
            setCallFunctionNameList(filteredFunctionNameList);
            List<String> filteredVariableNameList = callVariableNameList.stream().filter(string -> !string.isEmpty()).collect(Collectors.toList());
            setCallVariableNameList(filteredVariableNameList);
        }
    }

    public void setFunc(CFunctionInfo func) {
        id = func.getId();
        name = func.getName();
        fullName = func.getFullName();
        belongTo = func.getBelongTo();
        belongToName = func.getBelongToName();
        isInline = func.getIsInline();
        isConst = func.getIsConst();
        isDefine = func.getIsDefine();
    }

    public long createNode(BatchInserter inserter) {
        this.inserter = inserter;
        if (id != -1) {
            return id;
        }
        Map<String, Object> map = new HashMap<>();
        map.put(CExtractor.NAME, name);
        map.put(CExtractor.FULLNAME, fullName);
        map.put(CExtractor.BELONGTO, belongTo);
        map.put(CExtractor.BELONGTINAME, belongToName);
        map.put(CExtractor.ISINLINE, isInline);
        map.put(CExtractor.ISCONST, isConst);
        map.put(CExtractor.ISDEFINE, isDefine);
        this.id = inserter.createNode(map, CExtractor.c_function);
        return this.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fullName, belongTo);
    }

    @Override
    public boolean equals(Object obj) {
        CFunctionInfo func = (CFunctionInfo) obj;
        if (callFunctionNameList.size() != func.getCallFunctionNameList().size()) {
            return false;
        }
        for (String f : callFunctionNameList) {
            if (!func.getCallFunctionNameList().contains(f)) {
                return false;
            }
        }
        return (name.equals(func.getName()) && belongTo.equals(func.getBelongTo()));
    }
}
