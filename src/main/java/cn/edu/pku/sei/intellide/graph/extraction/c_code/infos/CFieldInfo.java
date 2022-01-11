package cn.edu.pku.sei.intellide.graph.extraction.c_code.infos;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import lombok.Getter;
import lombok.Setter;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.util.HashMap;
import java.util.Map;

public class CFieldInfo {
    @Getter
    private long id;
    @Getter
    @Setter
    private String name;
    @Getter
    @Setter
    private String type = "int";

    private long createNode(BatchInserter inserter) {
        Map<String, Object> map = new HashMap<>();
        map.put(CExtractor.NAME, name);
        map.put(CExtractor.TYPE, type);
        id = inserter.createNode(map, CExtractor.c_field);
        return id;
    }
}
