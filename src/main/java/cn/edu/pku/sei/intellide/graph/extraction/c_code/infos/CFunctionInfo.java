package cn.edu.pku.sei.intellide.graph.extraction.c_code.infos;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity.GetProbInvokeListReturnClass;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity.IsArgOfFunctionReturnClass;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity.NameFunctionStack;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity.NumedStatement;
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

    private Map<Integer, CImplicitInvokePoint> invokePointMap = new HashMap<>();

    /**
     * statementList的长度
     */
    private int numOfStatements = 0;

    public CFunctionInfo() {
    }

    public CFunctionInfo(String name, String fullName, String belongTo, Boolean isInline, Boolean isConst, Boolean isDefine, String belongToName, IASTFunctionDefinition functionDefinition) {
        this.name = name;
        this.fullName = fullName;
        this.belongTo = belongTo;
        this.isInline = isInline;
        this.isConst = isConst;
        this.isDefine = isDefine;
        this.belongToName = belongToName;
        this.functionDefinition = functionDefinition;
    }

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
            FunctionUtil.giveFunctionSeq(statementList);
        }
    }

    /**
     * 1、根据调用点标识符以及这句话的作用域信息，构造一个 CImplicitInvokePoint 类型的隐式调用结点
     * 2、构建函数拥有（has_imp）该结点的关系
     *
     * @param invokePoint    调用点的标识符
     * @param numedStatement 调用域所在的作用域信息
     * @return 返回这么一个隐式调用结点
     */
    public CImplicitInvokePoint buildImpInvoke(String invokePoint, NumedStatement numedStatement) {
        CImplicitInvokePoint point = new CImplicitInvokePoint(invokePoint,
                numedStatement.getLayer(),
                numedStatement.getSeqNum());
        invokePointMap.put(numedStatement.getFunSeq(), point);
        if (inserter != null) {
            long pointId = point.createNode(inserter);
            inserter.createRelationship(getId(), pointId, CExtractor.has_imp, new HashMap<>());
        }
        return point;
    }

    /**
     * 大体逻辑：
     * 找到第一次赋值点，在这之前，如果有调用就构造imp_invoke与has_imp，如果有作为参数传走就递归
     *
     * @param index
     * @param list
     * @param originFunction 原始调用方的函数结点
     * @param k              原始调用方哪句话调用了自己
     */
    public void updateImplicitInvoke(int index, List<CFunctionInfo> list, CFunctionInfo originFunction, int k) {
        String invokePointUnStar = fullParams.get(index).split(" ")[1];
        if (invokePointUnStar.startsWith("*")) {
            invokePointUnStar = invokePointUnStar.substring(1);
        }
        String invokePoint = invokePointUnStar.startsWith("*") ? invokePointUnStar : "*" + invokePointUnStar;
        int firstCheckNum = 0;
        int lastCheckNum = numOfStatements - 1;
        // 当changeFrom被设为true时，下次循环from就是false
        // 这是因为，找到一个赋值点，说明下次循环找赋值点下面的调用点时，就不是from原来的那个调用函数了
        boolean from = true;
        boolean changeFrom = false;
        while (firstCheckNum < numOfStatements) {
            List<CFunctionInfo> tempList = new ArrayList<>();
            for (int i = firstCheckNum; i < numOfStatements; i++) {
                NumedStatement numedStatement = statementList.get(i);
                IASTStatement statement = numedStatement.getStatement();
                if (statement instanceof IASTExpressionStatement) {
                    for (IASTNode node : statement.getChildren()) {
                        if (node instanceof IASTBinaryExpression) {
                            IASTBinaryExpression binaryNode = (IASTBinaryExpression) node;
                            if (binaryNode.getOperator() == 17) {
                                // 17说明是赋值语句
                                String operand1 = binaryNode.getOperand1().getRawSignature();
                                operand1 = operand1.startsWith("*") ? operand1 : "*" + operand1;
                                if (operand1.equals(invokePoint)) {
                                    lastCheckNum = i;
                                    changeFrom = true;
                                    String operand2 = binaryNode.getInitOperand2().getRawSignature();
                                    // 确定右侧不以&开头
                                    if (operand2.startsWith("&")) {
                                        operand2 = operand2.substring(1);
                                    }
                                    tempList = FunctionPointerUtil.getInvokeFunctions(includeFileList, belongTo, operand2);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            // 第二个循环，寻找invokePoint是否成为了新函数的调用点，构建has_imp与imp_invoke的关系
            for (int j = firstCheckNum; j <= lastCheckNum; j++) {
                NumedStatement numedStatement = statementList.get(j);
                IASTStatement statement = numedStatement.getStatement();
                List<String> invokePoints = new ArrayList<>();
                if (statement instanceof IASTReturnStatement) {
                    invokePoints.addAll(FunctionUtil.getFunctionNameFromReturnStatement((IASTReturnStatement) statement));
                } else if (statement instanceof IASTDeclarationStatement) {
                    invokePoints.addAll(FunctionUtil.getFunctionNameFromDeclarationStatement((IASTDeclarationStatement) statement));
                } else if (statement instanceof IASTExpressionStatement) {
                    invokePoints.addAll(FunctionUtil.getFunctionNameFromExpressionStatement((IASTExpressionStatement) statement));
                }
                for (String singleInvokePoint : invokePoints) {
                    if (singleInvokePoint.equals(invokePointUnStar)) {
                        buildAndInvokeFromKnown(numedStatement, singleInvokePoint, list, originFunction, k, index, from);
                    }
                }
            }
            // 第三个循环，看看这个invokePoint在重新赋值之前有没有作为参数去其他的函数，若有，递归更新
            for (int kk = firstCheckNum; kk <= lastCheckNum; kk++) {
                // 找到一个包含invokePoint为参数的函数调用，然后直接break
                IASTStatement statement1 = statementList.get(kk).getStatement();
                IsArgOfFunctionReturnClass returnClass = FunctionPointerUtil.isArgOfFunction(statement1, invokePointUnStar, includeFileList, belongTo);
                if (returnClass != null && returnClass.getFunctionList().size() > 0) {
                    returnClass.getFunctionList().get(0).updateImplicitInvoke(returnClass.getIndex(), list, this, kk);
                    break;
                }
            }
            firstCheckNum = lastCheckNum + 1;
            lastCheckNum = numOfStatements - 1;
            list = tempList;
            if (changeFrom) {
                from = false;
            }
        }
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
                    invokePoints.addAll(FunctionUtil.getFunctionNameFromExpressionStatement((IASTExpressionStatement) statement));
                }
                // 这句话的可能隐式调用点拿到了，遍历它们，再从这个位置往前寻找定义点(1)
                // 如果找不到找全局(2)
                // 再找不到说明显式调用，交给下一步去做就行了
                // 找定义点的时候只检查本块 + 上层，本层其他块不检查
                // 这个循环是这句话的所有invokePoint
                for (String invokePoint : invokePoints) {
                    // 是从第一句话找到的invokePoint，直接从全局找就行，找完break即可
                    if (i == 0) {
                        CVariableInfo info = FunctionPointerUtil.isIncludeVariable(invokePoint, belongTo);
                        if (invokePoint.startsWith("*")) {
                            // (*fun)();
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

                    // 从这往前找
                    for (int j = i - 1; j >= 0; j--) {
                        NumedStatement numedCheckDeclare = statementList.get(j);
                        if (FunctionPointerUtil.isSameOrUpStat(numedStatement, numedCheckDeclare)) {
                            IASTStatement checkDeclare = numedCheckDeclare.getStatement();
                            if (checkDeclare instanceof IASTDeclarationStatement) {
                                String declareResult = FunctionPointerUtil.getDeclarationLeftName((IASTDeclarationStatement) checkDeclare);
                                String tempInvokePoint = invokePoint.startsWith("*") ? invokePoint : "*" + invokePoint;
                                String tempDeclareResult = declareResult.startsWith("*") ? declareResult : "*" + declareResult;
                                if (tempDeclareResult.equals(tempInvokePoint)) {
                                    // 用函数名匹配到变量定义了，那么持久化这个隐式调用点，然后直接检查下一个invokePoint
                                    CImplicitInvokePoint point = buildImpInvoke(invokePoint, numedStatement);
                                    // 找到赋值点，我的目的是赋值点后面第一句（将包含invokePoint作为参数的函数）揪出来
                                    // 然后检查新函数时把（参数序号、已赋值的函数结点）传进去
                                    // 这个函数获得的是，(赋值点语句及对应被调用函数结点)的一个List
                                    List<GetProbInvokeListReturnClass> probInvokeAllInfoList = getProbInvokeList(numedCheckDeclare, numedStatement, invokePoint);
                                    List<CFunctionInfo> probInvokeList = GetProbInvokeListReturnClass.getAllFunctions(probInvokeAllInfoList);
                                    point.setProbInvokeFunctions(probInvokeList);
                                    point.getProbInvokeFunctions().forEach(invokeFunc -> {
                                        inserter.createRelationship(point.getId(), invokeFunc.getId(), CExtractor.imp_invoke, new HashMap<>());
                                    });

                                    // 要带着已知信息去检查新函数了！
                                    for (GetProbInvokeListReturnClass info : probInvokeAllInfoList) {
                                        NumedStatement infoNumedStatement = info.getNumedStatement();
                                        for (int k = infoNumedStatement.getFunSeq() + 1; k < numOfStatements; k++) {
                                            // 找到一个包含invokePoint为参数的函数调用，找到这个invokePoint对应的新赋值点就break
                                            IASTStatement statement1 = statementList.get(k).getStatement();
                                            IsArgOfFunctionReturnClass returnClass = FunctionPointerUtil.isArgOfFunction(statement1, invokePoint, includeFileList, belongTo);
                                            if (returnClass != null) {
                                                if (returnClass.getFunctionList().size() > 0) {
                                                    returnClass.getFunctionList().get(0).updateImplicitInvoke(returnClass.getIndex(), probInvokeList, this, k);
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                    break;
                                }
                            }
                        }
                        if (j == 0) {
                            // 到这检查完函数的第一句话了，没有，那么就检查全局指针
                            // 匹配到了，那么持久化这个隐式调用点
                            // typedef void (*fun) (void);
                            // fun fun1 = &real_function();
                            CVariableInfo info = FunctionPointerUtil.isIncludeVariable(invokePoint, belongTo);
                            if (invokePoint.startsWith("*")) {
                                // (*fun)();
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

    /**
     * 把这句话作用域输入进去，并且根据定义点定义的那个variable构建has_imp和imp_invoke
     *
     * @param numedStatement 隐式调用所在的statement的作用域信息
     * @param invokePoint    隐式调用点的那个标识符
     * @param info           定义点定义的变量
     */
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
     * @param numedStatement
     * @param invokePoint
     * @param list
     * @param originFunction 原始调用方的函数结点
     * @param k              原始调用方传入参数那句话是第几句话
     * @param index          这个调用点来源于第几个参数
     */
    private void buildAndInvokeFromKnown(NumedStatement numedStatement, String invokePoint, List<CFunctionInfo> list,
                                         CFunctionInfo originFunction, int k, int index, boolean needFrom) {
        CImplicitInvokePoint point = buildImpInvoke(invokePoint, numedStatement);
        point.setProbInvokeFunctions(list);
        point.getProbInvokeFunctions().forEach(invokeFunc -> {
            inserter.createRelationship(point.getId(), invokeFunc.getId(), CExtractor.imp_invoke, new HashMap<>());
        });
        if (needFrom) {
            Map<String, Object> fromMap = new HashMap<>();
            fromMap.put("paramIndex", index);
            fromMap.put("originStatementIndex", k);
            inserter.createRelationship(point.getId(), originFunction.getId(), CExtractor.from, fromMap);
        }
    }

    /**
     * 目前解决的挂钩情况：[直接的函数地址挂钩]
     * 从后往前检查挂钩情况：
     * 同一块，若有检查到就直接停（1）
     * 若（1）中没有，起点终点间整个遍历，有就算（2）
     * <p>
     * 算法逻辑：根据定义点位置：
     * 1、在同一块中，那么就只找同一块语句，找到一个赋值点那就是了，然后直接返回结果
     * 2、在上面语句中，那么中间所有的赋值点
     *
     * @param startStatement 从哪一句开始检查起
     * @param endStatement   检查到哪句话之前
     * @return 可能的被调用的函数结点
     */
    public List<GetProbInvokeListReturnClass> getProbInvokeList(
            NumedStatement startStatement,
            NumedStatement endStatement,
            String invokePoint) {
        List<GetProbInvokeListReturnClass> result = new ArrayList<>();
        // 让tempInvokePoint确保是*开头
        String tempInvokePoint = invokePoint.startsWith("*") ? invokePoint : "*" + invokePoint;
        // 先检查同一块
        int t1 = 0;
        for (int i = numOfStatements - 1; i >= 0; i--) {
            // 跳过endStatement到函数结尾之间的语句
            NumedStatement check = statementList.get(i);
            if (!(check.isSameLayer(endStatement) && check.getSeqNum() == endStatement.getSeqNum()) && t1 == 0) {
                continue;
            } else {
                t1 = 1;
            }
            if (check.isSameLayer(startStatement) && check.getSeqNum() == startStatement.getSeqNum()) {
                // 说明在同一块检查到declare那句话了，那么就不继续找了，注意有break
                IASTStatement statement = check.getStatement();
                if (statement instanceof IASTDeclarationStatement) {
                    List<CFunctionInfo> list = FunctionPointerUtil.getInvokeFunctions(
                            includeFileList,
                            belongTo,
                            FunctionPointerUtil.getDeclarationRightName((IASTDeclarationStatement) statement)
                    );
                    if (list.size() > 0) {
                        result.add(new GetProbInvokeListReturnClass(check, list));
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
                        result.add(new GetProbInvokeListReturnClass(check, list));
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
                    result.add(new GetProbInvokeListReturnClass(check, list));
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
                        result.add(new GetProbInvokeListReturnClass(check, list));
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
