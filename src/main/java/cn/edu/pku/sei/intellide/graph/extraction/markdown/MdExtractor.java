package cn.edu.pku.sei.intellide.graph.extraction.markdown;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.neo4j.cypher.internal.frontend.v2_3.ast.functions.E;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;


public class MdExtractor extends KnowledgeExtractor {

    public static final Label MARKDOWN = Label.label("Markdown");
    public static final Label MARKDOWNSECTION = Label.label("MarkdownSection");
    public static final Label MARKDOWNCATALOG = Label.label("MarkdownCatalog");
    public static final RelationshipType SUB_MD_ELEMENT = RelationshipType.withName("subMdElement");
    // node attributes
    public static final String TITLE = "title";
    public static final String ISCATALOG = "iscatalog";
    public static final String ISSECTION = "issection";
    public static final String CONTENT = "content";
    public static final String CODEBLOCK = "codeblock";
    public static final String TABLE = "table";
    public static final String LEVEL = "level";
    public static final String SERIAL = "serial";
    public static final String LINKDOCS = "linkdocs";

    private int curLevel, curLine;
    private int lineNum;                    // number of lines of document
    ArrayList<MdSection> Entities;


    public static void main(String[] args) {
        MdExtractor test = new MdExtractor();
        test.setDataDir("E:\\changwenhui\\SoftwareReuse\\knowledgeGraph\\openHarmony\\testDocs");
        test.extraction();
    }

    @Override
    public boolean isBatchInsert() {
        return true;
    }

