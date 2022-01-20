package cn.edu.pku.sei.intellide.graph.extraction.code_mention;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.git.GitExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.markdown.MdExtractor;
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
    public static final RelationshipType MODIFY = RelationshipType.withName("modify");
    public static final RelationshipType DELETE = RelationshipType.withName("delete");

    @Override
    public void extraction() {
        try {
            this.detectCodeMentionInMd();
        } catch (IOException | ParseException e) {
            e.printStackTrace();
        }
        this.detectCodeMentionInDiff();
    }

    /**
     * 代码与 markdown 文档的关联
     * TODO: C语言代码实体的属性不完善，需要路径信息(目前只是name属性)
     * @throws IOException
     * @throws ParseException
     */
    private void detectCodeMentionInMd() throws IOException, ParseException {
        final String CONTENT_FIELD = "content";
        final String CODEBLOCK_FIELD = "codeblock";
        final String ID_FIELD = "id";
        Analyzer analyzer = new StandardAnalyzer();
        Directory directory = new RAMDirectory();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        IndexWriter iwriter = new IndexWriter(directory, config);
        ResourceIterator<Node> docxNodes = null;
        // 处理两类 markdown 类型的实体，相关属性包括：content, codeblock
        try (Transaction tx = this.getDb().beginTx()) {
            docxNodes = this.getDb().findNodes(MdExtractor.MARKDOWNSECTION);
            while (docxNodes.hasNext()) {
                Node docxNode = docxNodes.next();
                String content = (String) docxNode.getProperty(MdExtractor.CONTENT);
                String codeblock = (String) docxNode.getProperty(MdExtractor.CODEBLOCK);
                content = content.replaceAll("\\W+", " ").toLowerCase();
                codeblock = codeblock.replaceAll("\\W+", " ").toLowerCase();
                Document document = new Document();
                document.add(new StringField(ID_FIELD, "" + docxNode.getId(), Field.Store.YES));
                document.add(new TextField(CONTENT_FIELD, content, Field.Store.YES));
                document.add(new TextField(CODEBLOCK_FIELD, codeblock, Field.Store.YES));
                iwriter.addDocument(document);
            }

            docxNodes = this.getDb().findNodes(MdExtractor.MARKDOWN);
            while (docxNodes.hasNext()) {
                Node docxNode = docxNodes.next();
                String content = (String) docxNode.getProperty(MdExtractor.CONTENT);
                String codeblock = (String) docxNode.getProperty(MdExtractor.CODEBLOCK);
                content = content.replaceAll("\\W+", " ").toLowerCase();
                codeblock = codeblock.replaceAll("\\W+", " ").toLowerCase();
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
                String q = name.toLowerCase();
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
                String q = name.toLowerCase();
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
                String q = name.toLowerCase();
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
                String q = name.toLowerCase();
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

        /*
        try (Transaction tx = this.getDb().beginTx()) {
            ResourceIterator<Node> fileNodes = this.getDb().findNodes(JavaExtractor.CLASS);
            while (fileNodes.hasNext()) {
                Node fileNode = fileNodes.next();
                String name = (String) fileNode.getProperty(JavaExtractor.NAME);
                String q = name.toLowerCase();
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
         */
    }

    /**
     * 代码文件实体与commit的关联，ADD, DELETE 和 MODIFY 三种关系(会存在多种关系)
     */
    private void detectCodeMentionInDiff() {
        Map<String, Node> codeFileMap = new HashMap<>();
        Pattern pattern = Pattern.compile("(ADD|MODIFY|DELETE)\\s+(\\S+)\\s+to\\s+(\\S+)");
        try (Transaction tx = this.getDb().beginTx()) {
            ResourceIterator<Node> fileNodes = this.getDb().findNodes(CExtractor.c_code_file);
            while (fileNodes.hasNext()) {
                Node fileNode = fileNodes.next();
                // TODO: 该属性尚未添加到code_file当中，需要与commit中的 diff_summary 中的文件路径进行正则匹配
                String fullName = (String) fileNode.getProperty(CExtractor.FULLNAME);
                String sig;
                if(fullName.contains(".c")) sig = fullName.replace('.', '/') + ".c";
                else sig = fullName.replace('.', '/') + ".h";
                codeFileMap.put(sig, fileNode);
            }
            ResourceIterator<Node> commits = this.getDb().findNodes(GitExtractor.COMMIT);
            while (commits.hasNext()) {
                Node commit = commits.next();
                String diffSummary = (String) commit.getProperty(GitExtractor.DIFF_SUMMARY);
                Matcher matcher = pattern.matcher(diffSummary);
                while (matcher.find()) {
                    String relStr = matcher.group(1);
                    String srcPath = matcher.group(2);
                    String dstPath = matcher.group(3);
                    RelationshipType relType = relStr.equals("ADD") ? ADD : relStr.equals("MODIFY") ? MODIFY : DELETE;
                    for (String sig : codeFileMap.keySet())
                        if (srcPath.contains(sig) || dstPath.contains(sig))
                            commit.createRelationshipTo(codeFileMap.get(sig), relType);
                }
            }
            tx.success();
        }
    }
}
