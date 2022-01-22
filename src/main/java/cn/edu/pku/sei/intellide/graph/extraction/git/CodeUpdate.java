package cn.edu.pku.sei.intellide.graph.extraction.git;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.*;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.GetTranslationUnitUtil;
import lombok.experimental.var;
import org.eclipse.cdt.core.dom.ast.IASTTranslationUnit;
import org.eclipse.core.runtime.CoreException;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 根据 commit 信息更新代码图谱，在 GitUpdate.java 中被调用
 */
public class CodeUpdate extends KnowledgeExtractor {

    private Map<String, GitUpdate.CommitInfo> commitInfos;

    /**
     * 记录修改涉及到的代码信息，每次需要初始化
     */
    private boolean isGlobalModify = false;
    private boolean isGlobalAdd = false;
    private boolean isGlobalDelete = false;
    private Set<String> changedFunctions = new HashSet<>();
    private Set<String> renamedFunctions = new HashSet<>();
    private Set<String> changedMacros = new HashSet<>();
    private Set<String> changedIncludes = new HashSet<>();
    private Set<String> changedStructs = new HashSet<>();

    /**
     * 记录修改部分对应图谱中的信息
     */



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
        isGlobalModify = false;
        isGlobalAdd = false;
        isGlobalDelete = false;
        changedFunctions.clear();
    }

    /**
     * 针对单个 commit 的 diff_info 内容进行处理
     * diff_info format: { parent_commit_name: { modified_fileName: diff_information } }
     * @param commitInfo
     */
    private void parseCommit(GitUpdate.CommitInfo commitInfo) {
        JSONArray diffInfos = commitInfo.diffInfo;
        // 初始化用于记录的全局数据结构
        initDS();
        for(int i = 0;i < diffInfos.size();i++) {
            // 只涉及单个代码文件的 diff 信息
            System.out.println("diffInfos: " + diffInfos.getJSONObject(i));
            JSONObject diff = diffInfos.getJSONObject(i);
            for(Map.Entry<String, Object> entry: diff.entrySet()) {
                // 代码项目根目录下的文件路径
                String fileName = entry.getKey();
                // 只处理 .h/.c 文件
                if(!fileName.contains(".c") && !fileName.contains(".h")) {
                    continue;
                }

                // 解析 diff 内容，定位文件内部的具体位置
                parseFileDiff((String) entry.getValue());


                // 获取修改文件的绝对路径(需要添加项目代码路径作为前缀来进行定位)
                String filePath = "D:\\documents\\SoftwareReuse\\knowledgeGraph\\gradDesign\\kernel_liteos_a\\" + fileName;

                // 后续步骤：获取图谱信息：解析代码文件；集合比对

                // 解析被修改的代码文件
                CCodeFileInfo codeFileInfo = getCodeFileInfo(filePath, fileName);
                codeFileInfo.getFunctionInfoList().forEach(CFunctionInfo::initCallFunctionNameList);

                // 获取以该文件为核心的图谱信息（数据库事务执行）
                CCodeFileInfo graphCodeFileInfo = getGraphCodeFileInfo(fileName);
            }
        }
    }

    /**
     * 对于单个代码文件的commit diff，利用其中的 @@ 行的内容进行具体的分析定位
     * 处理的结果，作为下一步访问数据库查询的对象
     * @param diffs 一个文件所有的diff信息，需要进一步分割
     */
    private void parseFileDiff(String diffs) {
        diffs = String.valueOf(diffs.split("\\r?\\\\n"));
        List<String> lines = Arrays.asList(diffs.split("\n"));
        // 记录单个修改片段的信息
        int deleteLine = 0, addLine = 0;
        List<String> deleteLines = new ArrayList<>(), addLines = new ArrayList<>();

        for(int i = 0;i < lines.size();i++) {
            String line = lines.get(i);
            if(line.equals("")) continue;
//            parseDiffType(deleteLine, addLine, deleteLines, addLines, line);
            String tmp = "";
            if(line.contains("@@")) {
                if(line.lastIndexOf("@") == line.length() - 1) {
                    // 存在全局信息的修改
                }
                else {
                    // 非全局修改
                    tmp = line.substring(line.lastIndexOf("@") + 1);
                    if(isFunction(tmp)) changedFunctions.add(tmp);
                }
            }
            else {
                // 非位置信息行
                if(line.charAt(0) == '+' && line.charAt(1) != '+') {
                    addLine++; addLines.add(line.substring(1));
                    if(line.contains("#define")) changedMacros.add(line.substring(line.indexOf("#")));
                    else if(line.contains("#include")) changedIncludes.add(line.substring(line.indexOf("#")));
                    else if(line.contains("struct")) changedStructs.add(line.substring(line.indexOf("+") + 1));
                    else if(isFunction(line)) changedFunctions.add(line.substring(line.indexOf("+") + 1));
                }
                else if(line.charAt(0) == '-' && line.charAt(1) != '-') {
                    deleteLine++; deleteLines.add(line.substring(1));
                    if(line.contains("#define")) changedMacros.add(line.substring(line.indexOf("#")));
                    else if(line.contains("#include")) changedIncludes.add(line.substring(line.indexOf("#")));
                    else if(line.contains("struct")) changedStructs.add(line.substring(line.indexOf("-") + 1));
                    else if(isFunction(line)) changedFunctions.add(line.substring(line.indexOf("-") + 1));
                }
                else {
                    // 普通代码行
                    if(isFunction(line)) changedFunctions.add(line);
                }
            }
        }
    }

    /**
     * 判断修改内容是否与函数定义相关
     * TODO: 没有考虑声明的情况(仅有参数类型)
     * @param line
     * @return
     */
    private boolean isFunction(String line) {
        Pattern r = Pattern.compile("\\s*\\w+\\s+\\w+\\(\\w+\\s\\w+.*");
        Matcher m = r.matcher(line);
        return m.find();
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
     * 访问数据库，获取代码文件相关联的各列表信息
     * @param fileName 项目根目录下的文件路径，用于实体唯一匹配
     */
    private CCodeFileInfo getGraphCodeFileInfo(String fileName) {
        CCodeFileInfo codeFileInfo = new CCodeFileInfo(fileName);
        String query = "";
        Map<String, Object> properties = new HashMap<>();
        GraphDatabaseService db = this.getDb();
        // 数据库事务执行
        try (Transaction tx = db.beginTx()) {
            // include code-files
            query = "match (n:c_code_file)-[:include]->(m:c_code_file) where n.fileName={ name } return m";
            properties = new HashMap<>();
            properties.put("name", fileName);
            Result res = db.execute(query, properties);
            while(res.hasNext()) {
                Map<String, Object> row = res.next();
                for(String key: res.columns()) {
                    Node node = (Node) row.get(key);
                    codeFileInfo.getIncludeCodeFileList().add((String) node.getProperty("fileName"));
                }
            }

            // define variables
            query = "match (n:c_code_file)-[:define]->(m:c_variable) where n.fileName={ name } return m";
            res = db.execute(query, properties);
            while(res.hasNext()) {
                Map<String, Object> row = res.next();
                for(String key: res.columns()) {
                    Node node = (Node) row.get(key);
                    CVariableInfo var = new CVariableInfo();
                    var.setName((String) node.getProperty("name"));
                    var.setContent((String) node.getProperty("content"));
                    var.setBelongTo((String) node.getProperty("belongTo"));
                    var.setIsDefine((Boolean) node.getProperty("isDefine"));
                    var.setIsStructVariable((Boolean) node.getProperty("isStructVariable"));
                    codeFileInfo.getVariableInfoList().add(var);
                }
            }

            // define dataStructure
            // relationship: member_of - field
            // TODO: c_struct 实体的抽取有问题
            query = "match (n:c_code_file)-[:define]->(m:c_struct) where n.fileName={ name } return m";
            res = db.execute(query, properties);
            while(res.hasNext()) {
                Map<String, Object> row = res.next();
                for(String key: res.columns()) {
                    Node node = (Node) row.get(key);
                    CDataStructureInfo dataStructure = new CDataStructureInfo();
                    dataStructure.setName((String) node.getProperty("name"));
                    dataStructure.setContent((String) node.getProperty("content"));
                    dataStructure.setTypedefName((String) node.getProperty("typedefName"));
                    dataStructure.setIsEnum((Boolean) node.getProperty("isEnum"));
                    // 获取当前 dataStruct 的 fieldInfo 列表
                    query = "match (n:c_struct)-[:member_of]->(m:c_field) where n.name = {name} and n.typedefName = {typedefName} return m";
                    Map<String, Object> pMap = new HashMap<>();
                    pMap.put("name", node.getProperty("name"));
                    pMap.put("typedefName", node.getProperty("typedefName"));
                    Result tmp = db.execute(query, pMap);
                    while(tmp.hasNext()) {
                        Map<String, Object> t_row = tmp.next();
                        for(String k: tmp.columns()) {
                            Node t_node = (Node) t_row.get(k);
                            CFieldInfo fieldInfo = new CFieldInfo();
                            fieldInfo.setName((String) t_node.getProperty("name"));
                            fieldInfo.setType((String) t_node.getProperty("type"));
                            dataStructure.getFieldInfoList().add(fieldInfo);
                        }
                    }
                    codeFileInfo.getDataStructureList().add(dataStructure);
                }
            }

            // define functions
            query = "match (n:c_code_file)-[:define]->(m:c_function) where n.fileName={ name } return m";
            res = db.execute(query, properties);
            while(res.hasNext()) {
                Map<String, Object> row = res.next();
                for(String key: res.columns()) {
                    Node node = (Node) row.get(key);
                    CFunctionInfo func = new CFunctionInfo();
                    func.setName((String) node.getProperty("name"));
                    func.setContent((String) node.getProperty("content"));
                    func.setFullName((String) node.getProperty("fullName"));
                    func.setBelongTo((String) node.getProperty("belongTo"));
                    func.setBelongToName((String) node.getProperty("belongToName"));
                    func.setIsInline((Boolean) node.getProperty("isInline"));
                    func.setIsConst((Boolean) node.getProperty("isConst"));
                    func.setIsDefine((Boolean) node.getProperty("isDefine"));
                    // 获取函数调用的函数列表
                    query = "match (n: c_function)-[:invoke]->(m:c_function) where n.name = {name} and n.belongTo = {belongTo} return m";
                    Map<String, Object> pMap = new HashMap<>();
                    pMap.put("name", node.getProperty("name"));
                    pMap.put("belongTo", node.getProperty("belongTo"));
                    Result tmp = db.execute(query, pMap);
                    while(tmp.hasNext()) {
                        Map<String, Object> t_row = tmp.next();
                        for(String k: tmp.columns()) {
                            Node t_node = (Node) t_row.get(k);
                            func.getCallFunctionNameList().add((String) t_node.getProperty("fullName"));
                        }
                    }
                    codeFileInfo.getFunctionInfoList().add(func);
                }
            }


            tx.success();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return codeFileInfo;
    }
}