    @Override
    public void extraction() {

        for (File file : FileUtils.listFiles(new File(this.getDataDir()), new String[] { "md" }, true)) {
            Init();
            Map<String, MdSection> map = new HashMap<>();
            String fileName = file.getAbsolutePath().substring(new File(this.getDataDir()).getAbsolutePath().length())
                    .replaceAll("^[/\\\\]+", "");

//            fileName = fileName.substring(0, fileName.lastIndexOf("."));
//            if(!fileName.contains("process-process.md")) continue;
//            System.out.println(fileName);

            try {
                List<String> lines = Files.readAllLines(Paths.get(file.getAbsolutePath()), StandardCharsets.UTF_8);
                lineNum = lines.size() - 1;
                if(isCatalogDoc(file)) {
                    parseCatalog(lines);
                    if(Entities.get(1) != null & !map.containsKey(Entities.get(1).title))
                        map.put(Entities.get(1).title, Entities.get(1));
                }
                else {
                    parseMd(lines, map);
                    for(int i = 1;i <= 3;i++) {
                        if(Entities.get(i) != null & !map.containsKey(Entities.get(i).title)) {
                            map.put(Entities.get(i).title, Entities.get(i));
                            if(i > 1) Entities.get(i-1).children.add(Entities.get(i));
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            for (MdSection mdSection: map.values()) {
                if(mdSection.level != -1) mdSection.toNeo4j(this.getInserter());
            }
        }
    }

    public void Init() {
        curLevel = 1; curLine = -1;
        Entities = new ArrayList<MdSection>(4);
        for(int i = 0;i <= 3;i++) {
            Entities.add(i, new MdSection());
        }
    }

    /**
     * 判断文档是否是目录索引
     */
    public boolean isCatalogDoc(File file) {
        Long fileLengthLong = file.length();
        byte[] fileContent = new byte[fileLengthLong.intValue()];
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(file);
            inputStream.read(fileContent);
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        String content = new String(fileContent);
        if(content.contains(".md)**")) return true;
        return false;
    }

    public void parseCatalog(List<String> lines) throws IOException {
        setTitle(lines);
        Entities.get(1).isCatalog = true;
        Entities.get(1).isSection = false;
        ++curLine;
        while(curLine < lineNum) {
            String line = lines.get(++curLine);
            if((line.equals("") || line.contains("**图") || line.contains("![]"))) continue;
            if(line.contains(".md)**")) {
                // 跳转链接
                String linkDoc = line.substring(line.indexOf("[") + 1, line.indexOf("]"));
                Entities.get(1).linkDocs.add(linkDoc);
            }
            else {
                // 普通文本
                Entities.get(1).content += (line + "\n");
            }
        }
    }

    public void parseMd(List<String> lines, Map<String, MdSection> map) throws IOException {
        setTitle(lines);
        while (curLine < lineNum) {
            String line = lines.get(++curLine);
            // 跳过不考虑处理的文本
            if(TextFilter(line)) continue;
            TypeDispatch(line, lines, map);
        }
    }

    public boolean TextFilter(String line) {
        return (line.equals("") || line.contains("-   ") || line.contains("**图") || line.contains("![]"));
    }

    public void codeBelow(List<String> lines) {
        if((curLine + 1 < lineNum && lines.get(curLine + 1).contains("```")) || (curLine + 2 < lineNum && lines.get(curLine + 2).contains("```"))) {
            String tmp = Entities.get(curLevel).content;
            tmp = tmp.substring(0, tmp.length() - 1);
            tmp += "见 codeBlock\n";
            Entities.get(curLevel).content = tmp;
        }
    }

    /**
     * 根据文本类型转到相应的处理函数
     */
    public boolean TypeDispatch(String line, List<String> lines, Map<String, MdSection> map) throws IOException {
        boolean flag = false;
        if(line.contains("## ")) {
            parseTitle(line, lines, map); flag = true;
        }
        else if(line.contains("**表") || line.contains("=\"table")) {
            parseTable(line, lines, map); flag = true;
        }
        else if(line.contains("```")) {
            parseCodeBlock(line, lines, map); flag = true;
        }
        else if(line.contains("**说明：**") || line.contains("**须知：**")) {
            String tmp = line.substring(line.lastIndexOf("：")-2, line.lastIndexOf("：") + 1);
            while(curLine < lineNum && (lines.get(curLine + 1).equals("") || lines.get(curLine + 1).charAt(0) == '>')) {
                if(lines.get(curLine + 1).equals("")) {
                    ++curLine; continue;
                }
                tmp += lines.get(++curLine).substring(1);
            }
            Entities.get(curLevel).content += (tmp + "\n");
        }
        else if(TextFilter(line)) {
            flag = true;
        }
        else {
            if(!Entities.get(curLevel).content.contains(line)) {
                Entities.get(curLevel).content += (line + "\n");
                codeBelow(lines);
            }
        }
        return flag;
    }

    public void parseTitle(String line, List<String> lines, Map<String, MdSection> map) throws IOException {
        // 将上文已存在的标题实体存储
        for(int i = 2;i <= 3;i++) {
            if(Entities.get(i) != null & !map.containsKey(Entities.get(i).title)) {
                map.put(Entities.get(i).title, Entities.get(i));
                Entities.get(i-1).children.add(Entities.get(i));
            }
        }
        if(line.contains("### ")) { // 三级标题
            parseTitleContent(3, line, lines, map);
        }
        else if(line.contains("## ")) { // 二级标题
            parseTitleContent(2, line, lines, map);
        }
    }

    public void parseTable(String line, List<String> lines, Map<String, MdSection> map) throws IOException {
        String tbName = "";
        if(line.contains("**表")) tbName = line.substring(line.lastIndexOf(" "));
        else tbName = "table";
        String pattern1 = ".*</a><span>.*</span>(</strong>)?</p>.*";
        String pattern2 = ".*</a>.*</p>.*";
        ArrayList<JSONArray> tb = new ArrayList<>();
        JSONArray ja = new JSONArray();
        ++curLine;
        while(curLine < lineNum) {
            line = lines.get(++curLine);
            if(Pattern.matches(pattern1, line) || Pattern.matches(pattern2, line)) {
                line = line.replace("<span>", "");
                line = line.replace("</span>", "");
                line = line.replace("</strong>", "");
                ja.put(line.substring(line.lastIndexOf("</a>") + 4, line.lastIndexOf("</p>")));
            }
            else if(line.contains("</tr>")) {
                tb.add(ja);
                ja = new JSONArray();
            }
            else if(line.equals("</table>")) {
                try {
                    Entities.get(curLevel).table.put(tbName, tb);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return;
            }
        }
    }

    public void parseCodeBlock(String line, List<String> lines, Map<String, MdSection> map) throws IOException {
        String codeInfo = "";
        if(lines.get(curLine - 2).equals(""))
            codeInfo = lines.get(curLine - 1);
        else codeInfo = lines.get(curLine - 2);
        String codeContent = "";
        while(curLine < lineNum) {
            line = lines.get(++curLine);
            if(line.contains("```")) {
                try {
                    Entities.get(curLevel).codeBlock.put(codeInfo, codeContent);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                break;
            }
            codeContent += line + " \n ";
        }
    }

    public void parseTitleContent(int level, String line, List<String> lines, Map<String, MdSection> map) throws IOException {
        curLevel = level;
        Entities.set(level, new MdSection());
        Entities.get(level).title = line.substring(level+1, line.indexOf("<"));
        Entities.get(level).level = curLevel;
        while(curLine < lineNum) {
            String text = lines.get(++curLine);
            while(!TypeDispatch(text, lines, map) && curLine < lineNum) {
                // 普通文本，直接作为 content 添加
                if(!Entities.get(level).content.contains(text)) {
                    Entities.get(level).content += (text) + "\n";
                    codeBelow(lines);
                }
                text = lines.get(++curLine);
            }
        }
    }

    public void setTitle(List<String> lines) throws IOException {
        String title = lines.get(++curLine);
        if(title.contains("<")) title = title.substring(2, title.indexOf("<"));
        else title = title.substring(2);
        Entities.get(1).title = title;
        Entities.get(1).level = 1;
        Entities.get(1).isSection = false;
    }

    class MdSection {
        long node = -1;
        String title = "";
        int level = -1;
        boolean isCatalog = false;
        boolean isSection = true;
        String content = "";
        JSONObject codeBlock = new JSONObject(new LinkedHashMap<>());;
        JSONObject table = new JSONObject(new LinkedHashMap<>());
        ArrayList<MdSection> children = new ArrayList<>();
        ArrayList<String> linkDocs = new ArrayList<>();

        public long toNeo4j(BatchInserter inserter) {
            if(node != -1) return node;
            Map<String, Object> map = new HashMap<>();
            map.put(MdExtractor.TITLE, title);
            map.put(MdExtractor.ISCATALOG, isCatalog);
            map.put(MdExtractor.ISSECTION, isSection);
            map.put(MdExtractor.LEVEL, level);
            map.put(MdExtractor.CONTENT, content.toString());
            map.put(MdExtractor.CODEBLOCK, codeBlock.toString());
            map.put(MdExtractor.TABLE, table.toString());
            map.put(MdExtractor.LINKDOCS, linkDocs.toString());
            if(isCatalog) node = inserter.createNode(map, new Label[]{MdExtractor.MARKDOWNCATALOG});
            else if(isSection) node = inserter.createNode(map, new Label[]{MdExtractor.MARKDOWNSECTION});
            else node = inserter.createNode(map, new Label[]{MdExtractor.MARKDOWN});
            for (int i = 0; i < children.size(); i++) {
                MdSection child = children.get(i);
                if(child.level == -1) continue;
                long childId = child.toNeo4j(inserter);
                Map<String, Object> rMap = new HashMap<>();
                rMap.put(MdExtractor.SERIAL, i);
                inserter.createRelationship(node, childId, MdExtractor.SUB_MD_ELEMENT, rMap);
            }
            return node;
        }
    }
}
