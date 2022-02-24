package cn.edu.pku.sei.intellide.graph.extraction.code_mention;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.git.GitExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.markdown.MdExtractor;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.neo4j.graphdb.*;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 建立C语言代码实体与文档、commit之间的关联
 */
public class CCodeMentionExtractor extends KnowledgeExtractor {
    public static final RelationshipType CODE_MENTION = RelationshipType.withName("codeMention");
    public static final RelationshipType ADD = RelationshipType.withName("add");
    public static final RelationshipType UPDATE = RelationshipType.withName("update");
    public static final RelationshipType DELETE = RelationshipType.withName("delete");

    Map<Long, List<Long>> edges = new HashMap<>();
    Map<Long, Set<String>> contentMap = new HashMap<>();
    Map<Long, String> codeblockMap = new HashMap<>();
    Map<Long, String> tableMap = new HashMap<>();

    public static void main(String[] args) {
        String content = " OpenHarmony ketructur **edge** ree is a  **/Vno_de**  structure, ";
        Pattern r = Pattern.compile("\\s\\*\\*[/\\w]*\\*\\*\\s");
        Matcher m = r.matcher(content);
        while(m.find()) {
            String s = m.group();
            s = s.replaceAll("\\*", "");
            s = s.replaceAll("/", "");
            System.out.println(s);
        }
    }

    @Override
    public void extraction() {
//        try {
//            this.detectCodeMentionInMd();
//        } catch (IOException | ParseException e) {
//            e.printStackTrace();
//        }
        /* code_mention in markdown documents */
        detectCodeMentionInDoc();
        /* code_mention in Diff */
//        this.detectCodeMentionInDiff();
    }

    private void detectCodeMentionInDoc() {
        GraphDatabaseService db = this.getDb();
        try (Transaction tx = db.beginTx()) {
            ResourceIterator<Node> docNodes = db.findNodes(MdExtractor.MARKDOWNSECTION);
            while(docNodes.hasNext()) {
                Set<String> set = new HashSet<>();
                Node docNode = docNodes.next();
                /* node id 作为 key 值 */
                long id = docNode.getId();
                /* 识别 content 中的代码元素，加入集合 */
                String content = (String) docNode.getProperty(MdExtractor.CONTENT);
                Pattern r = Pattern.compile("\\s\\*\\*[/\\w]*\\*\\*\\s");
                Matcher m = r.matcher(content);
                while(m.find()) {
                    String s = m.group();
                    s = s.replaceAll("\\*", "");
                    s = s.replaceAll("/", "");
                    set.add(s);
                }
                contentMap.put(id, set);

                /* 代码块 */
//                JSONObject codeBlock = (JSONObject) docNode.getProperty("codeblock");
                String codeBlock = (String) docNode.getProperty(MdExtractor.CODEBLOCK);
                JSONObject json = JSONObject.parseObject(codeBlock);
                String tmp = "";
                for(Map.Entry entry : json.entrySet()) {
                    tmp += (String) entry.getValue();
                }
//                System.out.println(tmp);
                if(!tmp.equals("")) codeblockMap.put(id, tmp);

                /* 表格 */
                String table = (String) docNode.getProperty(MdExtractor.TABLE);
                json = JSONObject.parseObject(table);
                tmp = "";
                for(Map.Entry entry: json.entrySet()) {
                    JSONArray list = new JSONArray(Collections.singletonList(entry.getValue()));
                    for(int i = 0;i < list.size(); i++) {
                        tmp += list.getString(i);
                    }
                }
//                System.out.println(tmp);
                if(!tmp.equals("")) tableMap.put(id, tmp);

            }
            tx.success();
        }
        /* 根据 Map 中的信息，建立 code_mention 的关系 */
        setRelationships(CExtractor.c_code_file);
        setRelationships(CExtractor.c_function);
        setRelationships(CExtractor.c_variable);
        setRelationships(CExtractor.c_struct);
        setRelationships(CExtractor.c_field);
    }

