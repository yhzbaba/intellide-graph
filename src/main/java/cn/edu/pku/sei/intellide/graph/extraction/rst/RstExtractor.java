package cn.edu.pku.sei.intellide.graph.extraction.rst;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.markdown.MdExtractor;
import org.apache.commons.io.FileUtils;
import org.json.JSONObject;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class RstExtractor extends KnowledgeExtractor {

    public static final Label USERMODE = Label.label("UserMode");
    public static final Label SYSTEMMODE = Label.label("SystemMode");
    public static final Label DEVELOPERGUIDE = Label.label("DeveloperGuide");
    public static final RelationshipType SUB_DOC_ELEMENT = RelationshipType.withName("subDocElement");

    // node attributes
    public static final String TITLE = "title";
    public static final String CONTENT = "content";
    public static final String LEVEL = "level";
    public static final String CODEBLOCK = "codeBlock";
    public static final String SERIAL = "serial";

    // global data structure
    private Label label;
    private int curLevel, curLine, lineNum;
    private int sectionLevel = 6;
    ArrayList<RstSection> Entities;

    public static void main(String[] args) {
        RstExtractor test = new RstExtractor();
        test.setDataDir("E:\\changwenhui\\SoftwareReuse\\knowledgeGraph\\openHarmony\\parseData\\qemu-docs");
        test.extraction();
    }

    @Override
    public boolean isBatchInsert() {
        return true;
    }

    @Override
    public void extraction() {
        for (File file : FileUtils.listFiles(new File(this.getDataDir()), new String[] { "rst" }, true)) {

            String fileName = file.getAbsolutePath().substring(new File(this.getDataDir()).getAbsolutePath().length())
                    .replaceAll("^[/\\\\]+", "");
//            System.out.println(fileName + " " + file.getAbsolutePath());
//            if(!fileName.contains("aspeed.rst")) continue;

            // 判断文档实体类型
            setLabel(fileName);
            if(label == null) continue;

            Map<String, RstExtractor.RstSection> map = new HashMap<>();
            initDataStructure();

            try {
                List<String> lines = Files.readAllLines(Paths.get(file.getAbsolutePath()), StandardCharsets.UTF_8);
                lineNum = lines.size() - 1;
                Entities.get(0).title = fileName;
                Entities.get(0).level = 0;

                parseDoc(lines, map);
                for(int i = 1;i <= sectionLevel;i++) {
                    if(Entities.get(i) != null & !map.containsKey(Entities.get(i).title)) {
                        map.put(Entities.get(i).title, Entities.get(i));
                        Entities.get(i-1).children.add(Entities.get(i));
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
            }

            for (RstExtractor.RstSection rstSection: map.values()) {
                if(rstSection.level != -1) rstSection.toNeo4j(this.getInserter());
            }

        }
    }

    public void initDataStructure() {
        curLevel = 0; curLine = -1;
        Entities = new ArrayList<RstSection>(sectionLevel + 1);
        for(int i = 0; i <= sectionLevel; i++) {
            Entities.add(i, new RstSection());
        }
    }

    public void setLabel(String fileName) {
        if(fileName.contains("about\\") || fileName.contains("tools\\") || fileName.contains("devel\\")) {
            label = DEVELOPERGUIDE;
        }
        else if(fileName.contains("user\\")) {
            label = USERMODE;
        }
        else if(fileName.contains("specs\\") || fileName.contains("system\\") || fileName.contains("interop\\")) {
            label = SYSTEMMODE;
        }
        else {
            label = null;
        }
    }

    public void parseDoc(List<String> lines, Map<String, RstSection> map) {
        while(curLine < lineNum) {
            String line = lines.get(++curLine);
            if(line.equals("")) continue;
            typeDispatch(line, lines, map);
        }
    }

    public void typeDispatch(String line, List<String> lines, Map<String, RstSection> map) {
        boolean flag = false;
        // 章节标题的层级
        if(line.contains("===") && (curLine + 2 <lineNum && lines.get(curLine+2).contains("==="))) {
            curLevel = 1;
            Entities.get(curLevel).title = lines.get(++curLine);
            curLine += 2; return;
        }
        else if(curLine + 1 < lineNum && lines.get(curLine+1).contains("===")) {
            curLevel = 2; flag = true;
        }
        else if(curLine + 1 < lineNum && lines.get(curLine+1).contains("---")) {
            curLevel = 3; flag = true;
        }
        else if(curLine + 1 < lineNum && (lines.get(curLine+1).contains("'''") || lines.get(curLine+1).contains("~~~"))) {
            curLevel = 4; flag = true;
        }
        else if(curLine + 1 < lineNum && lines.get(curLine+1).contains("^^^")) {
            curLevel = 5; flag = true;
        }
        else if(curLine + 1 < lineNum && lines.get(curLine+1).contains("\"\"\"")) {
            curLevel = 6; flag = true;
        }
        if(flag) {
            for(int i = 1;i <= sectionLevel;i++) {
                if(Entities.get(i) != null & !map.containsKey(Entities.get(i).title)) {
                    map.put(Entities.get(i).title, Entities.get(i));
                    Entities.get(i-1).children.add(Entities.get(i));
                }
            }
            Entities.set(curLevel, new RstSection());
            Entities.get(curLevel).title = line;
            Entities.get(curLevel).level = curLevel;
            curLine += 2;
        }
        else {
            // TODO: 非标题文本，需要进一步细分类型
            Entities.get(curLevel).content += line;
        }
    }

    class RstSection {
        long node = -1;
        String title = "";
        int level = -1;
        String content = "";
        ArrayList<RstExtractor.RstSection> children = new ArrayList<>();

        public long toNeo4j(BatchInserter inserter) {
            if(node != -1) return node;
            Map<String, Object> map = new HashMap<>();
            map.put(RstExtractor.TITLE, title);
            map.put(RstExtractor.LEVEL, level);
            map.put(RstExtractor.CONTENT, content);
            node = inserter.createNode(map, new Label[] { label });
            for(int i = 0; i < children.size(); i++) {
                RstSection child = children.get(i);
                if(child.level == -1) continue;
                long childID = child.toNeo4j(inserter);
                Map<String, Object> rMap = new HashMap<>();
                rMap.put(RstExtractor.SERIAL, i);
                inserter.createRelationship(node, childID, RstExtractor.SUB_DOC_ELEMENT, rMap);
            }
            return node;
        }
    }
}
