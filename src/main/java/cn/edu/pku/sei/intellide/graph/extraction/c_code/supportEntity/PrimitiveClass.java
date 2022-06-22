package cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity;

import lombok.Data;

/**
 * @author yhzbaba
 * 全局有维护一个map，建立typedef的名字跟真正类型名字的映射关系
 * <p>
 * 真正名字类型包含：
 * 1、名字，并且当这是一个函数指针时，名字为空字符串
 * 2、是否为一个函数指针类型
 * 3、定义在哪个文件中的重命名类型，若为基础类型，则为空字符串
 * 4、返回值类型（若为函数指针），否则空字符串
 */
@Data
public class PrimitiveClass {
    private String primitiveName = "";

    private Boolean isFunPointer;

    private String fileName = "";

    private String returnType = "";

    /**
     * 用于基础类型创建
     */
    public PrimitiveClass() {
        isFunPointer = false;
    }

    public PrimitiveClass(String primitiveName, Boolean isFunPointer, String fileName, String returnType) {
        this.primitiveName = primitiveName;
        this.isFunPointer = isFunPointer;
        this.fileName = fileName;
        this.returnType = returnType;
    }

    @Override
    public String toString() {
        return "PrimitiveClass{" +
                "primitiveName='" + primitiveName + '\'' +
                ", isFunPointer=" + isFunPointer +
                ", fileName='" + fileName + '\'' +
                ", returnType='" + returnType + '\'' +
                '}';
    }
}