    private void setRelationships(Label label) {
        GraphDatabaseService db = this.getDb();
        try (Transaction tx = db.beginTx()) {
            ResourceIterator<Node> Nodes = db.findNodes(label);
            while (Nodes.hasNext()) {
                Node fileNode = Nodes.next();
                long nodeId = fileNode.getId();
                String name = "";
                if (label == CExtractor.c_code_file) name = (String) fileNode.getProperty(CExtractor.TAILFILENAME);
                else name = (String) fileNode.getProperty(CExtractor.NAME);

                if(nameFilter(name)) continue;

                for (Map.Entry entry : contentMap.entrySet()) {
                    long id = (long) entry.getKey();
                    Set<String> set = (Set<String>) entry.getValue();
                    if (set.contains(name)) {
                        if (!edges.containsKey(nodeId) || !edges.get(nodeId).contains(id)) {
                            fileNode.createRelationshipTo(db.getNodeById(id), CODE_MENTION);
                            if (!edges.containsKey(nodeId)) {
                                List<Long> tmp = new ArrayList<>();
                                tmp.add(id);
                                edges.put(nodeId, tmp);
                            } else {
                                edges.get(nodeId).add(id);
                            }
                        }
                    }
                }
                for (Map.Entry entry : codeblockMap.entrySet()) {
                    long id = (long) entry.getKey();
                    String value = (String) entry.getValue();
                    if (value.contains(name)) {
                        if (!edges.containsKey(nodeId) || !edges.get(nodeId).contains(id)) {
                            fileNode.createRelationshipTo(db.getNodeById(id), CODE_MENTION);
                            if (!edges.containsKey(nodeId)) {
                                List<Long> tmp = new ArrayList<>();
                                tmp.add(id);
                                edges.put(nodeId, tmp);
                            } else {
                                edges.get(nodeId).add(id);
                            }
                        }
                    }
                }
                for (Map.Entry entry : tableMap.entrySet()) {
                    long id = (long) entry.getKey();
                    String value = (String) entry.getValue();
                    if (value.contains(name)) {
                        if (!edges.containsKey(nodeId) || !edges.get(nodeId).contains(id)) {
                            fileNode.createRelationshipTo(db.getNodeById(id), CODE_MENTION);
                            if (!edges.containsKey(nodeId)) {
                                List<Long> tmp = new ArrayList<>();
                                tmp.add(id);
                                edges.put(nodeId, tmp);
                            } else {
                                edges.get(nodeId).add(id);
                            }
                        }
                    }
                }
            }
            tx.success();
        }
    }

    /**
     * 剔除一般性的命名实体，避免引入噪声关系
     */
    private boolean nameFilter(String name) {
        if(name.length() <= 3) return true;
        List<String> fileterList = new ArrayList<>();
        String[] list = { "main", "type", "name", "value", "info", "task", "time", "part",
                        "size", "flag", "mode", "lock", "addr", "path", "data", "node",
                        "call", "used", "start", "param", "func", "format",
                        "INT32", "UINT32" };
        fileterList.addAll(Arrays.asList(list));
        if(fileterList.contains(name)) {
            return true;
        }
        return false;
    }

