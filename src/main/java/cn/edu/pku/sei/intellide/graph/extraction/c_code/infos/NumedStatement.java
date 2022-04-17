package cn.edu.pku.sei.intellide.graph.extraction.c_code.infos;

import lombok.Data;
import org.eclipse.cdt.core.dom.ast.IASTStatement;

import java.util.ArrayList;
import java.util.List;

@Data
public class NumedStatement {
    /**
     * 一个函数中，这条语句处于第几层的第几个块
     * 比如说第一层第二个块，就是1,2,-1,-1用来标识结尾
     * {
     * --{
     * ----...
     * --}
     * --{
     * ----Here!
     * --}
     * }
     */
    private List<Integer> layer = new ArrayList<>();

    /**
     * 在这个块的第几句
     * 注意：if语句里的compound是包含在if里面的，所以if算一句statement
     * 但是直接的一个{}块是compound，在本层也算一句statement
     */
    private int seqNum = 0;

    private IASTStatement statement;

    public NumedStatement(IASTStatement statement) {
        this.statement = statement;
    }

    /**
     * 往 layer 成员中加一组层数
     *
     * @param list
     */
    public void addLayer(List<Integer> list) {
        layer.addAll(list);
    }

    /**
     * 往 layer 成员中加一个层数
     *
     * @param number
     */
    public void addLayer(int number) {
        layer.add(number);
    }

    @Override
    public String toString() {
        return "NumedStatement{" +
                "layer=" + layer +
                ", seqNum=" + seqNum +
                ", statement=" + statement.getRawSignature() +
                '}';
    }
}
