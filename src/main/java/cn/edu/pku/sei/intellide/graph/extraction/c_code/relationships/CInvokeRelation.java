package cn.edu.pku.sei.intellide.graph.extraction.c_code.relationships;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CCodeFileInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CFunctionInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CProjectInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CVariableInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.FunctionUtil;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.VariableUtil;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class CInvokeRelation {
    /**
     * @param inserter value==null: 更新验证使用
     */
    public static void createInvokeRelations(CProjectInfo projectInfo, BatchInserter inserter) {
        projectInfo.getCodeFileInfoMap().values().forEach(cCodeFileInfo -> {
            cCodeFileInfo.getFunctionInfoList().forEach(cFunctionInfo -> {
                cFunctionInfo.setIncludeFileList(cCodeFileInfo.getIncludeCodeFileList());
                cFunctionInfo.initCallFunctionNameAndVariableNameList();
                cFunctionInfo.initNumberedStatementList();
                cFunctionInfo.processImplicitInvoke();
            });
            if (inserter != null) {
                cCodeFileInfo.getFunctionInfoList().forEach(cFunctionInfo -> {
                    // 对函数调用的每一个函数查询其所属信息
                    List<CFunctionInfo> invokeFunctions = new ArrayList<>();
                    List<CVariableInfo> invokeVariables = new ArrayList<>();
                    List<String> oldInvokeFunctions = cFunctionInfo.getCallFunctionNameList();
                    List<String> oldInvokeVariables = cFunctionInfo.getCallVariableNameList();
                    oldInvokeFunctions.forEach(callFunc -> {
                        List<CFunctionInfo> result = getInvokeFunctions(cCodeFileInfo, cFunctionInfo, callFunc);
                        for (CFunctionInfo func : result) {
                            if (func.getId() != -1) {
                                invokeFunctions.add(func);
                            }
                        }
                    });
                    oldInvokeVariables.forEach(callVariable -> {
                        List<CVariableInfo> result = getInvokeVariables(cCodeFileInfo, cFunctionInfo, callVariable);
                        for (CVariableInfo variable : result) {
                            if (variable.getId() != -1) {
                                invokeVariables.add(variable);
                            }
                        }
                    });
                    invokeFunctions.forEach(invokeFunc -> {
                        inserter.createRelationship(cFunctionInfo.getId(), invokeFunc.getId(), CExtractor.invoke, new HashMap<>());
                    });
                    invokeVariables.forEach(invokeVariable -> {
                        inserter.createRelationship(cFunctionInfo.getId(), invokeVariable.getId(), CExtractor.invoke, new HashMap<>());
                    });
                });
            }
        });
    }

    /**
     * 确定被调用的函数对象
     * 和 neo4j-c 版本有出入，没有使用哈希结构
     * param2 调用方的函数节点，而不是被调用方
     */
    private static List<CFunctionInfo> getInvokeFunctions(CCodeFileInfo cCodeFileInfo,
                                                          CFunctionInfo cFunctionInfo,
                                                          String name) {
        List<CFunctionInfo> res = new ArrayList<>();
        List<CFunctionInfo> tempList = FunctionUtil.FUNCTION_HASH_LIST[FunctionUtil.hashFunc(name)];
        if (tempList.size() > 1) {
            List<String> includeCodeFileList = cCodeFileInfo.getIncludeCodeFileList();
            /**
             *
             .h中声明，.c中实现，我include的是.h，又希望指向实现，这种情况加个兜底，如果计数为0，那么就加进去
             因为最后编译不可能存在两个实现
             */
            for (CFunctionInfo info : tempList) {
                if (cFunctionInfo.getBelongTo().equals(info.getBelongTo())) {
                    // (2)
                    res.add(info);
                } else {
                    for (String includeFileName : includeCodeFileList) {
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

    private static List<CVariableInfo> getInvokeVariables(CCodeFileInfo cCodeFileInfo,
                                                          CFunctionInfo cFunctionInfo,
                                                          String name) {
        List<CVariableInfo> res = new ArrayList<>();
        List<CVariableInfo> tempList = VariableUtil.VARIABLE_HASH_LIST[VariableUtil.hashVariable(name)];
        if (tempList.size() > 1) {
            List<String> includeCodeFileList = cCodeFileInfo.getIncludeCodeFileList();
            /**
             *
             .h中声明，.c中实现，我include的是.h，又希望指向实现，这种情况加个兜底，如果计数为0，那么就加进去
             因为最后编译不可能存在两个实现
             */
            for (CVariableInfo info : tempList) {
                if (cFunctionInfo.getBelongTo().equals(info.getBelongTo())) {
                    // (2)
                    res.add(info);
                } else {
                    for (String includeFileName : includeCodeFileList) {
                        if (includeFileName.contains(info.getBelongTo())) {
                            // (1)
                            res.add(info);
                        }
                    }
                }
            }
        } else if (tempList.size() == 1) {
            CVariableInfo only = tempList.get(0);
            // 只查到了一个那就直接扔进去 不然也没啥意义了
            res.add(only);
        }

        return res;
    }
}
