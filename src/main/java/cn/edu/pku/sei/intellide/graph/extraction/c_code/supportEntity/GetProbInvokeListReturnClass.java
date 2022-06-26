package cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CFunctionInfo;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class GetProbInvokeListReturnClass {
    private NumedStatement numedStatement;

    private List<CFunctionInfo> functionInfoList;

    public static List<CFunctionInfo> getAllFunctions(List<GetProbInvokeListReturnClass> param) {
        List<CFunctionInfo> res = new ArrayList<>();
        for (GetProbInvokeListReturnClass item : param) {
            res.addAll(item.getFunctionInfoList());
        }
        return res;
    }

    public GetProbInvokeListReturnClass(NumedStatement numedStatement, List<CFunctionInfo> functionInfoList) {
        this.numedStatement = numedStatement;
        this.functionInfoList = functionInfoList;
    }

    public GetProbInvokeListReturnClass() {
    }
}
