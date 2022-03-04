package cn.edu.pku.sei.intellide.graph.extraction.c_code.infos;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.cdt.core.dom.ast.IASTDeclSpecifier;
import org.eclipse.cdt.core.dom.ast.IASTSimpleDeclaration;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class CVariableInfo {
    @Getter
    private long id = -1;

    /**
     * 变量名(如果是宏那就是宏的内容)
     */
    @Getter
    @Setter
    private String name;

    /**
     * 内容 #define _TEST这样的设为""
     */
    @Getter
    @Setter
    private String content;

    /**
     * 属于哪里，或者是一个最外层的变量而已
     */
    @Getter
    @Setter
    private String belongTo;

    @Getter
    @Setter
    private Boolean isDefine;

    /**
     * 是不是一个结构体变量，进一步筛选是不是自己定义的结构体的变量
     */
    @Getter
    @Setter
    private Boolean isStructVariable;

    /**
     * 非存储属性，用来后续处理修饰符
     */
    @Getter
    @Setter
    private IASTDeclSpecifier specifier;

    /**
     * 包含自身所有信息
     */
    @Getter
    @Setter
    private IASTSimpleDeclaration simpleDeclaration;

    public long createNode(BatchInserter inserter) {
        if (id != -1) {
            return id;
        }
        Map<String, Object> map = new HashMap<>();
        map.put(CExtractor.NAME, name);
        map.put(CExtractor.CONTENT, content);
        map.put(CExtractor.BELONGTO, belongTo);
        map.put(CExtractor.ISDEFINE, isDefine);
        map.put(CExtractor.ISSTRUCTVARIABLE, isStructVariable);
        id = inserter.createNode(map, CExtractor.c_variable);
        return id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, belongTo, content);
    }

    @Override
    public boolean equals(Object obj) {
        CVariableInfo var = (CVariableInfo) obj;
        return (this.name.equals(var.getName()) && this.belongTo.equals(var.getBelongTo())
                && this.isDefine.equals(var.isDefine) && this.isStructVariable.equals(var.isStructVariable));
    }

    @Override
    public String toString() {
        return "CVariableInfo{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", content='" + content + '\'' +
                ", belongTo='" + belongTo + '\'' +
                ", isDefine=" + isDefine +
                ", isStructVariable=" + isStructVariable +
                ", specifier=" + specifier +
                ", simpleDeclaration=" + simpleDeclaration +
                '}';
    }
}
