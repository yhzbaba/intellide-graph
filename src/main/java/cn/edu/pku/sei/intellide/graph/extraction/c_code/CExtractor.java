package cn.edu.pku.sei.intellide.graph.extraction.c_code;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CFunctionInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CProjectInfo;

import org.eclipse.core.runtime.CoreException;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
    public static final String FUNCTIONLIST = "functionList";
    public static final String INCLUDEFILELIST = "includeFileList";
    public static final String DATASTRUCTURELIST = "dataStructureList";
    public static final String VARIABLELIST = "variableList";

    @Override
    public boolean isBatchInsert() {
        return true;
    }

    @Override
    public void extraction() {
        CProjectInfo projectInfo = new CProjectInfo();
        BatchInserter inserter = this.getInserter();
        try {
            // 完成创建节点的工作
            projectInfo.makeTranslationUnits(this.getDataDir(), inserter);
            // 创建节点之间的关系
            projectInfo.getCodeFileInfoMap().values().forEach(cCodeFileInfo -> {
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
                        //bug: 相同节点连边会创建多次
                        inserter.createRelationship(cFieldInfo.getId(), cDataStructureInfo.getId(), CExtractor.member_of, new HashMap<>());
                    });
                });
                cCodeFileInfo.getVariableInfoList().forEach(cVariableInfo -> {
                    inserter.createRelationship(cCodeFileInfo.getId(), cVariableInfo.getId(), CExtractor.define, new HashMap<>());
                });
            });
            projectInfo.getCodeFileInfoMap().values().forEach(cCodeFileInfo -> {
                cCodeFileInfo.getFunctionInfoList().forEach(CFunctionInfo::initCallFunctionNameList);
                cCodeFileInfo.getFunctionInfoList().forEach(cFunctionInfo -> {
                    List<String> newFilter = new ArrayList<>();
                    List<String> old = cFunctionInfo.getCallFunctionNameList();
                    assert old != null;
                    for (String s : old) {
                        // #include "include/abcd.h" 完整路径
                        // 看类图挨个处理
                        List<CFunctionInfo> tempList = getFunctionFromName(projectInfo, s);
                        if(tempList.size() > 1) {
                            List<String> includeCodeFileList = cCodeFileInfo.getIncludeCodeFileList();
//                            System.out.println(includeCodeFileList);
                            for (CFunctionInfo info : tempList) {
                                if (cFunctionInfo.getBelongTo().equals(info.getBelongTo())) {
                                    // (2)
                                    newFilter.add(info.getBelongTo() + s);
                                } else {
                                    for (String includeFileName : includeCodeFileList) {
                                        if(includeFileName.contains(info.getBelongTo())) {
                                            // (1)
                                            newFilter.add(info.getBelongTo() + s);
                                        }
                                    }
                                }
                            }
                        } else if (tempList.size() == 1) {
                            // 只查到了一个那就直接扔进去 不然也没啥意义了
                            newFilter.add(cFunctionInfo.getBelongTo() + s);
                        }
                    }
//                    System.out.println("filter: " + newFilter);
                    cFunctionInfo.setCallFunctionNameList(newFilter);
                });
                cCodeFileInfo.getFunctionInfoList().forEach(cFunctionInfo -> {
                    cFunctionInfo.getCallFunctionNameList().forEach(name -> {
                        // 查找函数修改后不需要这里的判断
                        CFunctionInfo tmp = getCalledFunction(projectInfo, name);
                        if(tmp.getId() != -1)
                            inserter.createRelationship(cFunctionInfo.getId(), tmp.getId(), CExtractor.invoke, new HashMap<>());
                    });
                });
            });
        } catch (IOException e) {
            e.printStackTrace();
        } catch (CoreException e) {
            e.printStackTrace();
        }

    }

    /**
     * 下面的两个查找方法尚待修改（增加路径信息）
     */

    private List<CFunctionInfo> getFunctionFromName(CProjectInfo projectInfo, String name) {
        List<CFunctionInfo> res = new ArrayList<>();
        projectInfo.getCodeFileInfoMap().values().forEach(cCodeFileInfo -> {
            cCodeFileInfo.getFunctionInfoList().forEach(func -> {
                if(func.getFullName().contains(name)) {
                    res.add(func);
                }
            });
        });
        return res;
    }

    private CFunctionInfo getCalledFunction(CProjectInfo projectInfo, String name) {
        CFunctionInfo res = new CFunctionInfo();
        projectInfo.getCodeFileInfoMap().values().forEach(cCodeFileInfo -> {
            cCodeFileInfo.getFunctionInfoList().forEach(func -> {
                if(name.contains(func.getName()) || func.getName().contains(name)) {
                    res.setFunc(func);
                }
            });
        });
        return res;
    }
}
