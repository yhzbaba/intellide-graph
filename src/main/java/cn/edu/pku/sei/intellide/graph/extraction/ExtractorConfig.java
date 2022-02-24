package cn.edu.pku.sei.intellide.graph.extraction;

import lombok.Getter;

public class ExtractorConfig {

    @Getter
    private String className, graphDir, srcCodeDir, dstCodeDir, dataDir;

    public ExtractorConfig(String className, String graphDir, String srcCodeDir, String dstCodeDir, String dataDir) {
        this.className = className;
        this.graphDir = graphDir;
        this.srcCodeDir = srcCodeDir;
        this.dstCodeDir = dstCodeDir;
        this.dataDir = dataDir;
    }

}