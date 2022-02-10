package cn.edu.pku.sei.intellide.graph.extraction.git;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.*;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.GetTranslationUnitUtil;
import org.eclipse.cdt.core.dom.ast.IASTTranslationUnit;
import org.eclipse.core.runtime.CoreException;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.neo4j.graphdb.*;

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
    private List<String> deleteItems = new ArrayList<>();
    private List<String> addItems = new ArrayList<>();
    private List<String> addFuncItems = new ArrayList<>();

    private Set<String> addIncludes = new HashSet<>();
    private Set<String> deleteIncludes = new HashSet<>();

    private Set<CVariableInfo> addVariables = new HashSet<>();
    private Set<CVariableInfo> deleteVariables = new HashSet<>();
    private Set<CVariableInfo> modifyVariables = new HashSet<>();

    private Set<CDataStructureInfo> addStructs = new HashSet<>();
    private Set<CDataStructureInfo> deleteStructs = new HashSet<>();
    private Set<CDataStructureInfo> modifyStructs = new HashSet<>();

    private Set<CFunctionInfo> addFunctions = new HashSet<>();
    private Set<CFunctionInfo> deleteFunctions = new HashSet<>();
    private Set<CFunctionInfo> modifyFunctions = new HashSet<>();



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
        addItems.clear(); deleteItems.clear(); addFuncItems.clear();
        addIncludes.clear(); deleteIncludes.clear();
        addVariables.clear(); deleteVariables.clear(); modifyVariables.clear();
        addStructs.clear(); deleteStructs.clear(); modifyStructs.clear();
        addFunctions.clear(); deleteFunctions.clear(); modifyFunctions.clear();
    }

    /**
     * 针对单个 commit 的 diff_info 内容进行处理
     * diff_info format: { parent_commit_name: { modified_fileName: diff_information } }
     * @param commitInfo
     */
    private void parseCommit(GitUpdate.CommitInfo commitInfo) {
//        JSONArray diffInfos = commitInfo.diffInfo;
        JSONArray diffInfos = new JSONArray();
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

                // 初始化用于记录的全局数据结构
                initDS();

                // 解析 diff 内容，记录修改相关的信息
                parseFileDiff((String) entry.getValue());

                // 获取修改文件的绝对路径(需要添加项目代码路径作为前缀来进行定位)
                String filePath = "D:\\documents\\SoftwareReuse\\knowledgeGraph\\gradDesign\\kernel_liteos_a\\" + fileName;

                // 解析被修改的代码文件
                CCodeFileInfo codeFileInfo = getCodeFileInfo(filePath, fileName);
                codeFileInfo.getFunctionInfoList().forEach(CFunctionInfo::initCallFunctionNameList);

                // HACK 项目名称
                filePath = "kernel_lite_os\\" + fileName;

                // 获取以该文件为核心的图谱信息（数据库事务执行）
                CCodeFileInfo graphCodeFileInfo = getGraphCodeFileInfo(filePath, fileName);

                // 更新后代码文件与图谱内容对比
                setDiffInfo(codeFileInfo, graphCodeFileInfo);

                // 执行数据库事务，更新图谱内容
                updateGraph(fileName, graphCodeFileInfo);
            }
        }
    }

    /**
     * 对于单个代码文件的commit diff，记录删除/新增的代码行内容，用于图谱更新时的判断
     * @param diffs 一个文件所有的diff信息
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
            if(line.contains("@@")) {
                // 处理上一个 diff 片段
                if(addLine == 0 && deleteLine != 0) {
                    deleteItems.addAll(deleteLines);
                }
                else if(addLine != 0 && deleteLine == 0) {
                    for(String item: addLines) {
                        if(isFunction(item)) {
                            addFuncItems.add(item);
                        }
                        else {
                            addItems.add(item);
                        }
                    }
                }
                addLine = 0; deleteLine = 0;
                addLines.clear(); deleteLines.clear();
            }
            else {
                if(line.length() > 1) {
                    if(line.charAt(0) == '+' && line.charAt(1) != '+') {
                        addLines.add(line);
                    }
                    else if(line.charAt(0) == '-' && line.charAt(1) != '-') {
                        deleteLines.add(line);
                    }
                }
            }
        }
        if(addLine == 0 && deleteLine != 0) {
            deleteItems.addAll(deleteLines);
        }
        else if(addLine != 0 && deleteLine == 0) {
            addItems.addAll(addLines);
        }
    }

    /**
     * 判断修改内容是否与函数定义相关
     * 函数声明/定义的格式
     * @return
     */
    private boolean isFunction(String line) {
        Pattern r = Pattern.compile("\\s*\\w+\\s\\w+\\(\\w+\\s\\w+.*");
        Matcher m = r.matcher(line);
        boolean f1 = m.find();
        r = Pattern.compile("\\s*\\w+\\s\\w+\\([^\\)]*\\)");
        m = r.matcher(line);
        boolean f2 = m.find();
        return f1 || f2;
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
        return new CCodeFileInfo(filePath, fileName, translationUnit);
    }

    /**
     * 访问数据库，获取代码文件相关联的各列表信息
     * @param fileName 项目根目录下的文件路径，用于实体唯一匹配
     */
    private CCodeFileInfo getGraphCodeFileInfo(String filePath, String fileName) throws QueryExecutionException {
        CCodeFileInfo codeFileInfo = new CCodeFileInfo(filePath, fileName);
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

    /**
     * 将更新后的代码文件内容与当前图谱内容进行对比，将修改记录在全局数据结构中
     * 用更新后的代码文件内容去查找图谱
     * 注：在调用 contains 方法是根据重写的 equals 处理的，需要进一步完善
     */
    private void setDiffInfo(CCodeFileInfo codeFileInfo, CCodeFileInfo graphCodeFileInfo) {
        // include files
        codeFileInfo.getIncludeCodeFileList().forEach(includeFile ->{
            // 图谱数据不包含 -> 新增
            if(!graphCodeFileInfo.getIncludeCodeFileList().contains(includeFile)) {
                addIncludes.add(includeFile);
            }
            else{
                graphCodeFileInfo.getIncludeCodeFileList().remove(includeFile);
            }
        });
        deleteIncludes.addAll(graphCodeFileInfo.getIncludeCodeFileList());

        // global variables
        codeFileInfo.getVariableInfoList().forEach(var -> {
            if(!graphCodeFileInfo.getVariableInfoList().contains(var)) {
                // add or modify（利用对 diff 的解析确定）
                boolean flag = false;
                for(String item: addItems) {
                    if(item.contains(var.getName())) {
                        addVariables.add(var);
                        flag = true; break;
                    }
                }
                if(!flag) modifyVariables.add(var);
            }
            else {
                graphCodeFileInfo.getVariableInfoList().remove(var);
            }
        });
        // 剩下的是已被删除的 variables
        deleteVariables.addAll(graphCodeFileInfo.getVariableInfoList());

        // data struct
        codeFileInfo.getDataStructureList().forEach(ds -> {
            if(!graphCodeFileInfo.getDataStructureList().contains(ds)) {
                boolean flag = false;
                for(String item: addItems) {
                    if(item.contains(ds.getName()) || item.contains(ds.getTypedefName())) {
                        addStructs.add(ds);
                        flag = true; break;
                    }
                }
                if(!flag) modifyStructs.add(ds);
            }
            else {
                graphCodeFileInfo.getDataStructureList().remove(ds);
            }
        });
        deleteStructs.addAll(graphCodeFileInfo.getDataStructureList());

        // functions
        // 需要正则匹配来识别新增定义的函数-addFuncItems
        codeFileInfo.getFunctionInfoList().forEach(func -> {
           if(!graphCodeFileInfo.getFunctionInfoList().contains(func)) {
               boolean flag = false;
               for(String item: addFuncItems) {
                   if(item.contains(func.getName())) {
                       addFunctions.add(func);
                       flag = true; break;
                   }
               }
               if(!flag) {
                   modifyFunctions.add(func);
               }
           }
           else {
               graphCodeFileInfo.getFunctionInfoList().remove(func);
           }
        });
        deleteFunctions.addAll(graphCodeFileInfo.getFunctionInfoList());
    }

    /**
     * 依据全局记录的数据结构，对图谱内容进行更新
     */
    private void updateGraph(String fileName, CCodeFileInfo codeFileInfo) throws QueryExecutionException {
        GraphDatabaseService db = this.getDb();
        try (Transaction tx = db.beginTx()) {
            // include files
            addIncludes.forEach(file -> {
                String cql = "match (n:c_code_file{fileName: '" + fileName + "'}) " +
                        "match (m:c_code_file{fileName: '" + file + "'}) " +
                        "create (n) -[:include]-> (m)";
                db.execute(cql);
            });
            deleteIncludes.forEach(file -> {
                String cql = "match (n:c_code_file{fileName: '" + fileName + "'}) " +
                        "-[r:include]-> (m:c_code_file{fileName: '" + file + "'}) " +
                        "delete r";
                db.execute(cql);
            });

            // variables
            addVariables.forEach(var -> {
                Node node = db.createNode(CExtractor.c_variable);
                node.setProperty("name", var.getName());
                node.setProperty("content", var.getContent());
                node.setProperty("belongTo", var.getBelongTo());
                node.setProperty("isDefine", var.getIsDefine());
                node.setProperty("isStructVariable", var.getIsStructVariable());
            });
            deleteVariables.forEach(var -> {
                String cql = "match (n:c_variable{name:'" + var.getName() + "'})" +
                        "detach delete n";
                db.execute(cql);
            });
            modifyVariables.forEach(var -> {
               Node node = db.findNode(CExtractor.c_variable, "name", var.getName());
               if(node != null) {
                   // 非更名操作，使用 name 查询匹配
                   node.setProperty("content", var.getContent());
                   node.setProperty("belongTo", var.getBelongTo());
                   node.setProperty("isDefine", var.getIsDefine());
                   node.setProperty("isStructVariable", var.getIsStructVariable());
               }
               else {
                   // 更名操作，需要匹配原节点
                   String tmp = var.getContent();
                   tmp.replace(var.getName(), "");
                   String cql = "match (n:c_variable) where n.belongTo = '" + var.getBelongTo() + "' and " +
                           "n.content contains '" + tmp + "' return n";
                   Result res = db.execute(cql);
                   // TODO: 也有可能存在返回多个节点的情况
                   if(res.hasNext()) {
                       Map<String, Object> row = res.next();
                       for(String key: res.columns()) {
                           Node n = (Node) row.get(key);
                           n.setProperty("name", var.getName());
                           n.setProperty("content", var.getContent());
                       }
                   }

               }
            });

            // dataStructures
            // TODO: 目前看来和 variable 基本是相同的处理，先跳过这部分

            // functions
            addFunctions.forEach(func -> {
               Node node = db.createNode(CExtractor.c_function);
               node.setProperty("name", func.getName());
               node.setProperty("fullName", func.getFullName());
               node.setProperty("belongTo", func.getBelongTo());
               node.setProperty("fullParams", func.getFullParams());
               node.setProperty("isInline", func.getIsInline());
               node.setProperty("isConst", func.getIsConst());
               node.setProperty("isDefine", func.getIsDefine());
               node.setProperty("belongToName", func.getBelongToName());
            });
            deleteFunctions.forEach(func -> {
                String cql = "match (n:c_function{name:'" + func.getFullName() + "'})" +
                        "detach delete n";
                db.execute(cql);
            });

            modifyFunctions.forEach(func -> {
                Node node = db.findNode(CExtractor.c_function, "fullName", func.getFullName());
                if(node != null) {
                    // 非更名操作
                    node.setProperty("name", func.getName());
                    node.setProperty("fullName", func.getFullName());
                    node.setProperty("belongTo", func.getBelongTo());
                    node.setProperty("fullParams", func.getFullParams());
                    node.setProperty("isInline", func.getIsInline());
                    node.setProperty("isConst", func.getIsConst());
                    node.setProperty("isDefine", func.getIsDefine());
                    node.setProperty("belongToName", func.getBelongToName());
                    // 需要考虑调用函数列表的修改
                    List<String> invokeFunctions = func.getCallFunctionNameList();
                    List<String> prevInvokeFunctions = new ArrayList<>();
                    for(CFunctionInfo prevFunc: codeFileInfo.getFunctionInfoList()) {
                        if(prevFunc.getName().equals(func.getName())) {
                            prevInvokeFunctions = prevFunc.getCallFunctionNameList();
                        }
                    }
                    for(String invokeFunc: invokeFunctions) {
                        if(prevInvokeFunctions.contains(invokeFunc)) {
                            prevInvokeFunctions.remove(invokeFunc);
                        }
                        else {
                            // 新增的函数调用
                            String cql = "match (n:c_function{name:'" + func.getName() + "'})" +
                                    "match (m:c_function{name:'" + invokeFunc + "'})" +
                                    "create (n)-[:invoke]->(m)";
                            db.execute(cql);
                        }
                    }
                    // 删除的函数调用
                    for(String prevFunc: prevInvokeFunctions) {
                        String cql = "match (n:c_function{name:'" + func.getName() + "'})" +
                                " -[r:invoke]-> (m:c_function{name:'" + prevFunc + "'}) return r";
                        db.execute(cql);
                    }
                }
                else {
                    // 更名操作，需要匹配原节点
                    String tmp = func.getFullName();
                    tmp.replace(func.getName(), "");
                    // TODO: 匹配属性可能不足够（也许引入参数列表）
                    String cql = "match (n:c_function) where n.belongTo = '" + func.getBelongTo() + "' and " +
                            "n.isInline = '" + func.getIsInline() + "' and" +
                            "n.isConst = '" + func.getIsConst() + "' and" +
                            "n.isDefine = '" + func.getIsDefine() + "' and" +
                            "n.fullName contains '" + tmp + "' return n";
                    Result res = db.execute(cql);
                    // TODO: 也有可能存在返回多个节点的情况
                    if(res.hasNext()) {
                        Map<String, Object> row = res.next();
                        for(String key: res.columns()) {
                            Node n = (Node) row.get(key);
                            n.setProperty("name", func.getName());
                            n.setProperty("fullName", func.getFullName());
                            n.setProperty("belongToName", func.getBelongToName());
                        }
                    }

                }
            });

            tx.success();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
