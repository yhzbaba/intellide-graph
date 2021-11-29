package cn.edu.pku.sei.intellide.graph.extraction.markdown;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import com.sun.org.apache.bcel.internal.generic.INSTANCEOF;
import org.apache.pdfbox.pdfviewer.MapEntry;
import org.neo4j.graphdb.*;

import java.util.*;

/**
 * 依据标题匹配，将目录类型文档与普通文档关联起来
 */
public class MdLinkExtractor extends KnowledgeExtractor {
    public static final Label MARKDOWN = Label.label("MarkdownSection");
    public static final Label MARKDOWNCATA = Label.label("MarkdownCatalog");
    public static final RelationshipType LINK_TO = RelationshipType.withName("linkto");
    public static final String TITLE = "title";
    public static final String LEVEL = "level";
    public static final String LINKDOCS = "linkdocs";

    @Override
    public void extraction() {
        Map<Long, List<String>> Catalog = getNodeDocs();
        Map<Long, String> Md = getNodeTitle();
        createRelationships(addRelationships(Catalog, Md));
    }

    public void createRelationships(Map<Long, Long> nodePairs) {
        GraphDatabaseService db = this.getDb();
        try (Transaction tx = db.beginTx()) {
            for(Map.Entry<Long, Long> entry: nodePairs.entrySet()) {
                long id1 = entry.getKey();
                long id2 = entry.getValue();
                Node node1 = db.getNodeById(id1);
                Node node2 = db.getNodeById(id2);
                node1.createRelationshipTo(node2, LINK_TO);
            }
            tx.success();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public Map<Long, List<String>> getNodeDocs() {
        Map<Long, List<String>> docsMap = new LinkedHashMap<>();
        GraphDatabaseService db = this.getDb();

        try (Transaction tx = db.beginTx()) {
            ResourceIterator<Node> nodes = db.findNodes(MARKDOWNCATA);
            while (nodes.hasNext()) {
                Node node = nodes.next();
                long id = node.getId();
                String linkDocs = (String) node.getProperty(LINKDOCS);
                linkDocs = linkDocs.substring(1, linkDocs.length() - 1);
                String[] tmp = linkDocs.split(",");
                List<String> docs = Arrays.asList(tmp);
                docsMap.put(id, docs);
            }
            tx.success();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return docsMap;
    }

    public Map<Long, String> getNodeTitle() {
        Map<Long, String> titleMap = new LinkedHashMap<Long, String>();
        GraphDatabaseService db = this.getDb();

        try (Transaction tx = db.beginTx()) {
            ResourceIterator<Node> nodes = db.findNodes(MARKDOWN);
            while (nodes.hasNext()) {
                Node node = nodes.next();
                long id = node.getId();
                // 只需要考虑文档标题
                int level = (int) node.getProperty(LEVEL);
                if(level != 1) continue;
                String title = (String) node.getProperty(TITLE);
                if (!title.equals("")) {
                    titleMap.put(id, title);
                }
            }
            tx.success();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return titleMap;
    }

    /**
     * 将目录索引与 md 文件的标题属性对比，匹配则记录表示关联
     */
    public Map<Long, Long> addRelationships(Map<Long, List<String>> catalog, Map<Long, String> md) {
        Map<Long, Long> nodePairs = new HashMap<>();
        for(Map.Entry<Long, List<String>> entry: catalog.entrySet()) {
            Long id = entry.getKey();
            List<String> docs = entry.getValue();
            for(String doc: docs) {
                Map<Long, Long> nodePair = new HashMap<>();
                for (Map.Entry<Long, String> entry2 : md.entrySet()) {
                    Long id2 = entry2.getKey();
                    String title = entry2.getValue();
                    if(doc.equals(title)) {
                        nodePairs.put(id, id2); break;
                    }
                }
            }
        }
        return nodePairs;
    }
}
