package cn.edu.pku.sei.intellide.graph.extraction.c_code.infos;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.ASTUtil;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.cdt.core.dom.ast.*;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.util.*;

public class CDataStructureInfo {
    @Getter
    private long id = -1;
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

    private BatchInserter inserter;

    public CDataStructureInfo() {}

    public CDataStructureInfo(BatchInserter inserter) {
        this.inserter = inserter;
    }

    public void initEnumFieldInfo() {
        for (IASTNode node: simpleDeclaration.getChildren()) {
            for (IASTNode node2: node.getChildren()) {
                if (node2 instanceof IASTEnumerationSpecifier.IASTEnumerator) {
                    CFieldInfo fieldInfo = new CFieldInfo();
                    fieldInfo.setName(((IASTEnumerationSpecifier.IASTEnumerator) node2).getName().toString());
                    fieldInfo.setType("int");
                    if(this.inserter != null) fieldInfo.createNode(inserter);
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
                if(this.inserter != null) fieldInfo.createNode(inserter);
                fieldInfoList.add(fieldInfo);
            }
        }
    }

    public long createNode() {
        if(id != -1) return id;
        Map<String, Object> map = new HashMap<>();
        map.put(CExtractor.NAME, name);
        map.put(CExtractor.CONTENT, content);
        map.put(CExtractor.TYPEDEFNAME, typedefName);
        map.put(CExtractor.ISENUM, isEnum);
        id = inserter.createNode(map, CExtractor.c_struct);
        return id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, typedefName);
    }

    @Override
    public boolean equals(Object obj) {
        CDataStructureInfo ds = (CDataStructureInfo) obj;
        if(fieldInfoList.size() != ds.getFieldInfoList().size()) {
            return false;
        }
        for(CFieldInfo field: fieldInfoList) {
            if(!ds.getFieldInfoList().contains(field)) {
                return false;
            }
        }
        return (this.name.equals(ds.getName()) && this.typedefName.equals(ds.getTypedefName()));
    }
}
