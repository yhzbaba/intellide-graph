package cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CFunctionInfo;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class IsArgOfFunctionReturnClass {
    List<CFunctionInfo> functionList;

    Integer index;

    public IsArgOfFunctionReturnClass() {
        functionList = new ArrayList<>();
    }
}
