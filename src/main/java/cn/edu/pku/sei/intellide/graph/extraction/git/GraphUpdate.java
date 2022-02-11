package cn.edu.pku.sei.intellide.graph.extraction.git;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CCodeFileInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CDataStructureInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CFunctionInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CVariableInfo;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.GetTranslationUnitUtil;
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

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 根据 commit 信息更新代码图谱，在 GitUpdate.java 中被调用
 */
public class GraphUpdate extends KnowledgeExtractor {

    public static void main(String[] args) {
        String filePath = "D:\\documents\\SoftwareReuse\\knowledgeGraph\\gradDesign\\test2.c";
        String content = getFileContent(filePath);
        int s = 73;
        int e = 103;
        System.out.println(content.substring(s,e));
//        String s = "#define FD_ZERO(set) (((fd_set FAR *)(set))->fd_count=0)";
//        Matcher m = Pattern.compile("(\\s+)([\\w_()]+)(\\s+)").matcher(s);
//        if(m.find()) {
//            System.out.println(m.group(2));
//        }
//        String s = "int f;";
//        System.out.println(s.substring(s.lastIndexOf(" "), s.indexOf(";")));
    }

    private Map<String, GitUpdate.CommitInfo> commitInfos;

    private String srcContent;
    private String dstContent;

    /*
     * 记录修改涉及到的代码信息，主要是元素名称，与 CCodeFileInfo 中的信息进行匹配
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

    private Set<String> addFunctions = new HashSet<>();
    private Set<String> deleteFunctions = new HashSet<>();
    private Set<String> updateFunctions = new HashSet<>();

    // 记录单个 commit 修改的代码实体的id
    Set<Long> updateEntities = new HashSet<Long>();
    Set<Long> addEntities = new HashSet<Long>();
    Set<Long> deleteEntities = new HashSet<Long>();

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
        for(String diff: commitInfo.diffSummary) {
            if(!diff.contains(".c") || !diff.contains(".h")) continue;

            /* 以单个文件作为单位进行处理，首先进行初始化工作 */

            // 获取文件名
            String file = getFileFromDiff(diff);
            // TODO: 这里后期要改成项目代码的路径
            String srcFile = "" + file;
            String dstFile = "" + file;

            // 将文件内容读入字符串
            srcContent = getFileContent(srcFile);
            dstContent = getFileContent(dstFile);

