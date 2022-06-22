package cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CFunctionInfo;
import lombok.Data;

import java.util.Stack;

/**
 * 一个函数有很多调用点，根据调用点名字合并"名字栈"，拥有一个栈和这个栈的名字
 */
@Data
public class NameFunctionStack {
    private String name;

    private Stack<CFunctionInfo> functionStack = new Stack<>();
}
