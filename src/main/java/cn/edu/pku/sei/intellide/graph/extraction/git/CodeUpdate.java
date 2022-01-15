package cn.edu.pku.sei.intellide.graph.extraction.git;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;

import java.util.List;
import java.util.Map;

public class CodeUpdate extends KnowledgeExtractor {

    private Map<String, GitUpdate.CommitInfo> commitInfos;


    public static void main(String[] args) {

    }

    CodeUpdate(Map<String, GitUpdate.CommitInfo> commitInfos) {
        this.commitInfos = commitInfos;
        extraction();
    }

    @Override
    public void extraction() {
        for(GitUpdate.CommitInfo commitInfo: commitInfos.values()) {
            List<String> diffInfos = commitInfo.diffInfo;
            for(String diffInfo: diffInfos) {
                System.out.println(diffInfo);
                // 只处理 .h .c 文件
                if(!diffInfo.contains(".c") && !diffInfo.contains(".h")) {
                    continue;
                }
                // 获取修改文件路径(需要添加项目代码路径作为前缀来进行定位)
                String filePath = diffInfo.substring(diffInfo.lastIndexOf(" ") + 1);
                System.out.println("filePath: " + filePath);

                // 后续步骤：获取图谱信息：解析代码文件；集合比对

            }
        }
    }
}
