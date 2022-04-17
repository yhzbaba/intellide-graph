package cn.edu.pku.sei.intellide.graph.extraction.c_code.infos;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class CImplicitInvokePoint {
    @Getter
    private long id = -1;

    private String name;

    private String layer;

    private Integer seqNum;

    private List<CFunctionInfo> probInvokeFunctions = new ArrayList<>();

    public long createNode(BatchInserter inserter) {
        if (id != -1) {
            return id;
        }
        Map<String, Object> map = new HashMap<>();
        map.put(CExtractor.NAME, name);
        map.put(CExtractor.LAYER, layer);
        map.put(CExtractor.SEQNUM, seqNum);
        this.id = inserter.createNode(map, CExtractor.c_imp_invoke);
        return this.id;
    }
}
