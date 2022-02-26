package cn.edu.pku.sei.intellide.graph.extraction.git;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.CProjectInfo;
import org.eclipse.core.runtime.CoreException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;

import java.io.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Utils {

    /**
     * 访问数据库，获取当前图谱最新的commit_time
     */
    public static int getCommitTime(GraphDatabaseService db) {
        try (Transaction tx = db.beginTx()) {
            ResourceIterator<Node> nodes = db.findNodes(GitUpdate.TIMESTAMP);
            if(nodes.hasNext()) {
                Node node = nodes.next();
                return (int) node.getProperty(GitUpdate.COMMIT_TIME);
            }
            tx.success();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return -1;
    }

    /**
     * 获取修改的代码文件名称
     */
    public static String getFileFromDiff(String diff) {
        String res = "";
        if(diff.contains(".c")) {
            Pattern r = Pattern.compile("\\s\\w+.c");
            Matcher m = r.matcher(diff);
            if(m.find()) {
                res = m.group().substring(1);
            }
        }
        else {
            Pattern r = Pattern.compile("\\s\\w+.h");
            Matcher m = r.matcher(diff);
            if(m.find()) {
                res = m.group().substring(1);
            }
        }
        return res;
    }

    public static String getFileContent(String filePath) {
        String res = "";
        File file = new File(filePath);
        FileReader reader = null;
        try {
            reader = new FileReader(file);
            BufferedReader bReader = new BufferedReader(reader);
            StringBuilder sb = new StringBuilder();
            String s = "";
            while ((s =bReader.readLine()) != null) {
                sb.append(s + "\n");
            }
            bReader.close();
            res = sb.toString();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return res;
    }

    /**
     * 利用位置索引获取代码片段，通过正则匹配或者截取子串得到修改代码元素的名称
     * @param type 标识修改代码元素的类型，如 function, variable...
     */
    public static String getItemName(int start, int end, String fileContent, String type) {
        String res = "";
        String tmp = "";
        if(start == 0 && end == 0) tmp = fileContent;
        else tmp = fileContent.substring(start, end);
        if(type.equals("Include")) {
            res = tmp.substring(tmp.indexOf("\"") + 1, tmp.lastIndexOf("\""));
        }
        else if(type.equals("MacroVar")) {
            Matcher m = Pattern.compile("#define\\s(\\w+)\\s").matcher(tmp);
            if(m.find()) {
                res = m.group(1);
            }
        }
        else if(type.equals("MacroFunc")) {
            // format: function(param...)
            Matcher m = Pattern.compile("#define\\s(\\w+)\\(").matcher(tmp);
            if(m.find()) {
                res = m.group(1);
            }
        }
        else if(type.equals("Typedef")) {
            // format: GenericString: name [s,e]
            // typedef struct { } name;
            Matcher m = Pattern.compile("\\s(\\w+)\\s\\[").matcher(tmp);
            if(m.find()) {
                res = m.group(1);
            }
        }
        else if(type.equals("Struct")) {
            // struct { };
            Matcher m = Pattern.compile("struct\\s(\\w+)\\s").matcher(tmp);
            if(m.find()) {
                res = m.group(1);
            }
        }
        else if(type.equals("FuncDef")) {
            // TODO: 最好的处理是函数名称+参数列表，这里暂且只使用函数名
            // modifier/type name(params...)
            Matcher m = Pattern.compile("\\w+\\s(\\w+)\\(.*\\)").matcher(tmp);
            if(m.find()) {
                res = m.group(1);
            }
        }
        else if(type.equals("Function")) {
            Matcher m = Pattern.compile("\\w+\\s(\\w+)\\(.*\\)[\\n\\s]").matcher(tmp);
            if(m.find()) {
                res = m.group(1);
            }
        }
        return res;
    }

    /**
     * 向上回溯找到函数定义的父结点
     * e.g. FunCall -> Assignment -> Some -> ExprStatement -> Compound -> Definition
     */
    public static String getFunctionFromDef(GraphUpdate.EditAction editAction, String srcContent, String dstContent) {
        String res = "";
        String tmp = editAction.Node.toString();
        while(!tmp.contains("Definition")) {
            editAction.Node = editAction.Node.getParent();
            tmp = editAction.Node.toString();
        }
        editAction.pNode = tmp.substring(0, tmp.indexOf(" "));
        editAction.pStart = Integer.parseInt(tmp.substring(tmp.indexOf("[") + 1, tmp.indexOf(",")));
        editAction.pEnd = Integer.parseInt(tmp.substring(tmp.indexOf(",") + 1, tmp.indexOf("]"))) - 1;
        if(editAction.type.contains("insert")) {
            res = Utils.getItemName(editAction.pStart, editAction.pEnd, dstContent, "FuncDef");
        }
        else if(editAction.type.contains("delete")) {
            res = Utils.getItemName(editAction.pStart, editAction.pEnd, srcContent, "FuncDef");
        }
        return res;
    }
}
