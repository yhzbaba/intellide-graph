package cn.edu.pku.sei.intellide.graph.extraction.git;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.*;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.GetTranslationUnitUtil;
import cn.edu.pku.sei.intellide.graph.extraction.code_mention.CCodeMentionExtractor;
import com.github.gumtreediff.actions.EditScript;
import com.github.gumtreediff.actions.EditScriptGenerator;
import com.github.gumtreediff.actions.SimplifiedChawatheScriptGenerator;
import com.github.gumtreediff.actions.model.Action;
import com.github.gumtreediff.client.Run;
import com.github.gumtreediff.gen.TreeGenerators;
import com.github.gumtreediff.matchers.MappingStore;
import com.github.gumtreediff.matchers.Matchers;
import com.github.gumtreediff.tree.Tree;
import org.eclipse.cdt.core.dom.ast.IASTTranslationUnit;
import org.eclipse.core.runtime.CoreException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 根据 commit 信息更新代码图谱，在 GitUpdate.java 中被调用
 * FIXME:
 * - #define 变量 cdt 没有识别出来
 * - 执行 cypher 查找文件实体时的标识属性，文件的 fileName 应当设置为 项目内路径+tailFileName
 * - struct 的 field member 没有抽取出来
 */
public class GraphUpdate {

    public static void main(String[] args) {
        Map<String, LinkedHashSet<String>> fileCommitInfos = new LinkedHashMap<>();
        fileCommitInfos.put("b", new LinkedHashSet<>());
        fileCommitInfos.get("b").add("f");
        fileCommitInfos.get("b").add("a");
        fileCommitInfos.put("a", new LinkedHashSet<>());
        fileCommitInfos.get("a").add("c");
        fileCommitInfos.get("a").add("b");
        for(Map.Entry entry: fileCommitInfos.entrySet()) {
            String funcName = (String) entry.getKey();
            Set<String> invokeFunctions = (LinkedHashSet<String>) entry.getValue();
            for(String s: invokeFunctions) {
                System.out.println(s);
            }
        }
//        String filePath = "D:\\Documents\\GradProject\\test2.c";
//        String content = Utils.getFileContent(filePath);
//        int s = 65;
//        int e = 81;
//        System.out.println(content.substring(s,e));
//        String ss = content.substring(s,e);
//        Matcher m = Pattern.compile("#define\\s(\\w+)\\s").matcher(ss);
//            if(m.find()) {
//                System.out.println(m.group(1));
//            }
    }

    private Map<String, GitUpdate.CommitInfo> commitInfos;

    /* 记录 <file, set<commit> >，按照添加顺序依次更新文件
     * 主要的问题在于：单个 commit 对应的两个版本的代码文件缺失（只有初始和最终版本），导致 commit-code 不能对应
     * TODO: commit-code relationship is not correct(code version)
     */
    private Map<String, LinkedHashSet<Long>> fileCommitInfos = new LinkedHashMap<>();

    private String srcCodeDir;
    private String dstCodeDir;
    private String srcContent;
    private String dstContent;

    private GraphDatabaseService db;

    /*
     * 记录修改涉及到的代码信息，主要是元素名称，与 CCodeFileInfo 中的信息进行匹配
     * struct 和 function 的修改包括两类：内部成员的增删(map)、标识符等属性的修改(set)
     * 每次处理文件前需要初始化
     */
    private Set<String> addIncludes = new HashSet<>();
    private Set<String> deleteIncludes = new HashSet<>();

    private Set<String> addVariables = new HashSet<>();
    private Set<String> deleteVariables = new HashSet<>();
    private Set<String> updateVariables = new HashSet<>();

    private Set<String> addStructs = new HashSet<>();
    private Set<String> deleteStructs = new HashSet<>();
    private Set<String> updateStructs = new HashSet<>();
    private Map<String, Set<String>> addStructMembers = new HashMap<>();
    private Map<String, Set<String>> deleteStructMembers = new HashMap<>();

    private Set<String> addFunctions = new HashSet<>();
    private Set<String> deleteFunctions = new HashSet<>();
    private Set<String> updateFunctions = new HashSet<>();
    private Map<String, Set<String>> addInvokeFunctions = new HashMap<>();
    private Map<String, Set<String>> deleteInvokeFunctions = new HashMap<>();
    private Map<String, String> renameFunctions = new HashMap<>();

    // 记录单个 commit 修改的代码实体的id
    Set<Long> updateEntities = new HashSet<>();
    Set<Long> addEntities = new HashSet<>();

