package cn.edu.pku.sei.intellide.graph.extraction.c_code.infos;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import lombok.Getter;
import lombok.Setter;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class CFieldInfo {
    @Getter
    private long id = -1;
    @Getter
    @Setter
    private String name;
    @Getter
    @Setter
    private String type = "int";

    public long createNode(BatchInserter inserter) {
        if (id != -1) {
            return id;
        }
        Map<String, Object> map = new HashMap<>();
        map.put(CExtractor.NAME, name);
        map.put(CExtractor.TYPE, type);
        id = inserter.createNode(map, CExtractor.c_field);
        return id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }

    @Override
    public boolean equals(Object obj) {
        CFieldInfo field = (CFieldInfo) obj;
        return (this.name.equals(field.getName()) && this.type.equals(field.getType()));
    }
}
