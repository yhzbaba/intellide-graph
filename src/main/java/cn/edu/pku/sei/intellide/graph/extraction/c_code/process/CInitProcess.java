package cn.edu.pku.sei.intellide.graph.extraction.c_code.process;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.FunctionPointerUtil;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.FunctionUtil;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.VariableUtil;

public class CInitProcess {
    public static void init() {
        FunctionPointerUtil.init();
        FunctionUtil.init();
        VariableUtil.init();
    }
}
