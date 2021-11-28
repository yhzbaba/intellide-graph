package cn.edu.pku.sei.intellide.graph.extraction.markdown;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;


public class MdExtractor extends KnowledgeExtractor {

    public static final Label MarkdownSection = Label.label("MarkdownSection");
    public static final RelationshipType SUB_MD_ELEMENT = RelationshipType.withName("subMdElement");
    public static final RelationshipType PARENT = RelationshipType.withName("parent");
    public static final String TITLE = "title";
    public static final String CONTENT = "content";
    public static final String CODEBLOCK = "codeblock";
    public static final String TABLE = "table";
    public static final String LEVEL = "level";
    public static final String SERIAL = "serial";

    private int curLevel;
    private String root;    // 作为文件的标识
    private String tbName;
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
        Map<String, MdSection> map = new HashMap<>();
        for (File file : FileUtils.listFiles(new File(this.getDataDir()), new String[] { "md" }, true)) {
            Init();
            String fileName = file.getAbsolutePath().substring(new File(this.getDataDir()).getAbsolutePath().length())
                    .replaceAll("^[/\\\\]+", "");
            fileName = fileName.substring(0, fileName.lastIndexOf("."));

            if(!fileName.contains("shell-net-ntpdate")) continue;

            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(new File(file.getAbsolutePath())),"GBK"));
                parseMd(in, map);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        map.put(Entities.get(1).title, Entities.get(1));
        for (MdSection mdSection: map.values()) {
            System.out.println("title: " + mdSection.title);
            System.out.println("content: " + mdSection.content);
            System.out.println("codeblock: " + mdSection.codeBlock);
            System.out.println("table: " + mdSection.table);
//            mdSection.toNeo4j(this.getInserter());
        }
    }

    public void Init() {
        curLevel = 1;
        Entities = new ArrayList<MdSection>(4);
        for(int i = 0;i <= 3;i++) {
            Entities.add(i, new MdSection());
        }
    }

    public void parseMd(BufferedReader in, Map<String, MdSection> map) throws IOException {
        String title = in.readLine();
        root = title.substring(2, title.indexOf("<"));
        Entities.get(1).title = root;
        Entities.get(1).level = 1;
        String line = "";
        while ((line = in.readLine()) != null) {
            // 跳过不考虑处理的文本
            if(TextFilter(line)) continue;
            // 根据文本类型转到相应的处理函数
            TypeDispatch(line, in, map);
        }
    }

    public boolean TextFilter(String line) {
        return (line.equals("") || line.contains("-   ") || line.contains("**图") || line.contains("![]"));
    }

    public boolean TypeDispatch(String line, BufferedReader in, Map<String, MdSection> map) throws IOException {
        boolean flag = false;
        if(line.contains("# ")) {
            parseTitle(line, in, map); flag = true;
        }
        else if(line.contains("**表")) {
            parseTable(line, in, map); flag = true;
        }
        else if(line.equals("```")) {
            parseCodeBlock(line, in, map); flag = true;
        }
        return flag;
    }

    public void parseTitle(String line, BufferedReader in, Map<String, MdSection> map) throws IOException {
        // 将上文已存在的标题实体存储
        for(int i = 2;i <= 3;i++) {
            if(Entities.get(i) != null & !map.containsKey(Entities.get(i).title)) {
                map.put(Entities.get(i).title, Entities.get(i));
                Entities.get(i-1).children.add(Entities.get(i));
            }
        }
        if(line.contains("### ")) { // 三级标题
            parseTitleContent(3, line, in, map);
        }
        else if(line.contains("## ")) { // 二级标题
            parseTitleContent(2, line, in, map);
        }
    }

    public void parseTable(String line, BufferedReader in, Map<String, MdSection> map) throws IOException {
        tbName = line.substring(line.lastIndexOf(" "));
        String pattern = ".*</a>.*</p>.*";
        ArrayList<JSONArray> tb = new ArrayList<>();
        JSONArray ja = new JSONArray();
        while((line = in.readLine()) != null) {
            if(Pattern.matches(line, pattern)) {
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

    public void parseCodeBlock(String line, BufferedReader in, Map<String, MdSection> map) throws IOException {
        while((line = in.readLine()) != null) {
            if(line.equals("```")) return;
            if(TextFilter(line)) continue;
            Entities.get(curLevel).codeBlock += line;
        }
    }

    public void parseTitleContent(int level, String line, BufferedReader in, Map<String, MdSection> map) throws IOException {
        curLevel = level;
        Entities.set(level, new MdSection());
        Entities.get(level).title = root + "-" + line.substring(level+1, line.indexOf("<"));
        Entities.get(level).level = curLevel;
        String text = "";
        while((text = in.readLine()) != null) {
            if(TextFilter(text)) continue;
            while(!TypeDispatch(text, in, map)) {
                if(TextFilter(text)) {
                    text = in.readLine();
                    if(text == null) break;
                    continue;
                }
                // 普通文本，直接作为 content 添加
                Entities.get(level).content += (text) + "\n";
                text = in.readLine();
                if(text == null) break;
            }
        }
    }

    class MdSection {
        long node = -1;
        String title = "";
        int level = -1;
        int serial = 0;
        String content;
        String codeBlock;
        JSONObject table = new JSONObject(new LinkedHashMap<>());
        ArrayList<MdSection> children = new ArrayList<>();

        public long toNeo4j(BatchInserter inserter) {
            if(node != -1) return node;
            Map<String, Object> map = new HashMap<>();
            map.put(MdExtractor.TITLE, title);
            map.put(MdExtractor.LEVEL, level);
            map.put(MdExtractor.SERIAL, serial);
            map.put(MdExtractor.CONTENT, content.toString());
            map.put(MdExtractor.CODEBLOCK, codeBlock);
            map.put(MdExtractor.TABLE, table.toString());
            node = inserter.createNode(map, new Label[] {MdExtractor.MarkdownSection});
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