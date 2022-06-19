package cn.edu.pku.sei.intellide.graph.extraction.c_code.process;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CProjectInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.relationships.CInvokeRelation;
import org.neo4j.unsafe.batchinsert.BatchInserter;

public class CInvokeStoreProcess {
    public static void invokeStoreProcess(CProjectInfo projectInfo, BatchInserter inserter) {
        CInvokeRelation.createInvokeRelations(projectInfo, inserter);
    }
}
