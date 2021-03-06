package cn.edu.pku.sei.intellide.graph.extraction.git;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
import com.alibaba.fastjson.JSONArray;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.RelationshipType;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitExtractor extends KnowledgeExtractor {

    public static final RelationshipType PARENT = RelationshipType.withName("parent");
    public static final String NAME = "name";
    public static final String MESSAGE = "message";
    public static final String COMMIT_TIME = "commitTime";
    public static final String DIFF_SUMMARY = "diffSummary";
    public static final Label COMMIT = Label.label("Commit");
    public static final Label TIMESTAMP = Label.label("TimeStamp");
    public static final String EMAIL_ADDRESS = "emailAddress";
    public static final Label GIT_USER = Label.label("GitUser");
    public static final RelationshipType CREATOR = RelationshipType.withName("creator");
    public static final RelationshipType COMMITTER = RelationshipType.withName("committer");

    private Map<String, Long> commitMap = new HashMap<>();
    private Map<String, Long> personMap = new HashMap<>();
    private Map<String, Set<String>> parentsMap = new HashMap<>();
    // 记录最新的commit
    private boolean flag = false;

//    static JSONArray diffInfos = new JSONArray();

    public static void main(String[] args) {
//        GitExtractor test = new GitExtractor();
//        test.setDataDir("E:\\changwenhui\\SoftwareReuse\\knowledgeGraph\\openHarmony\\kernel_liteos_a\\.git");
//        test.extraction();
        String s = "lite\\kernel\\file\\conmd.d";
        if(s.contains("\\")) {
            s = s.replaceAll("\\\\", "/");
        }
        System.out.println(s);
    }

    @Override
    public boolean isBatchInsert() {
        return true;
    }

    @Override
    public void extraction() {
        Repository repository = null;
        FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder();
        repositoryBuilder.setMustExist(true);
        repositoryBuilder.setGitDir(new File(this.getDataDir()));
        try {
            repository = repositoryBuilder.build();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (repository.getObjectDatabase().exists()) {
            Git git = new Git(repository);
            Iterable<RevCommit> commits = null;
            try {
                commits = git.log().call();
//                commits = git.log().setMaxCount(100).call();
            } catch (GitAPIException e) {
                e.printStackTrace();
            }
            // 只取50条记录，同时最新的10条记录用于update阶段
            int cnt = 0;
            for (RevCommit commit : commits) {
//                if(cnt < 30) {
//                    cnt++; continue;
//                }
                try {
                    parseCommit(commit, repository, git);
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (GitAPIException e) {
                    e.printStackTrace();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
        parentsMap.entrySet().forEach(entry -> {
            long commitNodeId = commitMap.get(entry.getKey());
            entry.getValue().forEach(parentName -> {
                if (commitMap.containsKey(parentName))
                    this.getInserter().createRelationship(commitNodeId, commitMap.get(parentName), PARENT, new HashMap<>());
            });
        });
    }

    private void parseCommit(RevCommit commit, Repository repository, Git git) throws IOException, GitAPIException, JSONException {
        Map<String, Object> map = new HashMap<>();
        map.put(NAME, commit.getName());
        String message = commit.getFullMessage();
        map.put(MESSAGE, message != null ? message : "");
        map.put(COMMIT_TIME, commit.getCommitTime());
        List<String> diffStrs = new ArrayList<>();
        Set<String> parentNames = new HashSet<>();

        for (int i = 0; i < commit.getParentCount(); i++) {
            parentNames.add(commit.getParent(i).getName());
            ObjectId head = repository.resolve(commit.getName() + "^{tree}");
            ObjectId old = repository.resolve(commit.getParent(i).getName() + "^{tree}");
            ObjectReader reader = repository.newObjectReader();
            CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
            oldTreeIter.reset(reader, old);
            CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
            newTreeIter.reset(reader, head);
            List<DiffEntry> diffs = git.diff().setNewTree(newTreeIter).setOldTree(oldTreeIter).call();
            for (int k = 0; k < diffs.size(); k++) {
                /*
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                DiffFormatter df = new DiffFormatter(out);
                df.setDiffComparator(RawTextComparator.WS_IGNORE_ALL);
                df.setRepository(git.getRepository());
                df.format(diffs.get(k));
                String diffText = out.toString("UTF-8");
                diff += diffText;
                 */
                diffStrs.add(diffs.get(k).getChangeType().name() + " " + diffs.get(k).getOldPath() + " to " + diffs.get(k).getNewPath());
            }
//            if(diff.equals("")) continue;
            // 对 diff 进行拆分，暂且以文件作为划分依据（diff --git分割）
//            splitDiffs(diff, diffInfos);

        }
        map.put(DIFF_SUMMARY, String.join("\n", diffStrs));
//        map.put(DIFF_INFO, diffInfos.toString());
        long commitNodeId = this.getInserter().createNode(map, COMMIT);
        // 将最新的commit标记
        if(!flag) {
            this.getInserter().createNode(map, TIMESTAMP);
            flag = true;
        }
        commitMap.put(commit.getName(), commitNodeId);
        parentsMap.put(commit.getName(), parentNames);
        PersonIdent author = commit.getAuthorIdent();
        String personStr = author.getName() + ": " + author.getEmailAddress();
        if (!personMap.containsKey(personStr)) {
            Map<String, Object> pMap = new HashMap<>();
            String name = author.getName();
            String email = author.getEmailAddress();
            pMap.put(NAME, name != null ? name : "");
            pMap.put(EMAIL_ADDRESS, email != null ? email : "");
            long personNodeId = this.getInserter().createNode(pMap, GIT_USER);
            personMap.put(personStr, personNodeId);
            this.getInserter().createRelationship(commitNodeId, personNodeId, CREATOR, new HashMap<>());
        } else
            this.getInserter().createRelationship(commitNodeId, personMap.get(personStr), CREATOR, new HashMap<>());
        PersonIdent committer = commit.getCommitterIdent();
        personStr = committer.getName() + ": " + committer.getEmailAddress();
        if (!personMap.containsKey(personStr)) {
            Map<String, Object> pMap = new HashMap<>();
            String name = committer.getName();
            String email = committer.getEmailAddress();
            pMap.put(NAME, name != null ? name : "");
            pMap.put(EMAIL_ADDRESS, email != null ? email : "");
            long personNodeId = this.getInserter().createNode(pMap, GIT_USER);
            personMap.put(personStr, personNodeId);
            this.getInserter().createRelationship(commitNodeId, personNodeId, COMMITTER, new HashMap<>());
        } else
            this.getInserter().createRelationship(commitNodeId, personMap.get(personStr), COMMITTER, new HashMap<>());
    }

    /**
     * 以文件为key值，对单个commit的内容进行分割
     */
    public static void splitDiffs(String diff, JSONArray diffInfos) throws JSONException {
        List<String> dg = new ArrayList<>();
        Matcher m = Pattern.compile("diff --git.*\\n").matcher(diff);
        while(m.find()) {
            dg.add(m.group());
        }
        int i = 0;
        String filePath = "";
        for(;i < dg.size() - 1;i++) {
            filePath = getFilePath(dg.get(i));
            JSONObject jo = new JSONObject();
            jo.put(filePath, diff.substring(diff.indexOf(dg.get(i)), diff.indexOf(dg.get(i+1))));
            diffInfos.add(jo);
        }
        filePath = getFilePath(dg.get(i));
        JSONObject jo = new JSONObject();
        jo.put(filePath, diff.substring(diff.indexOf(dg.get(i))));
        diffInfos.add(jo);
    }

    public static String getFilePath(String msg) {
        Matcher m = Pattern.compile("a/.*b/").matcher(msg);
        String res = "";
        if(m.find()) {
            res = m.group();
            return res.substring(2, res.length() - 3);
        }
        return res;
    }
}
