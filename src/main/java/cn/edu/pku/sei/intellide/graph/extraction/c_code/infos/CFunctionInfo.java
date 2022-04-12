package cn.edu.pku.sei.intellide.graph.extraction.c_code.infos;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
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

    private List<String> callVariableNameList = new ArrayList<>();

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
//                    System.out.println("? " + statement.getClass() + "->" + statement.getRawSignature());
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
