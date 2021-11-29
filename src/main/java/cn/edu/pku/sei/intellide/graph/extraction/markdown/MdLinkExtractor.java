package cn.edu.pku.sei.intellide.graph.extraction.markdown;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.RelationshipType;

public class MdLinkExtractor extends KnowledgeExtractor {
    public static final Label MARKDOWN = Label.label("MarkdownSection");
    public static final RelationshipType LINK_TO = RelationshipType.withName("linkto");

    @Override
    public void extraction() {

    }
}
