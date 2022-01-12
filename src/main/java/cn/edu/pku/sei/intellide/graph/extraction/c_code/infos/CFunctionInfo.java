package cn.edu.pku.sei.intellide.graph.extraction.c_code.infos;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.FunctionUtil;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.cdt.core.dom.ast.*;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CFunctionInfo {
    @Getter
    private long id = -1;
    @Getter
    @Setter
    private String name;
    @Getter
    @Setter
    private String content;
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
     * 这个函数所调用的函数名的列表,初始化里面装的是调用的函数名列表，二次装的是belongToName
     */
    @Getter
    @Setter
    private List<String> callFunctionNameList = new ArrayList<>();

    /**
     * 处理调用函数名列表
     */
    public void initCallFunctionNameList() {
        if(!isDefine) {
            // 这不是宏函数
            IASTCompoundStatement compoundStatement = (IASTCompoundStatement)functionDefinition.getBody();
            IASTStatement[] statements = compoundStatement.getStatements();
            List<String> finalResult = new ArrayList<>();
            for (IASTStatement statement : statements) {
                if(statement instanceof IASTReturnStatement) {
                    // return语句 可能出现函数调用 return fun1(2); return fun1(2) < fun2 (4);
                    List<String> returnResult = FunctionUtil.getFunctionNameFromReturnStatement((IASTReturnStatement)statement);
                    finalResult.addAll(returnResult);
                } else if (statement instanceof IASTDeclarationStatement) {
                    // int res = test1();
                    finalResult.addAll(FunctionUtil.getFunctionNameFromDeclarationStatement((IASTDeclarationStatement)statement));
                } else if (statement instanceof IASTExpressionStatement) {
                    // fun1(2)
                    finalResult.addAll(FunctionUtil.getFunctionNameFromExpressionStatement((IASTExpressionStatement)statement));
                } else if (statement instanceof IASTForStatement) {
                    for (IASTNode node : statement.getChildren()) {
                        if(node instanceof IASTBinaryExpression) {
                            List<String> binaryResult = FunctionUtil.getFunctionNameFromBinaryExpression((IASTBinaryExpression)node);
                            finalResult.addAll(binaryResult);
                        } else if (node instanceof IASTCompoundStatement) {
                            List<String> compoundResult = FunctionUtil.getFunctionNameFromCompoundStatement((IASTCompoundStatement)node);
                            finalResult.addAll(compoundResult);
                        }
                    }
                } else if (statement instanceof IASTWhileStatement) {
                    for (IASTNode node : statement.getChildren()) {
                        if(node instanceof IASTBinaryExpression) {
                            List<String> binaryResult = FunctionUtil.getFunctionNameFromBinaryExpression((IASTBinaryExpression)node);
                            finalResult.addAll(binaryResult);
                        } else if (node instanceof IASTCompoundStatement) {
                            List<String> compoundResult = FunctionUtil.getFunctionNameFromCompoundStatement((IASTCompoundStatement)node);
                            finalResult.addAll(compoundResult);
                        }
                    }
                }
            }
            List<String> filtered = finalResult.stream().filter(string -> !string.isEmpty()).collect(Collectors.toList());
            System.out.println(name + "函数的调用情况: " + filtered);
            setCallFunctionNameList(filtered);
        }
    }

    public void setFunc(CFunctionInfo func) {
        name = func.getName();
        content = func.getContent();
        fullName = func.getFullName();
        belongTo = func.getBelongTo();
        belongToName = func.getBelongToName();
        isInline = func.getIsInline();
        isConst = func.getIsConst();
        isDefine = func.getIsDefine();
    }

    public long createNode(BatchInserter inserter) {
        if(id != -1) return id;
        Map<String, Object> map = new HashMap<>();
        map.put(CExtractor.NAME, name);
        map.put(CExtractor.CONTENT, content);
        map.put(CExtractor.FULLNAME, fullName);
        map.put(CExtractor.BELONGTO, belongTo);
        map.put(CExtractor.BELONGTINAME, belongToName);
        map.put(CExtractor.ISINLINE, isInline);
        map.put(CExtractor.ISCONST, isConst);
        map.put(CExtractor.ISDEFINE, isDefine);
        id = inserter.createNode(map, CExtractor.c_function);
        return id;
    }

    @Override
    public int hashCode() {
        return fullName.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        CFunctionInfo func = (CFunctionInfo) obj;
        return this.fullName.equals(func.getFullName());
    }
}