    public GraphUpdate(Map<String, GitUpdate.CommitInfo> commitInfos, String srcCodeDir, String dstCodeDir, GraphDatabaseService db) {
        this.commitInfos = commitInfos;
        this.srcCodeDir = srcCodeDir;
        this.dstCodeDir = dstCodeDir;
        this.db = db;
        this.extraction();
    }

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
        for(Map.Entry entry: fileCommitInfos.entrySet()) {
            String fileName = (String) entry.getKey();
            Set<Long> commitIds = (LinkedHashSet<Long>) entry.getValue();

            /* 以单个文件作为单位进行处理，首先进行初始化工作 */
            initDS();

            String srcFile = srcCodeDir + "//" + fileName;
            String dstFile = dstCodeDir + "//" + fileName;

            // 将文件内容读入字符串
            srcContent = Utils.getFileContent(srcFile);
            dstContent = Utils.getFileContent(dstFile);

            // 解析被修改的代码文件(dstFile)
            System.out.println("Parse Code File: " + fileName + "...\n");
            IASTTranslationUnit translationUnit = null;
            try {
                translationUnit = GetTranslationUnitUtil.getASTTranslationUnit(new File(dstFile));
            } catch (CoreException | IOException e) {
                e.printStackTrace();
            }
            CCodeFileInfo codeFileInfo = new CCodeFileInfo(null, fileName, fileName.substring(fileName.lastIndexOf("//") + 1), translationUnit);
            codeFileInfo.getFunctionInfoList().forEach(CFunctionInfo::initCallFunctionNameList);

            /*
             * 调用 GumTree API 获得 edit actions
             * 主要用于修改的定位，具体的图谱更新工作通过 CCodeFileInfo 进行
             */

            Run.initGenerators();

            Tree src = null, dst = null;
            try {
                src = TreeGenerators.getInstance().getTree(srcFile).getRoot();;
                dst = TreeGenerators.getInstance().getTree(dstFile).getRoot();
                com.github.gumtreediff.matchers.Matcher defaultMatcher = Matchers.getInstance().getMatcher();
                MappingStore mappings = defaultMatcher.match(src, dst);
                EditScriptGenerator editScriptGenerator = new SimplifiedChawatheScriptGenerator();
                EditScript actions = editScriptGenerator.computeActions(mappings);
                List<Action> actionsList = actions.asList();
                for(Action action: actionsList) {
                    // 解析单个 edit action
                    parseEditAction(action, action.getNode().getParent());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            /* 依据全局数据结构的记录：
             * 建立 Commit 与 Code 实体的关联
             * 完成单个文件的修改信息的记录，统一执行数据库事务进行图谱更新
             */

            System.out.println("Update Knowledge Graph...\n");
            updateKG(codeFileInfo, fileName);

            System.out.println("Create Entity Relationships...\n");
            createRelationships(commitIds, fileName);
        }
    }


    private void initDS() {
        addIncludes.clear(); deleteIncludes.clear();
        addVariables.clear(); deleteVariables.clear(); updateVariables.clear();
        addStructs.clear(); deleteStructs.clear(); updateStructs.clear();
        addStructMembers.clear(); deleteStructMembers.clear();
        addFunctions.clear(); deleteFunctions.clear(); updateFunctions.clear();
        addInvokeFunctions.clear(); deleteInvokeFunctions.clear(); renameFunctions.clear();

        updateEntities.clear(); addEntities.clear();
    }

    /**
     * 处理 diffSummary 包含的文件
     * @param commitInfo 单个commit涉及的所有修改文件
     */
    private void parseCommit(GitUpdate.CommitInfo commitInfo) {
//        long commitId = commitInfo.id;
        for(String diff: commitInfo.diffSummary) {
            if(!diff.contains(".c") && !diff.contains(".h")) continue;

            String fileName = Utils.getFileFromDiff(diff);

            // 单点处理直接在此处进行操作

            if(!fileCommitInfos.containsKey(fileName)) {
                fileCommitInfos.put(fileName, new LinkedHashSet<>());
            }
            fileCommitInfos.get(fileName).add(commitInfo.id);
        }
    }

    
    /**
     * 对单个编辑动作进行解析，获取具体的修改内容
     * 对各类 edit action 进行分类，并针对性地记录
     * @param action
     */
    private void parseEditAction(Action action, Tree Node) {
        EditAction editAction = new EditAction();
        /* 父节点的类型和位置
         * 父结点通常是用于修改函数的定位
         * 如果是 Compound，调用两次 getParent()，由 Compound 得到 Definition
         */
        editAction.Node = Node;
        String pNode = Node.toString();
        if(pNode.contains("Compound")) pNode = Node.getParent().toString();
        else pNode = Node.toString();
        editAction.pNode = pNode.substring(0, pNode.indexOf(" "));
        editAction.pStart = Integer.parseInt(pNode.substring(pNode.indexOf("[") + 1, pNode.indexOf(",")));
        editAction.pEnd = Integer.parseInt(pNode.substring(pNode.indexOf(",") + 1, pNode.indexOf("]")));
        String[] lines = action.toString().split("\n");
        int i = 0;
        while(i < lines.length) {
            if(lines[i].equals("===")) {
                editAction.type = lines[++i]; i += 2;
                String tmp = lines[i];
                editAction.tNode = tmp.substring(0, tmp.indexOf(" "));
                editAction.Start = Integer.parseInt(tmp.substring(tmp.indexOf("[") + 1, tmp.indexOf(",")));
                editAction.End = Integer.parseInt(tmp.substring(tmp.indexOf(",") + 1, tmp.indexOf("]")));
                i++;
            }
            else if(lines[i].equals("to")) {
                break;
            }
            else {
                while(i < lines.length && !lines[i].equals("to")) {
                    editAction.content.add(lines[i]);
                    i++;
                }
            }
        }
        // 根据 action 类型，具体解析修改内容
        parseActionContent(editAction);
    }

    /**
     * 针对单个 action，依据 type 和 tNode 判断修改的具体类型，对 content 属性进行解析并记录
     * Tree Node Types: insert, delete, update, move
     * Modified Object Types: CppTop, Declaration, Definition, ExprStatement, DeclList. Storage, ParameterType
     * 还有诸多情况未考虑在内，需要根据实测来添加相应的处理
     */
    private void parseActionContent(EditAction editAction) {
        // 依据 edit action 节点的类型进行相应的处理

        // Note:有很多不相关的修改，但因为恰好是-+的操作，识别为 update / move
        if(editAction.type.contains("update")) {
            if(editAction.pNode.equals("Definition")) {
                // 函数更名
                String tmp = editAction.content.get(0);
                Matcher m = Pattern.compile("replace\\s(\\w+)\\sby\\s(\\w+)").matcher(tmp);
                if(m.find()) {
                    String oldFunc = m.group(1);
                    String newFunc = m.group(2);
                    renameFunctions.put(oldFunc, newFunc);
                }
            }
            else if(editAction.tNode.contains("GenericString") && editAction.tNode.contains(".h")) {
                // - include + include
                String tmp = editAction.content.get(0);
                Matcher m = Pattern.compile("replace\\s\"(\\w+)\"\\sby\\s\"(\\w+)\"").matcher(tmp);
                if(m.find()) {
                    String oldInclude = m.group(1);
                    String newInclude = m.group(2);
                    addIncludes.add(newInclude);
                    deleteIncludes.add(oldInclude);
                }
            }
        }
        else {
            if(editAction.tNode.contains("CppTop")) {
                // Macro, include
                int i = 0;
                while(i < editAction.content.size()) {
                    if(editAction.content.get(i).contains("Include")) {
                        if(editAction.type.contains("insert")) {
                            // add include files
                            addIncludes.add(Utils.getItemName(editAction.Start, editAction.End, dstContent, "Include"));
                        }
                        else if(editAction.type.contains("delete")) {
                            deleteIncludes.add(Utils.getItemName(editAction.Start, editAction.End, srcContent, "Include"));
                        }
                        break;
                    }
                    else if(editAction.content.get(i).contains("DefineVar")) {
                        if(editAction.type.contains("insert")) {
                            // add Macro #define(as variable)
                            addVariables.add(Utils.getItemName(editAction.Start, editAction.End, dstContent, "MacroVar"));
                        }
                        else if(editAction.type.contains("delete")) {
                            deleteVariables.add(Utils.getItemName(editAction.Start, editAction.End, srcContent, "MacroVar"));
                        }
                        break;
                    }
                    else if(editAction.content.get(i).contains("DefineFunc")) {
                        if(editAction.type.contains("insert")) {
                            // add Macro #define(as function)
                            addFunctions.add(Utils.getItemName(editAction.Start, editAction.End, dstContent, "MacroFunc"));
                        }
                        else if(editAction.type.contains("delete")) {
                            deleteVariables.add(Utils.getItemName(editAction.Start, editAction.End, srcContent, "MacroFunc"));
                        }
                        break;
                    }
                    i++;
                }
            }
            else if(editAction.tNode.contains("Declaration")) {
                // struct, variable, function (global declaration)
                int i = 0;
                boolean isTypeDef = false;
                while(i < editAction.content.size()) {
                    if (editAction.content.get(i).contains("GenericString: typedef")) {
                        // typedef struct
                        if(editAction.type.contains("insert")) {
                            addStructs.add(Utils.getItemName(0, 0, editAction.content.get(i + 1), "Typedef"));
                        }
                        else if(editAction.type.contains("delete")) {
                            deleteStructs.add(Utils.getItemName(0, 0, editAction.content.get(i + 1), "Typedef"));
                        }
                        isTypeDef = true;
                        break;
                    }
                    i++;
                }
                if(!isTypeDef) {
                    String tmp = "";
                    if(editAction.type.contains("insert")) tmp = dstContent.substring(editAction.Start, editAction.End);
                    else if(editAction.type.contains("delete")) tmp = srcContent.substring(editAction.Start, editAction.End);
                    if(tmp.contains("struct")) {
                        // struct(no typedef)
                        if(editAction.type.contains("insert")) {
                            addStructs.add(Utils.getItemName(0, 0, tmp, "Struct"));
                        }
                        else if(editAction.type.contains("delete")) {
                            deleteStructs.add(Utils.getItemName(0, 0, tmp, "Struct"));
                        }
                    }
                    else {
                        Matcher m = Pattern.compile("\\w+\\s(\\w+)\\(.*\\);").matcher(tmp);
                        if(m.find()) {
                            // function declaration
                            if(editAction.type.contains("insert")) {
                                addFunctions.add(m.group(1));
                            }
                            else if(editAction.type.contains("delete")) {
                                deleteFunctions.add(m.group(1));
                            }
                        }
                        else {
                            // global variable
                            if(editAction.type.contains("insert")) {
                                addVariables.add(tmp.substring(tmp.lastIndexOf(" ")+1, tmp.indexOf(";")));
                            }
                            else if(editAction.type.contains("delete")) {
                                deleteVariables.add(tmp.substring(tmp.lastIndexOf(" ")+1, tmp.indexOf(";")));
                            }
                        }
                    }
                }
            }
            else if(editAction.tNode.contains("Definition")) {
                // function definition
                int i = 0;
                while(i < editAction.content.size()) {
                    if(editAction.content.get(i).contains("ParamList")) {
                        // function definition
                        if(editAction.type.contains("insert")) {
                            addFunctions.add(Utils.getItemName(editAction.Start, editAction.End, dstContent, "FuncDef"));
                        }
                        else if(editAction.type.contains("delete")) {
                            deleteFunctions.add(Utils.getItemName(editAction.Start, editAction.End, srcContent, "FuncDef"));
                        }
                        break;
                    }
                    i++;
                }
            }
            /* 函数内部的修改
             * 依照目前的处理，主要是方法调用的增删，标识符的修改，参数列表的修改
             */
            else if(editAction.tNode.contains("ExprStatement") || editAction.tNode.contains("DeclList")) {
                /* 函数内部的修改语句
                 * e.g. 包含了函数调用的赋值语句，单纯的函数调用
                 */
                int i = 0;
                while(i < editAction.content.size()) {
                    if(editAction.content.get(i).contains("FunCall")) {
                        // 函数内有调用函数的修改
                        String invokeFunc = editAction.content.get(i+2);
                        invokeFunc = invokeFunc.substring(invokeFunc.indexOf(":")+2, invokeFunc.indexOf("[")-1);
                        String Func = "";
                        if(editAction.type.contains("insert")) {
                            // 获取父结点的函数名称
                            Func = Utils.getItemName(editAction.pStart, editAction.pEnd, dstContent, "FuncDef");
                            if(addInvokeFunctions.containsKey(Func)) {
                                addInvokeFunctions.get(Func).add(invokeFunc);
                            }
                            else {
                                addInvokeFunctions.put(Func, new HashSet<>());
                                addInvokeFunctions.get(Func).add(invokeFunc);
                            }
                        }
                        else if(editAction.type.contains("delete")) {
                            Func = Utils.getItemName(editAction.pStart, editAction.pEnd, srcContent, "FuncDef");
                            if(deleteInvokeFunctions.containsKey(Func)) {
                                deleteInvokeFunctions.get(Func).add(invokeFunc);
                            }
                            else {
                                deleteInvokeFunctions.put(Func, new HashSet<>());
                                deleteInvokeFunctions.get(Func).add(invokeFunc);
                            }
                        }
                        break;
                    }
                    i++;
                }
            }
            else if(editAction.tNode.contains("Storage")) {
                // 函数/变量标识符的修改
                if(editAction.tNode.contains("insert")) {
                    updateFunctions.add(Utils.getItemName(editAction.pStart, editAction.pEnd, dstContent, "FuncDef"));
                }
                else if(editAction.type.contains("delete")) {
                    updateFunctions.add(Utils.getItemName(editAction.pStart, editAction.pEnd, srcContent, "FuncDef"));
                }
            }
            else if(editAction.tNode.contains("ParameterType")) {
                // 函数参数列表的修改
                String func = Utils.getFunctionFromDef(editAction, srcContent, dstContent);
                updateFunctions.add(func);
            }
            else if(editAction.tNode.contains("FunCall")) {
                // 函数内调用
                if(editAction.type.contains("insert")) {
                    String func = Utils.getFunctionFromDef(editAction, srcContent, dstContent);
                    String invokeFunc = dstContent.substring(editAction.Start, editAction.End);
                    invokeFunc = invokeFunc.substring(0, invokeFunc.indexOf("("));
                    if(addInvokeFunctions.containsKey(func)) {
                        addInvokeFunctions.get(func).add(invokeFunc);
                    }
                    else {
                        addInvokeFunctions.put(func, new HashSet<>());
                        addInvokeFunctions.get(func).add(invokeFunc);
                    }
                }
                else if(editAction.type.contains("delete")) {
                    String func = Utils.getFunctionFromDef(editAction, srcContent, dstContent);
                    String invokeFunc = srcContent.substring(editAction.Start, editAction.End);
                    invokeFunc = invokeFunc.substring(0, invokeFunc.indexOf("("));
                    if(deleteInvokeFunctions.containsKey(func)) {
                        deleteInvokeFunctions.get(func).add(invokeFunc);
                    }
                    else {
                        deleteInvokeFunctions.put(func, new HashSet<>());
                        deleteInvokeFunctions.get(func).add(invokeFunc);
                    }
                }
            }
            else if(editAction.tNode.contains("Binary")) {
                // 条件语句 if, while
                int i = 0;
                while(i < editAction.content.size()) {
                    if(editAction.content.get(i).contains("FunCall")) {
                        String invokeFunc = editAction.content.get(i + 2);
                        invokeFunc = invokeFunc.substring(invokeFunc.indexOf(":")+2, invokeFunc.indexOf("[")-1);
                        String func = Utils.getFunctionFromDef(editAction, srcContent, dstContent);
                        if(editAction.type.contains("insert")) {
                            if(addInvokeFunctions.containsKey(func)) {
                                addInvokeFunctions.get(func).add(invokeFunc);
                            }
                            else {
                                addInvokeFunctions.put(func, new HashSet<>());
                                addInvokeFunctions.get(func).add(invokeFunc);
                            }
                        }
                        else if(editAction.type.contains("delete")) {
                            if(deleteInvokeFunctions.containsKey(func)) {
                                deleteInvokeFunctions.get(func).add(invokeFunc);
                            }
                            else {
                                deleteInvokeFunctions.put(func, new HashSet<>());
                                deleteInvokeFunctions.get(func).add(invokeFunc);
                            }
                        }
                        break;
                    }
                    i++;
                }
            }
        }
    }
    

    /**
     * 更新图谱内容
     */
    private void updateKG(CCodeFileInfo codeFileInfo, String fileName) {
        try(Transaction tx = db.beginTx()) {
            // commit -update-> code_file
//            Node commitNode = db.getNodeById(commitId);
            Node fileNode = db.findNode(CExtractor.c_code_file, "fileName", fileName);
//            commitNode.createRelationshipTo(fileNode, CCodeMentionExtractor.UPDATE);
            // include files
            addIncludes.forEach(file -> {
                String cql = "match (n:c_code_file{fileName:'" + fileName + "'}) " +
                        "match (m:c_code_file{tailFileName:'" + file + "'}) " +
                        "create (n) -[:include]-> (m)";
                db.execute(cql);
            });
            deleteIncludes.forEach(file -> {
                String cql = "match (n:c_code_file{fileName: '" + fileName + "'}) " +
                        "-[r:include]-> (m:c_code_file{tailFileName: '" + file + "'}) " +
                        "delete r";
                db.execute(cql);
            });

            // variables
            addVariables.forEach(var -> {
               for(CVariableInfo cVar: codeFileInfo.getVariableInfoList()) {
                   if(cVar.getName().equals(var)) {
                       Node node = db.createNode(CExtractor.c_variable);
                       node.setProperty("name", cVar.getName());
                       node.setProperty("content", cVar.getContent());
                       node.setProperty("belongTo", cVar.getBelongTo());
                       node.setProperty("isDefine", cVar.getIsDefine());
                       node.setProperty("isStructVariable", cVar.getIsStructVariable());
                       fileNode.createRelationshipTo(node, CExtractor.define);
                       addEntities.add(node.getId());
                       break;
                   }
               }
            });
            deleteVariables.forEach(var -> {
                String cql = "match (n:c_code_file{fileName:'" + fileName + "'})" +
                        "-[:define]->" +
                        "(m:c_variable{name:'" + var + "'})" +
                        "detach delete m";
                db.execute(cql);
            });
            updateVariables.forEach(var -> {
               // TODO:是否需要考虑变量更名的操作
                for(CVariableInfo cVar: codeFileInfo.getVariableInfoList()) {
                    if(cVar.getName().equals(var)) {
                        String cql = "match (n:c_code_file{fileName:'" + fileName + "'})" +
                                "-[:define]->" +
                                "(m:c_variable{name:'" + cVar.getName() + "'})" +
                                "return m";
                        Result res = db.execute(cql);
                        while(res.hasNext()) {
                            Map<String, Object> row = res.next();
                            for(String key: res.columns()) {
                                Node node = (Node) row.get(key);
                                node.setProperty("content", cVar.getContent());
                                node.setProperty("belongTo", cVar.getBelongTo());
                                node.setProperty("isDefine", cVar.getIsDefine());
                                node.setProperty("isStructVariable", cVar.getIsStructVariable());
                                updateEntities.add(node.getId());
                            }
                        }
                        break;
                    }
                }
            });

            // structs
            addStructs.forEach(struct -> {
                for(CDataStructureInfo cStruct: codeFileInfo.getDataStructureList()) {
                    if(cStruct.getName().equals(struct) || cStruct.getTypedefName().equals(struct)) {
                        Node node = db.createNode(CExtractor.c_struct);
                        node.setProperty("name", cStruct.getName());
                        node.setProperty("typedefName", cStruct.getTypedefName());
                        node.setProperty("isEnum", cStruct.getIsEnum());
                        for(CFieldInfo field: cStruct.getFieldInfoList()) {
                            Node fNode = db.createNode(CExtractor.c_field);
                            fNode.setProperty("name", field.getName());
                            fNode.setProperty("type", field.getType());
                            node.createRelationshipTo(fNode, CExtractor.member_of);
                        }
                        addEntities.add(node.getId());
                        fileNode.createRelationshipTo(node, CExtractor.define);
                        break;
                    }
                }
            });
            deleteStructs.forEach(struct -> {
                String cql = "match (n:c_code_file{fileName:'" + fileName + "'})" +
                        "-[:define]->" +
                        "(m:c_struct{name:'" + struct + "'})" +
                        "<-[:member_of]-" +
                        "(o:c_field) " +
                        "detach delete m, o";
                db.execute(cql);
                // 防止 struct 没有 field 成员导致匹配失败
                cql = "match (n:c_code_file{fileName:'" + fileName + "'})" +
                        "-[:define]->" +
                        "(m:c_struct{name:'" + struct + "'})" +
                        "detach delete m";
                db.execute(cql);
            });
            updateStructs.forEach(struct -> {
                for(CDataStructureInfo cStruct: codeFileInfo.getDataStructureList()) {
                    if (cStruct.getName().equals(struct)  || cStruct.getTypedefName().equals(struct)) {
                        String cql = "match (n:c_code_file{fileName:'" + fileName + "'})" +
                                "-[:define]->" +
                                "(m:c_struct)" +
                                "where m.name = '" + cStruct.getName() +"' or m.typedefName = '" + cStruct.getTypedefName() + "' " +
                                "return m";
                        Result res = db.execute(cql);
                        while(res.hasNext()) {
                            Map<String, Object> row = res.next();
                            for(String key: res.columns()) {
                                Node node = (Node) row.get(key);
                                node.setProperty("typedefName", cStruct.getTypedefName());
                                node.setProperty("isEnum", cStruct.getIsEnum());
                                updateEntities.add(node.getId());
                            }
                        }
                    }
                }
            });
            for(Map.Entry entry: addStructMembers.entrySet()) {
                String cStruct = (String) entry.getKey();
                Set<String> fieldList = (Set<String>) entry.getValue();
                String cql = "match (n:c_code_file{fileName:'" + fileName + "'})" +
                        "-[:define]->" +
                        "(m:c_struct{name:'" + cStruct + "'})" +
                        "return m";
                Result res = db.execute(cql);
                while(res.hasNext()) {
                    Map<String, Object> row = res.next();
                    for(String key: res.columns()) {
                        Node node = (Node) row.get(key);
                        fieldList.forEach(field -> {
                            // 新增的 struct 内的 field，查询、创建节点、关联
                            for(CDataStructureInfo ds: codeFileInfo.getDataStructureList()) {
                                for(CFieldInfo f: ds.getFieldInfoList()) {
                                    if(f.getName().equals(field)) {
                                        Node fNode = db.createNode(CExtractor.c_field);
                                        fNode.setProperty("name", field);
                                        fNode.setProperty("type", f.getType());
                                        node.createRelationshipTo(fNode, CExtractor.member_of);
                                        break;
                                    }
                                }
                            }
                        });
                        updateEntities.add(node.getId());
                    }
                }
            }
            for(Map.Entry entry: deleteStructMembers.entrySet()) {
                String cStruct = (String) entry.getKey();
                Set<String> fieldList = (Set<String>) entry.getValue();
                fieldList.forEach(field -> {
                    String cql = "match (n:c_code_file{fileName:'" + fileName + "'})" +
                            "-[:define]->" +
                            "(m:c_struct{name:'" + cStruct + "'})" +
                            "<-[:member_of]-" +
                            "(o:c_field{name:'" + field + "'})" +
                            "detach delete o" +
                            "return m";
                    db.execute(cql);
                });
            }

            // functions
            addFunctions.forEach(func -> {
                for(CFunctionInfo cFunc: codeFileInfo.getFunctionInfoList()) {
                    if(cFunc.getName().equals(func)) {
                        Node node = db.createNode(CExtractor.c_function);
                        node.setProperty("name", cFunc.getName());
                        node.setProperty("fullName", cFunc.getFullName());
                        node.setProperty("belongTo", cFunc.getBelongTo());
                        node.setProperty("fullParams", cFunc.getFullParams().toString());
                        node.setProperty("isInline", cFunc.getIsInline());
                        node.setProperty("isConst", cFunc.getIsConst());
                        node.setProperty("isDefine", cFunc.getIsDefine());
                        node.setProperty("belongToName", cFunc.getBelongToName());
                        // function invoke relationship
                        List<String> fileList = codeFileInfo.getIncludeCodeFileList();
                        fileList.add(fileName);
                        for(String invokeFunc: cFunc.getCallFunctionNameList()) {
                            // 从所有可能的代码文件中查找函数
                            for(String name: fileList) {
                                String cql = "match (n:c_code_file)" +
                                        "-[:define]->" +
                                        "(m:c_function)" +
                                        "where n.fileName contains '" + name + "' " +
                                        "and m.name = '" + invokeFunc + "' " +
                                        "return m";
                                Result res = db.execute(cql);
                                if(res.hasNext()) {
                                    // 找到调用的函数实体
                                    Map<String, Object> row = res.next();
                                    for(String key: res.columns()) {
                                        Node n = (Node) row.get(key);
                                        node.createRelationshipTo(n, CExtractor.invoke);
                                    }
                                    break;
                                }
                            }
                        }
                        addEntities.add(node.getId());
                        fileNode.createRelationshipTo(node, CExtractor.define);
                        break;
                    }
                }
            });
            deleteFunctions.forEach(func -> {
                String cql = "match (n:c_code_file{fileName:'" + fileName + "'})" +
                        "-[:define]->" +
                        "(m:c_function{name:'" + func + "'})" +
                        "detach delete m";
                db.execute(cql);
            });
            updateFunctions.forEach(func -> {
                String cql = "match (n:c_code_file{fileName:'" + fileName + "'})" +
                        "-[:define]->" +
                        "(m:c_function{name:'" + func + "'})" +
                        "return m";
                Result res = db.execute(cql);
                while(res.hasNext()) {
                    Map<String, Object> row = res.next();
                    for(String key: res.columns()) {
                        Node node = (Node) row.get(key);
                        updateEntities.add(node.getId());
                        for(CFunctionInfo cFunc: codeFileInfo.getFunctionInfoList()) {
                            if(cFunc.getName().equals(func)) {
                                node.setProperty("belongTo", cFunc.getBelongTo());
                                node.setProperty("fullParams", cFunc.getFullParams());
                                node.setProperty("isInline", cFunc.getIsInline());
                                node.setProperty("isConst", cFunc.getIsConst());
                                node.setProperty("isDefine", cFunc.getIsDefine());
                                break;
                            }
                        }
                    }
                }
            });
            for(Map.Entry entry: renameFunctions.entrySet()) {
                String oldFunc = (String) entry.getKey();
                String newFunc = (String) entry.getValue();
                String cql = "match (n:c_code_file{fileName:'" + fileName + "'})" +
                        "-[:define]->" +
                        "(m:c_function{name:'" + oldFunc + "'})" +
                        "return m";
                Result result = db.execute(cql);
                if(result.hasNext()) {
                    Map<String, Object> row = result.next();
                    for (String key : result.columns()) {
                        Node node = (Node) row.get(key);
                        node.setProperty("name", newFunc);
                    }
                }
            }
            for(Map.Entry entry: addInvokeFunctions.entrySet()) {
                String funcName = (String) entry.getKey();
                Set<String> invokeFunctions = (Set<String>) entry.getValue();
                String cql = "match (n:c_code_file{fileName:'" + fileName + "'})" +
                        "-[:define]->" +
                        "(m:c_function{name:'" + funcName + "'})" +
                        "return m";
                Result result = db.execute(cql);
                if(result.hasNext()) {
                    Map<String, Object> row = result.next();
                    Node node = null;
                    for(String key: result.columns()) {
                        // 找到函数实体
                        node = (Node) row.get(key);
                    }
                    for(CFunctionInfo cFunc: codeFileInfo.getFunctionInfoList()) {
                        if (cFunc.getName().equals(funcName)) {
                            // 获取候选文件列表
                            List<String> fileList = codeFileInfo.getIncludeCodeFileList();
                            fileList.add(fileName);
                            // 处理每个调用函数: invokeFunc
                            for(String invokeFunc: invokeFunctions) {
                                // 从所有可能的代码文件中查找函数
                                for(String name: fileList) {
                                    // 根据文件名和函数名定位到被调用的函数实体
                                    cql = "match (n:c_code_file)" +
                                            "-[:define]->" +
                                            "(m:c_function)" +
                                            "where n.fileName contains '" + name + "' " +
                                            "and m.name = '" + invokeFunc + "' " +
                                            "return m";
                                    Result res = db.execute(cql);
                                    if(res.hasNext()) {
                                        // 找到调用的函数实体
                                        row = res.next();
                                        for(String k: res.columns()) {
                                            Node n = (Node) row.get(k);
                                            node.createRelationshipTo(n, CExtractor.invoke);
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            for(Map.Entry entry: deleteInvokeFunctions.entrySet()) {
                String funcName = (String) entry.getKey();
                Set<String> invokeFunctions = (Set<String>) entry.getValue();
                for(String invokeFunc: invokeFunctions) {
                    String cql = "match (n:c_code_file{fileName:'" + fileName + "'})" +
                            "-[:define]->" +
                            "(m:c_function{name:'" + funcName + "'})" +
                            "-[r:invoke]->" +
                            "(o:c_function{name:'" + invokeFunc + "'})" +
                            "delete r";
                    db.execute(cql);
                }
            }
            tx.success();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * 建立 commit 与 code 之间的关系（ADD, UPDATE）
     */
    private void createRelationships(Set<Long> commitIds, String fileName) {
        try (Transaction tx = db.beginTx()) {
//            Node fileNode = db.findNode(CExtractor.c_code_file, "fileName", fileName);
            addEntities.forEach(id -> {
                Node node = db.getNodeById(id);
                for(Long commitId: commitIds) {
                    Node commitNode = db.getNodeById(commitId);
                    commitNode.createRelationshipTo(node, CCodeMentionExtractor.ADD);
                }
            });
            updateEntities.forEach(id -> {
                Node node = db.getNodeById(id);
                for(Long commitId: commitIds) {
                    Node commitNode = db.getNodeById(commitId);
                    commitNode.createRelationshipTo(node, CCodeMentionExtractor.UPDATE);
                }
            });
            tx.success();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * 定义类 edit action
     */
    static class EditAction {
        String type;

        // 修改节点及始末位置
        String tNode;
        int Start, End;

        // 修改节点的父结点及始末位置
        Tree Node;
        String pNode;
        int pStart, pEnd;

        // 具体的修改内容，初始只是记录下来，后续进一步解析
        List<String> content = new ArrayList<>();
    }
}
