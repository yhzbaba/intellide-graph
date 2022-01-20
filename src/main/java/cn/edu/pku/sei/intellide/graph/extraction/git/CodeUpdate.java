package cn.edu.pku.sei.intellide.graph.extraction.git;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CCodeFileInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CFunctionInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.GetTranslationUnitUtil;
import org.eclipse.cdt.core.dom.ast.IASTTranslationUnit;
import org.eclipse.core.runtime.CoreException;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 根据 commit 信息更新代码图谱，在 GitUpdate.java 中被调用
 */
public class CodeUpdate extends KnowledgeExtractor {

    private Map<String, GitUpdate.CommitInfo> commitInfos;

    private boolean isGlobalChange = false;
    private List<String> changedFunctions = new ArrayList<String>();


    public static void main(String[] args) {
        /*
        一个 diffInfo 的示例：

        kernel/common/los_printf.c:
        diff --git a/kernel/common/los_printf.c b/kernel/common/los_printf.c
        index e5d0b77..8d55bb9 100644
        --- a/kernel/common/los_printf.c
        +++ b/kernel/common/los_printf.c
        @@ -47,6 +47,7 @@
         #include \"los_excinfo_pri.h\"
         #endif
         #include \"los_exc_pri.h\"
        +#include \"los_sched_pri.h\"

         #define SIZEBUF 256

        @@ -94,7 +95,7 @@

         for (;;) {
         cnt = write(STDOUT_FILENO, str + written, (size_t)toWrite);
        - if ((cnt < 0) || ((cnt == 0) && (OS_INT_ACTIVE)) || (toWrite == cnt)) {
        + if ((cnt < 0) || ((cnt == 0) && ((!OsPreemptable()) || (OS_INT_ACTIVE))) || (toWrite == cnt)) {
         break;
         }
         written += cnt;
         */
        String lines[] = "".split("\\r?\\\\n");
        for(String ss: lines) {
            System.out.println(ss);
        }
    }

    public CodeUpdate() {}

    public CodeUpdate(Map<String, GitUpdate.CommitInfo> commitInfos) {
        this.commitInfos = commitInfos;
        extraction();
    }

    @Override
    public void extraction() {
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
    }

    private void initDS() {
        isGlobalChange = false;
        changedFunctions.clear();
    }

    /**
     * 针对单个 commit 的 diff_info 内容进行处理
     * diff_info format: { parent_commit_name: { modified_fileName: diff_information } }
     * @param commitInfo
     */
    private void parseCommit(GitUpdate.CommitInfo commitInfo) {
        JSONArray diffInfos = commitInfo.diffInfo;
        for(int i = 0;i < diffInfos.size();i++) {
            // 只涉及单个代码文件的 diff 信息
            JSONObject diff = diffInfos.getJSONObject(i);
            // 初始化用于记录的全局数据结构
            initDS();
            for(Map.Entry<String, Object> entry: diff.entrySet()) {
                // 代码项目根目录下的文件路径
                String fileName = entry.getKey();
                // 只处理 .h/.c 文件
                if(!fileName.contains(".c") && !fileName.contains(".h")) {
                    continue;
                }
                // 定位文件内部的具体位置
                locateInnerFile((String) entry.getValue());


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
    }

    /**
     * 对于单个代码文件的commit diff，利用其中的 @@ 行的内容具体定位
     * @param diffs 一个文件所有的diff信息，需要进一步分割
     */
    private void locateInnerFile(String diffs) {
        String[] lines = diffs.split("\\r?\\\\n");
        for(String line: lines) {
            if(line.contains("@@")) {
                if(line.lastIndexOf("@") == line.length() - 1) {
                    // 存在全局信息的修改
                    isGlobalChange = true;
                }
                else {
                    // 非全局修改
                    String info = line.substring(line.lastIndexOf("@") + 1);
                    System.out.println(info);

                }
            }
        }
    }

    /**
     * 对代码文件调用 c_code 解析插件
     * @param filePath 项目数据路径
     * @param fileName 项目根目录下的文件路径
     * @return 代码文件的类对象
     */
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
        /*
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
        */
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