            // 解析被修改的代码文件(dstFile)
            IASTTranslationUnit translationUnit = null;
            try {
                translationUnit = GetTranslationUnitUtil.getASTTranslationUnit(new File(dstFile));
            } catch (CoreException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            CCodeFileInfo codeFileInfo = new CCodeFileInfo(file, dstFile, translationUnit);
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
                    /* 解析单个 edit action */
                    EditAction editAction = parseEditAction(action, action.getNode().getParent());
                    /* 根据 action 类型，具体解析修改内容 */
                    parseActionContent(editAction);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            // 完成单个文件的修改信息的记录，统一执行数据库事务进行图谱更新

            // 建立 Commit 与 代码 实体的关联

        }
    }

    /**
     * 获取修改的代码文件名称
     */
    private static String getFileFromDiff(String diff) {
        String res = "";
        if(diff.contains(".c")) {
            Pattern r = Pattern.compile("\\s\\w+.c");
            Matcher m = r.matcher(diff);
            if(m.find()) {
                res = m.group().substring(1);
            }
        }
        else {
            Pattern r = Pattern.compile("\\s\\w+.h");
            Matcher m = r.matcher(diff);
            if(m.find()) {
                res = m.group().substring(1);
            }
        }
        return res;
    }

    /**
     * 对单个编辑动作进行解析，获取具体的修改内容
     * 对各类 edit action 进行分类，并针对性地记录
     * @param action
     */
    private EditAction parseEditAction(Action action, Tree Node) {
        EditAction editAction = new EditAction();
        // 父节点的类型和位置
        String pNode = Node.toString();
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
        return editAction;
    }

    /**
     * 针对单个 action，依据 type 和 tNode 判断修改的具体类型，对 content 属性进行解析并记录
     * @param editAction
     */
    private void parseActionContent(EditAction editAction) {
        if(editAction.type.contains("insert")) {
            // 依据 edit action 节点的类型进行相应的处理
            if(editAction.tNode.contains("CppTop")) {
                int i = 0;
                while(i < editAction.content.size()) {
                    if(editAction.content.get(i).contains("Include")) {
                        // add include files
                        addIncludes.add(getItemName(editAction.Start, editAction.End, dstContent, "Include"));
                        break;
                    }
                    else if(editAction.content.get(i).contains("DefineVar")) {
                        // add Macro #define(as variable)
                        addVariables.add(getItemName(editAction.Start, editAction.End, dstContent, "MacroVar"));
                    }
                    else if(editAction.content.get(i).contains("DefineFunc")) {
                        // add Macro #define(as function)
                        addFunctions.add(getItemName(editAction.Start, editAction.End, dstContent, "MacroFunc"));
                    }
                    i++;
                }
            }
            else if(editAction.tNode.contains("Declaration")) {
                int i = 0;
                while(i < editAction.content.size()) {
                    if(editAction.content.get(i).contains("GenericString: typedef")) {
                        // typedef struct
                        addStructs.add(getItemName(0, 0, editAction.content.get(i+1), "Typedef"));
                        break;
                    }
                    else {
                        String tmp = dstContent.substring(editAction.Start, editAction.End);
                        if(tmp.contains("struct")) {
                            // struct(no typedef)
                            addStructs.add(getItemName(0, 0, tmp, "Struct"));
                        }
                        else {
                            Matcher m = Pattern.compile("\\w+\\s(\\w+)\\(.*\\);").matcher(tmp);
                            if(m.find()) {
                                // function declaration
                                addFunctions.add(m.group(1));
                            }
                            else {
                                // global variable
                                addVariables.add(tmp.substring(tmp.lastIndexOf(" ")+1, tmp.indexOf(";")));
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
                        addFunctions.add(getItemName(editAction.Start, editAction.End, dstContent, "Function"));
                    }
                }
            }
            else if(editAction.tNode.contains("ExprStatement")) {
                //

            }
            else if(editAction.tNode.contains("DeclList")) {
                // 函数内部表达式

            }
        }
        else if(editAction.type.contains("delete")) {

        }
        else if(editAction.type.contains("update")) {

        }
        else if(editAction.type.contains("move")) {

        }
    }

    /**
     * 将文件内容输出到字符串，利用位置索引获取代码元素的名称
     * @param type 标识修改代码元素的类型，如 function, variable...
     */
    private String getItemName(int start, int end, String fileContent, String type) {
        String res = "";
        String tmp = "";
        if(start == 0 && end == 0) tmp = fileContent;
        else tmp = fileContent.substring(start, end);
        if(type.equals("Include")) {
            res = tmp.substring(tmp.indexOf("\"") + 1, tmp.lastIndexOf("\""));
        }
        else if(type.equals("MacroVar")) {
            Matcher m = Pattern.compile("(\\s+)(\\w+)(\\s+)").matcher(tmp);
            if(m.find()) {
                res = m.group(2);
            }
        }
        else if(type.equals("MacroFunc")) {
            // format: function(param...)
            Matcher m = Pattern.compile("(\\s+)([\\w_()]+)(\\s+)").matcher(tmp);
            if(m.find()) {
                res = m.group(2);
            }
        }
        else if(type.equals("Typedef")) {
            // format: GenericString: name [s,e]
            Matcher m = Pattern.compile("\\s(\\w+)\\s\\[").matcher(tmp);
            if(m.find()) {
                res = m.group(1);
            }
        }
        else if(type.equals("Struct")) {
            Matcher m = Pattern.compile("struct\\s(\\w+)\\s").matcher(tmp);
            if(m.find()) {
                res = m.group(1);
            }
        }
        else if(type.equals("Function")) {
            // TODO: 最好的处理是函数名称+参数列表，这里暂且只使用函数名
            Matcher m = Pattern.compile("\\w+\\s(\\w+)\\(.*\\)").matcher(tmp);
            if(m.find()) {
                res = m.group(1);
            }
        }
        return res;
    }

    private static String getFileContent(String filePath) {
        String res = "";
        File file = new File(filePath);
        FileReader reader = null;
        try {
            reader = new FileReader(file);
            BufferedReader bReader = new BufferedReader(reader);
            StringBuilder sb = new StringBuilder();
            String s = "";
            while ((s =bReader.readLine()) != null) {
                sb.append(s + "\n");
            }
            bReader.close();
            res = sb.toString();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return res;
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
