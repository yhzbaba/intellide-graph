package cn.edu.pku.sei.intellide.graph.extraction.c_code.infos;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.GetTranslationUnitUtil;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.ProjectFilesReader;
import lombok.Getter;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.cdt.core.dom.ast.IASTTranslationUnit;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CProjectInfo {
    @Getter
    private Map<String, CCodeFileInfo> codeFileInfoMap = new HashMap<>();

    /**
     * 项目路径下所有文件构造TranslationUnit，构造对应CCodeFileInfo,这个函数结束codeFileInfoMap就构建完成了
     * @param dir 项目路径
     * @throws IOException
     * @throws CoreException
     */
    public void makeTranslationUnits(String dir, BatchInserter inserter) throws IOException, CoreException {
        ProjectFilesReader fileComponent = new ProjectFilesReader(dir);
        List<File> files = fileComponent.getAllFilesAndDirsList();
        for (File file: files) {
            if (file.isFile()){
                String fileFullName = file.getAbsolutePath();
                String fileName = file.getName();
                if(fileName.contains(".")) {
                    String substring = fileName.substring(fileName.lastIndexOf("."));
                    if(!".c".equals(substring) && !".h".equals(substring)){
                        continue;
                    }
                } else {
                    continue;
                }
//                System.out.println(fileName);
                IASTTranslationUnit translationUnit = GetTranslationUnitUtil.getASTTranslationUnit(new File(fileFullName));
                CCodeFileInfo codeFileInfo = new CCodeFileInfo(inserter, fileName, translationUnit);
                codeFileInfoMap.put(fileName, codeFileInfo);
            }
        }
    }
}
