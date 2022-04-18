package cn.edu.pku.sei.intellide.graph.extraction.c_code;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CCodeFileInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CFunctionInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CProjectInfo;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CVariableInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.FunctionPointerUtil;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.FunctionUtil;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.VariableUtil;
import org.eclipse.core.runtime.CoreException;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.io.IOException;
import java.util.*;

public class CExtractor extends KnowledgeExtractor {
    public static final Label c_alias = Label.label("c_alias");
    public static final Label c_code_file = Label.label("c_code_file");
    public static final Label c_struct = Label.label("c_struct");
    public static final Label c_field = Label.label("c_field");
    public static final Label c_function = Label.label("c_function");
    public static final Label c_imp_invoke = Label.label("c_imp_invoke");
    public static final Label c_variable = Label.label("c_variable");
    public static final RelationshipType define = RelationshipType.withName("define");
    public static final RelationshipType include = RelationshipType.withName("include");
    public static final RelationshipType member_of = RelationshipType.withName("member_of");
    public static final RelationshipType invoke = RelationshipType.withName("invoke");
    public static final RelationshipType has_imp = RelationshipType.withName("has_imp");
    public static final String NAME = "name";
    public static final String FILENAME = "fileName";
    public static final String FULLNAME = "fullName";
    public static final String ORIGINTYPE = "originType";
    public static final String COMMENT = "comment";
    public static final String CONTENT = "content";
    public static final String ISENUM = "isEnum";
    public static final String TYPE = "type";
    public static final String TYPEDEFNAME = "typedefName";
    public static final String BELONGTO = "belongTo";
    public static final String BELONGTINAME = "belongToName";
    public static final String ISINLINE = "isInline";
    public static final String ISCONST = "isConst";
    public static final String ISDEFINE = "isDefine";
    public static final String ISSTRUCTVARIABLE = "isStructVariable";
    public static final String FILEFULLNAME = "fileFullName";
    public static final String TAILFILENAME = "tailFileName";
    public static final String ISFUNCTIONPOINTER = "isFunctionPointer";
    public static final String LAYER = "layer";
    public static final String SEQNUM = "seqNum";

    @Override
    public boolean isBatchInsert() {
        return true;
    }

    @Override
    public void extraction() {
        CProjectInfo projectInfo = new CProjectInfo();
        for (int i = 0; i < FunctionUtil.SIZE_OF_FUNCTION_HASH_SET; i++) {
            FunctionUtil.FUNCTION_HASH_LIST[i] = new ArrayList<>();
        }
        for (int i = 0; i < VariableUtil.SIZE_OF_VARIABLE_HASH_SET; i++) {
            VariableUtil.VARIABLE_HASH_LIST[i] = new ArrayList<>();
        }
        for (int i = 0; i < FunctionPointerUtil.SIZE_OF_FUNCTION_POINTER_HASH_SET; i++) {
            FunctionPointerUtil.FUNCTION_POINTER_HASH_LIST[i] = new ArrayList<>();
        }
        BatchInserter inserter = this.getInserter();
        try {
            // 完成创建节点的工作
            projectInfo.makeTranslationUnits(this.getDataDir(), inserter);

            // 创建节点之间的关系
            projectInfo.getCodeFileInfoMap().values().forEach(cCodeFileInfo -> {
                // 处理单个文件
                cCodeFileInfo.getIncludeCodeFileList().forEach(key -> {
                    if (projectInfo.getCodeFileInfoMap().containsKey(key)) {
                        inserter.createRelationship(cCodeFileInfo.getId(), projectInfo.getCodeFileInfoMap().get(key).getId(), CExtractor.include, new HashMap<>());
                    }
                });
                cCodeFileInfo.getFunctionInfoList().forEach(cFunctionInfo -> {
                    inserter.createRelationship(cCodeFileInfo.getId(), cFunctionInfo.getId(), CExtractor.define, new HashMap<>());
                });

                cCodeFileInfo.getDataStructureList().forEach(cDataStructureInfo -> {
                    inserter.createRelationship(cCodeFileInfo.getId(), cDataStructureInfo.getId(), CExtractor.define, new HashMap<>());
                    cDataStructureInfo.getFieldInfoList().forEach(cFieldInfo -> {
                        inserter.createRelationship(cFieldInfo.getId(), cDataStructureInfo.getId(), CExtractor.member_of, new HashMap<>());
                    });
                });
                cCodeFileInfo.getVariableInfoList().forEach(cVariableInfo -> {
                    inserter.createRelationship(cCodeFileInfo.getId(), cVariableInfo.getId(), CExtractor.define, new HashMap<>());
                });
            });
            // 处理函数调用关系
            createInvokeRelations(projectInfo, inserter);

        } catch (IOException | CoreException e) {
            e.printStackTrace();
        }
    }

    /**
     * @param inserter value==null: 更新验证使用
     */
    public static void createInvokeRelations(CProjectInfo projectInfo, BatchInserter inserter) {
        projectInfo.getCodeFileInfoMap().values().forEach(cCodeFileInfo -> {
            cCodeFileInfo.getFunctionInfoList().forEach(cFunctionInfo -> {
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
