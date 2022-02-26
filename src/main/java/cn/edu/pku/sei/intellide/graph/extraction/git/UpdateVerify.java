package cn.edu.pku.sei.intellide.graph.extraction.git;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.*;
import org.eclipse.core.runtime.CoreException;
import org.neo4j.graphdb.*;

import java.io.IOException;
import java.util.*;

import static org.neo4j.graphdb.Direction.INCOMING;
import static org.neo4j.graphdb.Direction.OUTGOING;

public class UpdateVerify extends KnowledgeExtractor  {

    @Override
    public void extraction() {
        // 解析最终版本的项目代码（dstCodeDir 路径），记录在dstProjectInfo 中
        CProjectInfo dstProjectInfo = new CProjectInfo();
        try {
            dstProjectInfo.makeTranslationUnits(this.getDstCodeDir(), null);
            // 处理函数调用关系
            CExtractor.createInvokeRelations(dstProjectInfo, null);

        } catch (IOException | CoreException e) {
            e.printStackTrace();
        }

        // 访问数据库(graphDir 路径)，获取更新后的项目信息（注意配置 increment 为 true）
        CProjectInfo updateProjectInfo = new CProjectInfo();
        GraphDatabaseService db = this.getDb();
        try(Transaction tx = db.beginTx()) {
            for (ResourceIterator<Node> it = db.findNodes(CExtractor.c_code_file); it.hasNext(); ) {
                // 获取代码文件类对象的属性
                Node codeNode = it.next();
                String fileName = (String) codeNode.getProperty("fileName");
                CCodeFileInfo codeFileInfo = new CCodeFileInfo((String) codeNode.getProperty("tailFileName"), fileName);

                // includeCodeFileList
                List<String> includeCodeFileList = new ArrayList<>();
                if(codeNode.hasRelationship(CExtractor.include)) {
                    for(Relationship rel: codeNode.getRelationships(CExtractor.include, OUTGOING)) {
                        Node dstNode = rel.getEndNode();
                        includeCodeFileList.add((String) dstNode.getProperty("tailFileName"));
                    }
                }
                codeFileInfo.setIncludeCodeFileList(includeCodeFileList);

                List<CVariableInfo> variableInfoList = new ArrayList<>();
                List<CDataStructureInfo> dataStructureList = new ArrayList<>();
                List<CFunctionInfo> functionInfoList = new ArrayList<>();
                if(codeNode.hasRelationship(CExtractor.define)) {
                    for(Relationship rel: codeNode.getRelationships(CExtractor.define, OUTGOING)) {
                        Node dstNode = rel.getEndNode();
                        if(dstNode.hasLabel(CExtractor.c_variable)) {
                            // variable
                            CVariableInfo var = new CVariableInfo();
                            var.setName((String) dstNode.getProperty("name"));
                            var.setBelongTo((String) dstNode.getProperty("belongTo"));
                            var.setIsDefine((Boolean) dstNode.getProperty("isDefine"));
                            var.setIsStructVariable((Boolean) dstNode.getProperty("isStructVariable"));
                            variableInfoList.add(var);
                        }
                        else if(dstNode.hasLabel(CExtractor.c_struct)) {
                            // struct
                            CDataStructureInfo struct = new CDataStructureInfo();
                            struct.setName((String) dstNode.getProperty("name"));
                            struct.setTypedefName((String) dstNode.getProperty("typedefName"));
                            struct.setIsEnum((Boolean) dstNode.getProperty("isEnum"));
                            if(dstNode.hasRelationship(CExtractor.member_of)) {
                                for(Relationship r: dstNode.getRelationships(CExtractor.member_of, INCOMING)) {
                                    // struct field
                                    // TODO: 当前处理还没有考虑普通的域成员，后续再补充
                                    Node srcNode = r.getStartNode();

                                }
                            }
                            dataStructureList.add(struct);
                        }
                        else if(dstNode.hasLabel(CExtractor.c_function)) {
                            // function
                            CFunctionInfo func = new CFunctionInfo();
                            func.setName((String) dstNode.getProperty("name"));
                            func.setFullName((String) dstNode.getProperty("fullName"));
                            func.setBelongTo((String) dstNode.getProperty("belongTo"));
                            func.setBelongToName((String) dstNode.getProperty("belongToName"));
                            func.setIsInline((Boolean) dstNode.getProperty("isInline"));
                            func.setIsConst((Boolean) dstNode.getProperty("isConst"));
                            func.setIsDefine((Boolean) dstNode.getProperty("isDefine"));
//                            func.setFullParams((List<String>) dstNode.getProperty("fullParams"));
                            Set<String> callFunctionNameList = new HashSet<>();
                            if(dstNode.hasRelationship(CExtractor.invoke)) {
                                for(Relationship r: dstNode.getRelationships(CExtractor.invoke, OUTGOING)) {
                                    Node endNode = r.getEndNode();
                                    callFunctionNameList.add((String) endNode.getProperty("name"));
                                }
                            }
                            func.setCallFunctionNameList(callFunctionNameList);
                            functionInfoList.add(func);
                        }
                    }
                }
                codeFileInfo.setVariableInfoList(variableInfoList);
                codeFileInfo.setDataStructureList(dataStructureList);
                codeFileInfo.setFunctionInfoList(functionInfoList);

                updateProjectInfo.addFileInfo(fileName, codeFileInfo);
            }

            tx.success();
        }

        // 对比两个 ProjectInfo 中的信息，验证更新后图谱的一致性
        boolean flag = false;
        for(Map.Entry entry: dstProjectInfo.getCodeFileInfoMap().entrySet()) {
            String fileName = (String) entry.getKey();
            CCodeFileInfo dstCodeFileInfo = (CCodeFileInfo) entry.getValue();
            // 代码文件实体缺失
            if(!updateProjectInfo.getCodeFileInfoMap().containsKey(fileName)) {
                flag = true;
                System.out.println("mismatch code file: " + fileName);
            }
            CCodeFileInfo updateCodeFileInfo = updateProjectInfo.getCodeFileInfoMap().get(fileName);

            // 遍历 include, variable, struct, function
            dstCodeFileInfo.getIncludeCodeFileList().forEach(includeFile -> {
                if(!updateCodeFileInfo.getIncludeCodeFileList().contains(includeFile)) {
                    System.out.println("mismatch include file:" + includeFile);
                }
                else {
                    updateCodeFileInfo.getIncludeCodeFileList().remove(includeFile);
                }
            });
            if(!updateCodeFileInfo.getIncludeCodeFileList().isEmpty()) {
                flag = true;
            }

            dstCodeFileInfo.getVariableInfoList().forEach(var -> {
                if(!updateCodeFileInfo.getVariableInfoList().contains(var)) {
                    System.out.println("mismatch variable: " + var.getName());
                }
                else {
                    updateCodeFileInfo.getVariableInfoList().remove(var);
                }
            });
            if(!updateCodeFileInfo.getVariableInfoList().isEmpty()) {
                flag = true;
            }

            dstCodeFileInfo.getDataStructureList().forEach(ds -> {
                if(!updateCodeFileInfo.getDataStructureList().contains(ds)) {
                    System.out.println("mismatch struct: " + ds.getName());
                }
                else {
                    updateCodeFileInfo.getDataStructureList().remove(ds);
                }
            });
            if(!updateCodeFileInfo.getDataStructureList().isEmpty()) {
                flag = true;
            }

            dstCodeFileInfo.getFunctionInfoList().forEach(func -> {
                if(!updateCodeFileInfo.getFunctionInfoList().contains(func)) {
                    System.out.println("mismatch function:" + func.getName());
                }
                else {
                    updateCodeFileInfo.getFunctionInfoList().remove(func);
                }
            });
            if(!updateCodeFileInfo.getFunctionInfoList().isEmpty()) {
                flag = true;
            }

            updateProjectInfo.getCodeFileInfoMap().remove(fileName);
        }

        if(!updateProjectInfo.getCodeFileInfoMap().isEmpty()) {
            flag = true;
        }
        if(!flag) {
            System.out.println("Update Verification Passed");
        }
    }
}
