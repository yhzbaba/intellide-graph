package cn.edu.pku.sei.intellide.graph.extraction.c_code;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CProjectInfo;

import org.eclipse.core.runtime.CoreException;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.io.IOException;
import java.util.HashMap;

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

    @Override
    public boolean isBatchInsert() {
        return true;
    }

    @Override
    public void extraction() {
        CProjectInfo projectInfo = new CProjectInfo();
        BatchInserter inserter = this.getInserter();
        try {
            projectInfo.makeTranslationUnits(this.getDataDir(), inserter);
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
                        inserter.createRelationship(cFieldInfo.getId(), cDataStructureInfo.getId(), CExtractor.member_of, new HashMap<>());
                    });
                });
                cCodeFileInfo.getVariableInfoList().forEach(cVariableInfo -> {
                    inserter.createRelationship(cCodeFileInfo.getId(), cVariableInfo.getId(), CExtractor.define, new HashMap<>());
                });
            });
        } catch (IOException e) {
            e.printStackTrace();
        } catch (CoreException e) {
            e.printStackTrace();
        }

    }
}
