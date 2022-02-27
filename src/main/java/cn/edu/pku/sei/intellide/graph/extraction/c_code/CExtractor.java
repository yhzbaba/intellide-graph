package cn.edu.pku.sei.intellide.graph.extraction.c_code;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CCodeFileInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CFunctionInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CProjectInfo;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.FunctionUtil;
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
    public static final Label c_variable = Label.label("c_variable");
    public static final RelationshipType define = RelationshipType.withName("define");
    public static final RelationshipType include = RelationshipType.withName("include");
    public static final RelationshipType member_of = RelationshipType.withName("member_of");
    public static final RelationshipType invoke = RelationshipType.withName("invoke");
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
        BatchInserter inserter = this.getInserter();
        try {
            // 完成创建节点的工作
            projectInfo.makeTranslationUnits(this.getDataDir(), inserter);

            // 创建节点之间的关系
            projectInfo.getCodeFileInfoMap().values().forEach(cCodeFileInfo -> {
                // 处理单个文件
                cCodeFileInfo.getIncludeCodeFileList().forEach(key -> {
                    if(projectInfo.getCodeFileInfoMap().containsKey(key)) {
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
            cCodeFileInfo.getFunctionInfoList().forEach(CFunctionInfo::initCallFunctionNameList);
            if(inserter != null) {
                cCodeFileInfo.getFunctionInfoList().forEach(cFunctionInfo -> {
                    // 对函数调用的每一个函数查询其所属信息
                    Set<CFunctionInfo> invokeFunctions = new HashSet<>();
                    cFunctionInfo.getCallFunctionNameList().forEach(callFunc -> {
                        List<CFunctionInfo> result = getInvokeFunctions(projectInfo, cCodeFileInfo, callFunc);
                        for(CFunctionInfo func: result) {
                            if (func.getId() != -1) {
                                invokeFunctions.add(func);
                            }
                        }
                    });
                    invokeFunctions.forEach(invokeFunc -> {
                        inserter.createRelationship(cFunctionInfo.getId(), invokeFunc.getId(), CExtractor.invoke, new HashMap<>());
                    });
                });
            }
        });
    }

    /**
     * 确定被调用的函数对象
     * 和 neo4j-c 版本有出入，没有使用哈希结构
     */
    private static List<CFunctionInfo> getInvokeFunctions(CProjectInfo projectInfo, CCodeFileInfo cCodeFileInfo, String name) {
        List<CFunctionInfo> res = new ArrayList<>();
        // 调用文件内的函数
        for(CFunctionInfo func: cCodeFileInfo.getFunctionInfoList()) {
            if(func.getName().equals(name) || func.getFullName().contains(name)) {
                res.add(func);
                break;
            }
        }
        // 调用外部 include 文件的函数
        List<String> includeFiles = cCodeFileInfo.getIncludeCodeFileList();
        for (CCodeFileInfo codeFileInfo : projectInfo.getCodeFileInfoMap().values()) {
            if (!includeFiles.contains(codeFileInfo.getFileName())) continue;
            for (CFunctionInfo func : codeFileInfo.getFunctionInfoList()) {
                if (func.getName().equals(name) || func.getFullName().contains(name)) {
                    res.add(func);
                }
            }
        }
        return res;
    }
}
