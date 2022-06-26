package cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity;

import lombok.Data;

import java.util.List;

@Data
public class StringAndStringList {
    private String name;

    private List<String> stringList;

    public StringAndStringList(String name, List<String> stringList) {
        this.name = name;
        this.stringList = stringList;
    }

    public StringAndStringList() {
    }
}
