package cn.edu.pku.sei.intellide.graph.extraction.c_code.process;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CCodeFileInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CProjectInfo;
import org.eclipse.core.runtime.CoreException;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.io.IOException;

public class CHandleASTProcess {
    public static void initSingleAST(CCodeFileInfo info) {
        info.initDataStructures();
        info.initFunctions();
        info.initIncludeCodeFiles();
        info.initVariables();
    }

    public static void handleAST(CProjectInfo projectInfo, String dir, BatchInserter inserter) throws IOException, CoreException {
        projectInfo.makeTranslationUnits(dir, inserter);
    }
}
