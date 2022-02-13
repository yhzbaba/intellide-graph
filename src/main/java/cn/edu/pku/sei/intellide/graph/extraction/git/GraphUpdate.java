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
 */
public class GraphUpdate extends KnowledgeExtractor {

    public static void main(String[] args) {
        String cFunc = "main";
        String cql = "match (n:c_code_file)" +
                "-[:define]->" +
                "where n.fileName contains '" + cFunc + "'" +
                "return m";
        System.out.println(cql);
//        String filePath = "D:\\documents\\SoftwareReuse\\knowledgeGraph\\gradDesign\\test2.c";
//        String content = getFileContent(filePath);
//        int s = 227;
//        int e = 322;
//        System.out.println(content.substring(s,e));
//        String s = "replace oldFunc by newFunc";
//        Matcher m = Pattern.compile("replace\\s(\\w+)\\sby\\s(\\w+)").matcher(s);
//        if(m.find()) {
//            System.out.println(m.group(1) + "\n" + m.group(2));
//        }
    }

    private Map<String, GitUpdate.CommitInfo> commitInfos;

    private String srcContent;
    private String dstContent;

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
    Set<Long> deleteEntities = new HashSet<>();

    public GraphUpdate(Map<String, GitUpdate.CommitInfo> commitInfos) {
        this.commitInfos = commitInfos;
        this.extraction();
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

    /**
     * 处理 diffSummary 包含的文件
     * @param commitInfo 单个commit涉及的所有修改文件
     */
    private void parseCommit(GitUpdate.CommitInfo commitInfo) {
        long commitId = commitInfo.id;
        for(String diff: commitInfo.diffSummary) {
            if(!diff.contains(".c") || !diff.contains(".h")) continue;

            /* 以单个文件作为单位进行处理，首先进行初始化工作 */

            // 获取文件名
            String fileName = Utils.getFileFromDiff(diff);
            // TODO: 这里后期要改成项目代码的路径
            String srcFile = "" + fileName;
            String dstFile = "" + fileName;

            // 将文件内容读入字符串
            srcContent = Utils.getFileContent(srcFile);
            dstContent = Utils.getFileContent(dstFile);

            // 解析被修改的代码文件(dstFile)
            IASTTranslationUnit translationUnit = null;
            try {
                translationUnit = GetTranslationUnitUtil.getASTTranslationUnit(new File(dstFile));
            } catch (CoreException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            CCodeFileInfo codeFileInfo = new CCodeFileInfo(fileName, dstFile, translationUnit);
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

            updateKG(codeFileInfo, fileName, commitId);

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
        String pNode = Node.toString();
        if(pNode.contains("Compound")) pNode = Node.getParent().toString();
        else pNode = Node.toString();
        editAction.pNode = pNode.substring(0, pNode.indexOf(" "));
        editAction.pStart = Integer.parseInt(pNode.substring(pNode.indexOf("[") + 1, pNode.indexOf(",")));
        editAction.pEnd = Integer.parseInt(pNode.substring(pNode.indexOf(",") + 1, pNode.indexOf("]"))) - 1;
        String[] lines = action.toString().split("\n");
        int i = 0;
        while(i < lines.length) {
            if(lines[i].equals("===")) {
                editAction.type = lines[++i]; i += 2;
                String tmp = lines[i];
                editAction.tNode = tmp.substring(0, tmp.indexOf(" "));
                editAction.Start = Integer.parseInt(tmp.substring(tmp.indexOf("[") + 1, tmp.indexOf(",")));
                editAction.End = Integer.parseInt(tmp.substring(tmp.indexOf(",") + 1, tmp.indexOf("]"))) - 1;
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
                }
            }
            /* 函数内部的修改
             * 依照目前的处理，主要是方法调用的增删，标识符的修改，参数列表的修改
             * Map<FunctionName, List<invokedFunctions> >
             * TODO: 修改的内容最好尽可能地细化，相应用于记录的数据结构也要修改
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
                }
                // 简单记录修改过的函数名称
                if(editAction.type.contains("insert")) {
                    updateFunctions.add(Utils.getItemName(editAction.pStart, editAction.pEnd, dstContent, "Function"));
                }
                else if(editAction.type.contains("delete")) {
                    updateFunctions.add(Utils.getItemName(editAction.pStart, editAction.pEnd, srcContent, "FuncDef"));
                }
            }
            else if(editAction.tNode.contains("Storage")) {
                // 函数/变量标识符的修改

            }
            else if(editAction.tNode.contains("ParameterType")) {
                // 函数参数列表的修改

            }
        }
    }
    

    /**
     * 更新图谱内容
     */
    private void updateKG(CCodeFileInfo codeFileInfo, String fileName, long commitId) {
        GraphDatabaseService db = this.getDb();
        try(Transaction tx = db.beginTx()) {
            // commit -update-> code_file
            Node commitNode = db.getNodeById(commitId);
            Node fileNode = db.findNode(CExtractor.c_code_file, "fileName", fileName);
            commitNode.createRelationshipTo(fileNode, CCodeMentionExtractor.UPDATE);
            // include files
            addIncludes.forEach(file -> {
                String cql = "match (n:c_code_file{fileName:'" + fileName + "'}) " +
                        "match (m:c_code_file{fileName:'" + file + "'}) " +
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
               for(CVariableInfo cVar: codeFileInfo.getVariableInfoList()) {
                   if(cVar.getName().equals(var)) {
                       Node node = db.createNode(CExtractor.c_variable);
                       node.setProperty("name", cVar.getName());
                       node.setProperty("content", cVar.getContent());
                       node.setProperty("belongTo", cVar.getBelongTo());
                       node.setProperty("isDefine", cVar.getIsDefine());
                       node.setProperty("isStructVariable", cVar.getIsStructVariable());
                       addEntities.add(node.getId());
                       break;
                   }
               }
            });
            deleteVariables.forEach(var -> {
                for(CVariableInfo cVar: codeFileInfo.getVariableInfoList()) {
                    if(cVar.getName().equals(var)) {
                        String cql = "match (n:c_code_file{fileName:'" + fileName + "'})" +
                                "-[:define]->" +
                                "(m:c_variable{name:'" + cVar.getName() + "'})" +
                                "detach delete m";
                        db.execute(cql);
                        // TODO: 删除操作实体不存在，如何建立关系？
                        break;
                    }
                }
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
                    if(cStruct.getName().equals(struct)) {
                        Node node = db.createNode(CExtractor.c_variable);
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
                        break;
                    }
                }
            });
            deleteStructs.forEach(struct -> {
                for(CDataStructureInfo cStruct: codeFileInfo.getDataStructureList()) {
                    if(cStruct.getName().equals(struct)) {
                        for(CFieldInfo field: cStruct.getFieldInfoList()) {
                            String cql = "match (n:c_code_file{fileName:'" + fileName + "'})" +
                                    "-[:define]->" +
                                    "(m:c_variable{name:'" + cStruct.getName() + "'})" +
                                    "<-[r:member_of]-" +
                                    "(o:c_field{name:" + field.getName() + "'})" +
                                    "detach delete m, o";
                            db.execute(cql);
                        }
                        break;
                    }
                }
            });
            updateStructs.forEach(struct -> {
                for(CDataStructureInfo cStruct: codeFileInfo.getDataStructureList()) {
                    if (cStruct.getName().equals(struct)) {
                        String cql = "match (n:c_code_file{fileName:'" + fileName + "'})" +
                                "-[:define]->" +
                                "(m:c_struct{name:'" + cStruct.getName() + "'})" +
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
                        node.setProperty("fullParams", cFunc.getFullParams());
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
            for(Map.Entry entry: addInvokeFunctions.entrySet()) {
                String funcName = (String) entry.getKey();
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
                            for(String invokeFunc: cFunc.getCallFunctionNameList()) {
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
                List<String> invokeFunctions = (List<String>) entry.getValue();
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
     * 建立 commit 与 code 之间的关系（ADD, DELETE, UPDATE）
     */
    private void createRelationships() {

    }

    /**
     * 定义类 edit action
     */
    class EditAction {
        String type;

        // 修改节点及始末位置
        String tNode;
        int Start, End;

        // 修改节点的父结点及始末位置
        String pNode;
        int pStart, pEnd;

        // 具体的修改内容，初始只是记录下来，后续进一步解析
        List<String> content = new ArrayList<>();
    }
}
