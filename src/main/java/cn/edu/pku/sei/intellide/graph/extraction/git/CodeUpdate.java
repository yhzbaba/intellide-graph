package cn.edu.pku.sei.intellide.graph.extraction.git;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CCodeFileInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CFunctionInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.GetTranslationUnitUtil;
import org.eclipse.cdt.core.dom.ast.IASTTranslationUnit;
import org.eclipse.core.runtime.CoreException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CodeUpdate extends KnowledgeExtractor {

    private Map<String, GitUpdate.CommitInfo> commitInfos;


    public static void main(String[] args) {
    }

    public CodeUpdate() {}

    public CodeUpdate(Map<String, GitUpdate.CommitInfo> commitInfos) {
        this.commitInfos = commitInfos;
        extraction();
    }

    @Override
    public void extraction() {
        GraphDatabaseService db = this.getDb();
        try (Transaction tx = db.beginTx()) {
            String query = "match (n:c_code_file)-[:define]->(m:c_variable) where n.fileName={ name } return m.name";
            Map<String, Object> properties = new HashMap<>();
            properties.put("name", "fatfs.h");
            Result res = db.execute(query, properties);
            while(res.hasNext()) {
                Map<String, Object> row = res.next();
                for(String col: res.columns()) {
                    System.out.println(row.get(col));
                }
            }
            tx.success();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        /*
        for(GitUpdate.CommitInfo commitInfo: commitInfos.values()) {
            // 先沿 parent 递归处理
            for(String parent: commitInfo.parent) {
                if(commitInfos.containsKey(parent)) {
                    GitUpdate.CommitInfo parentCommit = commitInfos.get(parent);
                    if(!parentCommit.isHandled) {
                        parseCommit(parentCommit);
                        parentCommit.isHandled = true;
                    }
                }
            }
            // 处理完 parent commits
            if(!commitInfo.isHandled) {
                parseCommit(commitInfo);
                commitInfo.isHandled = true;
            }
        }

         */
    }

    private void parseCommit(GitUpdate.CommitInfo commitInfo) {
        List<String> diffInfos = commitInfo.diffInfo;
        for(String diffInfo: diffInfos) {
            System.out.println(diffInfo);
            // 只处理 .h/.c 文件
            if(!diffInfo.contains(".c") && !diffInfo.contains(".h")) {
                continue;
            }
            // 代码项目根目录下的文件路径
            String fileName = diffInfo.substring(diffInfo.lastIndexOf(" ") + 1);
            // 获取修改文件的绝对路径(需要添加项目代码路径作为前缀来进行定位)
            String filePath = fileName;
            System.out.println("filePath: " + filePath);

            // 后续步骤：获取图谱信息：解析代码文件；集合比对

            // 解析被修改的代码文件
            CCodeFileInfo codeFileInfo = getCodeFileInfo(filePath, fileName);
            codeFileInfo.getFunctionInfoList().forEach(CFunctionInfo::initCallFunctionNameList);

            // 获取以该文件为核心的图谱信息（数据库事务执行）
            CCodeFileInfo graphCodeFileInfo = getGraphCodeFileInfo(filePath);

        }
    }

    private CCodeFileInfo getCodeFileInfo(String filePath, String fileName) {
        IASTTranslationUnit translationUnit = null;
        try {
            translationUnit = GetTranslationUnitUtil.getASTTranslationUnit(new File(filePath));
        } catch (CoreException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new CCodeFileInfo(fileName, translationUnit);
    }

    /**
     * 访问数据库，获取代码文件相关联的各列表信息（只有出边关系）
     * @param filePath 文件全路径，用于实体匹配
     */
    private CCodeFileInfo getGraphCodeFileInfo(String filePath) {
        CCodeFileInfo res = new CCodeFileInfo(filePath);
        GraphDatabaseService db = this.getDb();
        try (Transaction tx = db.beginTx()) {
            // 定位到代码文件节点
            Node codeFileNode = db.findNode(CExtractor.c_code_file, CExtractor.FILENAME, filePath);
            // 获取节点出边节点的信息

            tx.success();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return res;
    }
}
