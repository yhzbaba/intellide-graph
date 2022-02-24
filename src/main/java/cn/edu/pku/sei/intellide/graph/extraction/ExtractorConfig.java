package cn.edu.pku.sei.intellide.graph.extraction;

import lombok.Getter;

public class ExtractorConfig {

    @Getter
    private String className, graphDir, prevCodeDir, codeDir, dataDir;

    public ExtractorConfig(String className, String graphDir, String prevCodeDir, String codeDir, String dataDir) {
        this.className = className;
        this.graphDir = graphDir;
        this.prevCodeDir = prevCodeDir;
        this.codeDir = codeDir;
        this.dataDir = dataDir;
    }

}