    /**
     * 代码与 markdown 文档的关联
     */
    private void detectCodeMentionInMd() throws IOException, ParseException {
        final String CONTENT_FIELD = "content";
        final String CODEBLOCK_FIELD = "codeblock";
        final String TABLE_FIELD = "table";
        final String ID_FIELD = "id";
        Analyzer analyzer = new StandardAnalyzer();
        Directory directory = new RAMDirectory();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        IndexWriter iwriter = new IndexWriter(directory, config);
        ResourceIterator<Node> docxNodes = null;
        // 处理两类 markdown 类型的实体，相关属性包括：content, codeblock
        try (Transaction tx = this.getDb().beginTx()) {
            /* 为文档实体建立索引 */
            docxNodes = this.getDb().findNodes(MdExtractor.MARKDOWNSECTION);
            while (docxNodes.hasNext()) {
                Node docxNode = docxNodes.next();
                String content = (String) docxNode.getProperty(MdExtractor.CONTENT);
                String codeblock = (String) docxNode.getProperty(MdExtractor.CODEBLOCK);
                String table = (String) docxNode.getProperty(MdExtractor.TABLE);
                content = content.replaceAll("\\W+", " ");
                codeblock = codeblock.replaceAll("\\W+", " ");
                table = table.replaceAll("\\W+", " ");
                Document document = new Document();
                document.add(new StringField(ID_FIELD, "" + docxNode.getId(), Field.Store.YES));
                document.add(new TextField(CONTENT_FIELD, content, Field.Store.YES));
                document.add(new TextField(CODEBLOCK_FIELD, codeblock, Field.Store.YES));
                document.add(new TextField(TABLE_FIELD, table, Field.Store.YES));
                iwriter.addDocument(document);
            }

            docxNodes = this.getDb().findNodes(MdExtractor.MARKDOWN);
            while (docxNodes.hasNext()) {
                Node docxNode = docxNodes.next();
                String content = (String) docxNode.getProperty(MdExtractor.CONTENT);
                String codeblock = (String) docxNode.getProperty(MdExtractor.CODEBLOCK);
                content = content.replaceAll("\\W+", " ");
                codeblock = codeblock.replaceAll("\\W+", " ");
                Document document = new Document();
                document.add(new StringField(ID_FIELD, "" + docxNode.getId(), Field.Store.YES));
                document.add(new TextField(CONTENT_FIELD, content, Field.Store.YES));
                document.add(new TextField(CODEBLOCK_FIELD, codeblock, Field.Store.YES));
                iwriter.addDocument(document);
            }
            tx.success();
        }
        iwriter.close();

        // 获取C语言代码实体节点的信息，进行索引的搜索
        DirectoryReader ireader = DirectoryReader.open(directory);
        IndexSearcher isearcher = new IndexSearcher(ireader);
        QueryParser parser = new QueryParser(CONTENT_FIELD, analyzer);
        // c_code_file: fileName
        try (Transaction tx = this.getDb().beginTx()) {
            ResourceIterator<Node> fileNodes = this.getDb().findNodes(CExtractor.c_code_file);
            while (fileNodes.hasNext()) {
                Node fileNode = fileNodes.next();
                String name = (String) fileNode.getProperty(CExtractor.FILENAME);
                String q = name;
                Query query = parser.parse(q);
                ScoreDoc[] hits = isearcher.search(query, 10000).scoreDocs;
                if (hits.length > 0 && hits.length < 20) {
                    for (ScoreDoc hit : hits) {
                        Node docxNode = this.getDb().getNodeById(Long.parseLong(ireader.document(hit.doc).get(ID_FIELD)));
                        fileNode.createRelationshipTo(docxNode, CODE_MENTION);
                    }
                }
            }
            tx.success();
        }
        // c_function
        try (Transaction tx = this.getDb().beginTx()) {
            ResourceIterator<Node> funcNodes = this.getDb().findNodes(CExtractor.c_function);
            while (funcNodes.hasNext()) {
                Node funcNode = funcNodes.next();
                String name = (String) funcNode.getProperty(CExtractor.NAME);
                String q = name;
                Query query = parser.parse(q);
                ScoreDoc[] hits = isearcher.search(query, 10000).scoreDocs;
                if (hits.length > 0 && hits.length < 20) {
                    for (ScoreDoc hit : hits) {
                        Node docxNode = this.getDb().getNodeById(Long.parseLong(ireader.document(hit.doc).get(ID_FIELD)));
                        funcNode.createRelationshipTo(docxNode, CODE_MENTION);
                    }
                }
            }
            tx.success();
        }
        // c_datastructure
        try (Transaction tx = this.getDb().beginTx()) {
            ResourceIterator<Node> dsNodes = this.getDb().findNodes(CExtractor.c_struct);
            while (dsNodes.hasNext()) {
                Node dsNode = dsNodes.next();
                String name = (String) dsNode.getProperty(CExtractor.NAME);
                String q = name;
                Query query = parser.parse(q);
                ScoreDoc[] hits = isearcher.search(query, 10000).scoreDocs;
                if (hits.length > 0 && hits.length < 20) {
                    for (ScoreDoc hit : hits) {
                        Node docxNode = this.getDb().getNodeById(Long.parseLong(ireader.document(hit.doc).get(ID_FIELD)));
                        dsNode.createRelationshipTo(docxNode, CODE_MENTION);
                    }
                }
            }
            tx.success();
        }
        // c_variable
        try (Transaction tx = this.getDb().beginTx()) {
            ResourceIterator<Node> varNodes = this.getDb().findNodes(CExtractor.c_variable);
            while (varNodes.hasNext()) {
                Node varNode = varNodes.next();
                String name = (String) varNode.getProperty(CExtractor.NAME);
                String q = name;
                Query query = parser.parse(q);
                ScoreDoc[] hits = isearcher.search(query, 10000).scoreDocs;
                if (hits.length > 0 && hits.length < 20) {
                    for (ScoreDoc hit : hits) {
                        Node docxNode = this.getDb().getNodeById(Long.parseLong(ireader.document(hit.doc).get(ID_FIELD)));
                        varNode.createRelationshipTo(docxNode, CODE_MENTION);
                    }
                }
            }
            tx.success();
        }
    }

    /**
     * 代码文件实体与commit的关联，ADD, DELETE 和 UPDATE 三种关系(会存在多种关系)
     */
    private void detectCodeMentionInDiff() {
        Map<String, Node> codeFileMap = new HashMap<>();
        Pattern pattern = Pattern.compile("(ADD|UPDATE|DELETE)\\s+(\\S+)\\s+to\\s+(\\S+)");
        try (Transaction tx = this.getDb().beginTx()) {
            ResourceIterator<Node> fileNodes = this.getDb().findNodes(CExtractor.c_code_file);
            while (fileNodes.hasNext()) {
                Node fileNode = fileNodes.next();
                String fullName = (String) fileNode.getProperty(CExtractor.FILEFULLNAME);
                if(fullName.contains(".c") || fullName.contains(".h")) {
                    if(fullName.contains("\\")) {
                        fullName = fullName.replaceAll("\\\\", "/");
                    }
                    codeFileMap.put(fullName, fileNode);
                }
            }
            ResourceIterator<Node> commits = this.getDb().findNodes(GitExtractor.COMMIT);
            while (commits.hasNext()) {
                Node commit = commits.next();
                List<String> diffSummary = Arrays.asList(((String) commit.getProperty(GitExtractor.DIFF_SUMMARY)).split("\n"));
                for(String diff : diffSummary) {
                    Matcher matcher = pattern.matcher(diff);
                    while (matcher.find()) {
                        String relStr = matcher.group(1);
                        String srcPath = matcher.group(2);
                        String dstPath = matcher.group(3);
                        RelationshipType relType = relStr.equals("ADD") ? ADD : relStr.equals("UPDATE") ? UPDATE : DELETE;
                        for (String sig : codeFileMap.keySet()) {
                            if (sig.contains(srcPath) || sig.contains(dstPath)) {
                                commit.createRelationshipTo(codeFileMap.get(sig), relType);
                            }
                        }
                    }
                }
            }
            tx.success();
        }
    }
}
