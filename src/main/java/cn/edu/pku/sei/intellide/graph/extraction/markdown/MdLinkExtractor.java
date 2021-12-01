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
    public static final Label MARKDOWN = Label.label("Markdown");
    public static final Label MARKDOWNCATALOG = Label.label("MarkdownCatalog");
    public static final RelationshipType LINK_TO = RelationshipType.withName("linkto");
    public static final String TITLE = "title";
    public static final String LEVEL = "level";
    public static final String LINKDOCS = "linkdocs";

    @Override
    public void extraction() {
        // 访问数据库获取结点对应属性以进行匹配
        Map<Long, List<String>> Catalog = getCatalogDocs();             // 目录文档及文件索引
        Map<Long, String> CatalogTitle = getNodeTitle(MARKDOWNCATALOG); // 目录文档标题
        Map<Long, String> Md = getNodeTitle(MARKDOWN);                  // 普通文档标题
        // 创建关联
        createRelationships(addRelationships(Catalog, CatalogTitle));
        createRelationships(addRelationships(Catalog, Md));
    }

    public void createRelationships(Map<Long, List<Long>> nodePairs) {
        GraphDatabaseService db = this.getDb();
        try (Transaction tx = db.beginTx()) {
            for(Map.Entry<Long, List<Long>> entry: nodePairs.entrySet()) {
                long id1 = entry.getKey();
                List<Long> ids = entry.getValue();
                Node node1 = db.getNodeById(id1);
                for(long id2: ids) {
                    Node node2 = db.getNodeById(id2);
                    node1.createRelationshipTo(node2, LINK_TO);
                }
            }
            tx.success();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public Map<Long, List<String>> getCatalogDocs() {
        Map<Long, List<String>> docsMap = new LinkedHashMap<>();
        GraphDatabaseService db = this.getDb();

        try (Transaction tx = db.beginTx()) {
            ResourceIterator<Node> nodes = db.findNodes(MARKDOWNCATALOG);
            while (nodes.hasNext()) {
                Node node = nodes.next();
                long id = node.getId();
                String linkDocs = (String) node.getProperty(LINKDOCS);
                linkDocs = linkDocs.substring(1, linkDocs.length() - 1);
                String[] tmp = linkDocs.split(", ");
                List<String> docs = Arrays.asList(tmp);
                docsMap.put(id, docs);
            }
            tx.success();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return docsMap;
    }

    public Map<Long, String> getNodeTitle(Label label) {
        Map<Long, String> titleMap = new LinkedHashMap<Long, String>();
        GraphDatabaseService db = this.getDb();

        try (Transaction tx = db.beginTx()) {
            ResourceIterator<Node> nodes = null;
            if(label.equals(MARKDOWN)) nodes = db.findNodes(MARKDOWN);
            else if(label.equals(MARKDOWNCATALOG)) nodes = db.findNodes(MARKDOWNCATALOG);
            while (nodes.hasNext()) {
                Node node = nodes.next();
                long id = node.getId();
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
     * 将标题属性对比，匹配则记录表示关联
     */
    public Map<Long, List<Long>> addRelationships(Map<Long, List<String>> catalog, Map<Long, String> md) {
        Map<Long, List<Long>> nodePairs = new HashMap<>();
        for(Map.Entry<Long, List<String>> entry: catalog.entrySet()) {
            Long id = entry.getKey();
            if(!nodePairs.containsKey(id))
                nodePairs.put(id, new ArrayList<>());
            List<String> docs = entry.getValue();
            for(String doc: docs) {
                for (Map.Entry<Long, String> entry2 : md.entrySet()) {
                    Long id2 = entry2.getKey();
                    String title = entry2.getValue();
                    if(doc.equals(title) && !nodePairs.get(id).contains(id2)) {
                        nodePairs.get(id).add(id2); break;
                    }
                }
            }
        }
        return nodePairs;
    }
}
