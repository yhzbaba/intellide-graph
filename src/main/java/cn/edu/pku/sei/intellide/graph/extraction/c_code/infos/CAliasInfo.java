package cn.edu.pku.sei.intellide.graph.extraction.c_code.infos;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import com.google.common.base.Preconditions;
import lombok.Getter;
import lombok.Setter;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.util.HashMap;
import java.util.Map;

public class CAliasInfo {
    @Getter
    private long id;
    @Getter
    @Setter
    private String name;
    @Getter
    @Setter
    private String originType;
    @Getter
    @Setter
    private String comment;

    private long createNode(BatchInserter inserter) {
        Map<String, Object> map = new HashMap<>();
        map.put(CExtractor.NAME, name);
        map.put(CExtractor.ORIGINTYPE, originType);
        map.put(CExtractor.COMMENT, comment);
        id = inserter.createNode(map, CExtractor.c_alias);
        return id;
    }
}
