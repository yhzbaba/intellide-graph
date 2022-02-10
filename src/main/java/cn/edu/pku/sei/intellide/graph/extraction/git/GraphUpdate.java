package cn.edu.pku.sei.intellide.graph.extraction.git;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import com.github.gumtreediff.actions.EditScript;
import com.github.gumtreediff.actions.EditScriptGenerator;
import com.github.gumtreediff.actions.SimplifiedChawatheScriptGenerator;
import com.github.gumtreediff.actions.model.Action;
import com.github.gumtreediff.client.Run;
import com.github.gumtreediff.gen.TreeGenerators;
import com.github.gumtreediff.matchers.MappingStore;
import com.github.gumtreediff.matchers.Matchers;
import com.github.gumtreediff.tree.Tree;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 根据 commit 信息更新代码图谱，在 GitUpdate.java 中被调用
 */
public class GraphUpdate extends KnowledgeExtractor {

    public static void main(String[] args) {
        /*
        One example of edit action
        ===
        insert-tree
        ---
        ExprStatement [33,39]
            Some [33,39]
                Assignment [33,38]
                    Ident [33,34]
                        GenericString: a [33,34]
                    GenericString: = [35,36]
                    Constant: 1 [37,38]
                GenericString: ; [38,39]
        to
        Compound [20,43]
        at 0
         */
        System.out.println(getFileFromDiff("add test.c to test.c"));
    }

    private Map<String, GitUpdate.CommitInfo> commitInfos;

    /* 全局数据结构，记录代码文件修改的信息 */


    public GraphUpdate(Map<String, GitUpdate.CommitInfo> commitInfos) {
        this.commitInfos = commitInfos;
        this.extraction();
    }

    @Override
    public void extraction() {
        for(GitUpdate.CommitInfo commitInfo: commitInfos.values()) {
            /* 先沿 parent 递归处理 */
            for(String parent: commitInfo.parent) {
                if(commitInfos.containsKey(parent)) {
                    GitUpdate.CommitInfo parentCommit = commitInfos.get(parent);
                    if(!parentCommit.isHandled) {
                        parseCommit(parentCommit);
                        parentCommit.isHandled = true;
                    }
                }
            }
            /* 处理完 parent commits */
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
            /* 处理单个文件 */
            String file = getFileFromDiff(diff);
            // TODO: 这里后期要改成项目代码的路径
            String srcFile = "" + file;
            String dstFile = "" + file;

            /* 调用 GumTree API 获得 edit actions */

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
                    System.out.println(action);
                    // 解析单个 edit action
                    parseEditAction(action);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            /* 完成单个文件的修改信息的记录，统一执行数据库事务进行图谱更新 */

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
    private void parseEditAction(Action action) {

    }


    /**
     * 定义类 edit action
     */
    class EditAction {
        String type;

    }

}
