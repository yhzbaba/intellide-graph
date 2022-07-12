package cn.edu.pku.sei.intellide.graph.extraction.c_code.process;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CProjectInfo;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.util.HashMap;

public class CBaseStoreProcess {
    public static void baseStoreProcess(CProjectInfo projectInfo, BatchInserter inserter) {
        projectInfo.getCodeFileInfoMap().values().forEach(cCodeFileInfo -> {
            // 处理单个文件
            cCodeFileInfo.getIncludeCodeFileList().forEach(key -> {
//                if (projectInfo.getCodeFileInfoMap().containsKey(key)) {
//                    inserter.createRelationship(cCodeFileInfo.getId(), projectInfo.getCodeFileInfoMap().get(key).getId(), CExtractor.include, new HashMap<>());
//                }
                for (String file : projectInfo.getCodeFileInfoMap().keySet()) {
                    // 根据不同系统的路径，选择使用 symbol or "/" 来判断
                    if (file.contains(key)) {
                        if (key.contains("/")) {
                            if (!file.substring(file.lastIndexOf("/") + 1).equals(key.substring(key.lastIndexOf("/") + 1))) {
                                continue;
                            }
                        } else {
                            if (!file.substring(file.lastIndexOf("/") + 1).equals(key)) {
                                continue;
                            }
                        }
                        inserter.createRelationship(cCodeFileInfo.getId(), projectInfo.getCodeFileInfoMap().get(file).getId(), CExtractor.include, new HashMap<>());
                        break;
                    }
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
    }
}
