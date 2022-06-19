package cn.edu.pku.sei.intellide.graph.extraction.c_code;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CCodeFileInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CFunctionInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CProjectInfo;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CVariableInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.process.CInitProcess;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.relationships.CInvokeRelation;
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
    public static final RelationshipType imp_invoke = RelationshipType.withName("imp_invoke");
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

        CInitProcess.init();

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
            CInvokeRelation.createInvokeRelations(projectInfo, inserter);

        } catch (IOException | CoreException e) {
            e.printStackTrace();
        }
    }
}
