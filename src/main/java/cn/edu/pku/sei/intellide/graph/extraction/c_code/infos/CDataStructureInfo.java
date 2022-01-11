package cn.edu.pku.sei.intellide.graph.extraction.c_code.infos;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.ASTUtil;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.cdt.core.dom.ast.*;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CDataStructureInfo {
    @Getter
    private long id;
    @Getter
    @Setter
    private String name;
    @Getter
    @Setter
    private String content;
    @Getter
    @Setter
    private String typedefName;
    @Getter
    @Setter
    private Boolean isEnum;
    @Getter
    @Setter
    private IASTSimpleDeclaration simpleDeclaration;
    @Getter
    @Setter
    private List<CFieldInfo> fieldInfoList = new ArrayList<>();


    public void initEnumFieldInfo() {
        for (IASTNode node: simpleDeclaration.getChildren()) {
            for (IASTNode node2: node.getChildren()) {
                if (node2 instanceof IASTEnumerationSpecifier.IASTEnumerator) {
                    CFieldInfo fieldInfo = new CFieldInfo();
                    fieldInfo.setName(((IASTEnumerationSpecifier.IASTEnumerator) node2).getName().toString());
                    fieldInfo.setType("int");
                    fieldInfoList.add(fieldInfo);
                }
            }
        }
    }

    public void initStructFieldInfo() {
        for (IASTNode node: simpleDeclaration.getChildren()) {
            for (IASTNode node2: node.getChildren()) {
                CFieldInfo fieldInfo = new CFieldInfo();
                StringBuilder name = new StringBuilder();
                StringBuilder type = new StringBuilder();
                boolean isPointer = false;
                boolean isArray = false;
                boolean isNull = false;
                for(IASTNode node3: node2.getChildren()) {
                    if (node3 instanceof IASTDeclarator) {
                        // 名字部分
                        name.append(((IASTDeclarator) node3).getName().toString());
                        if (ASTUtil.hasPointerType((IASTDeclarator)node3)){
                            // 指针
                            isPointer = true;
                        }
                        if (node3 instanceof IASTArrayDeclarator) {
                            isArray = true;
                        }
                    } else if (node3 instanceof IASTDeclSpecifier) {
                        // 类型部分 还没有处理函数指针
                        type.append(node3.getRawSignature());
                    } else {
                        isNull = true;
                    }
                }
                if ("".equals(name.toString())) {
                    continue;
                }
                fieldInfo.setName(name.toString());
                if (isPointer) {
                    type.append("*");
                }
                if (isArray) {
                    type.append("[]");
                }
                fieldInfo.setType(type.toString());
                fieldInfoList.add(fieldInfo);
            }
        }
    }

    public long createNode(BatchInserter inserter) {
        Map<String, Object> map = new HashMap<>();
        map.put(CExtractor.NAME, name);
        map.put(CExtractor.CONTENT, content);
        map.put(CExtractor.TYPEDEFNAME, typedefName);
        map.put(CExtractor.ISENUM, isEnum);
        id = inserter.createNode(map, CExtractor.c_field);
        return id;
    }
}
