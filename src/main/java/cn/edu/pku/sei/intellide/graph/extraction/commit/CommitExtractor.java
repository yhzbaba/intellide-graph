package cn.edu.pku.sei.intellide.graph.extraction.commit;

import cn.edu.pku.sei.intellide.graph.extraction.KnowledgeExtractor;
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
import org.json.JSONException;
import org.json.JSONObject;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.RelationshipType;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommitExtractor extends KnowledgeExtractor {
    public static final RelationshipType PARENT = RelationshipType.withName("parent");
    public static final String NAME = "name";
    public static final String MESSAGE = "message";
    public static final String COMMIT_TIME = "commitTime";
    public static final String DIFF_SUMMARY = "diffSummary";
    public static final String DIFF_INFO = "diffInfo";
    public static final Label COMMIT = Label.label("Commit");
    public static final String EMAIL_ADDRESS = "emailAddress";
    public static final Label GIT_USER = Label.label("GitUser");
    public static final RelationshipType CREATOR = RelationshipType.withName("creator");
    public static final RelationshipType COMMITTER = RelationshipType.withName("committer");

    private Map<String, Long> commitMap = new HashMap<>();
    private Map<String, Long> personMap = new HashMap<>();
    private Map<String, Set<String>> parentsMap = new HashMap<>();

    public static void main(String[] args) {
        CommitExtractor test = new CommitExtractor();
        test.setDataDir("D:\\documents\\SoftwareReuse\\knowledgeGraph\\gradDesign\\parseData\\.git");
        test.extraction();
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
//                commits = git.log().setMaxCount(10).call();
            } catch (GitAPIException e) {
                e.printStackTrace();
            }
            for (RevCommit commit : commits) {
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
//        System.out.println("===== commit information: ======");
//        System.out.println("commit name: " + commit.getName());
//        System.out.println(commit.getShortMessage());
//        System.out.println("parent commit number: " + commit.getParentCount());
        Map<String, Object> map = new HashMap<>();
        map.put(NAME, commit.getName());
        String message = commit.getFullMessage();
        map.put(MESSAGE, message != null ? message : "");
        map.put(COMMIT_TIME, commit.getCommitTime());
        List<String> diffStrs = new ArrayList<>();
        JSONObject diffInfos = new JSONObject(new LinkedHashMap<>());
        Set<String> parentNames = new HashSet<>();
        // 当前 commit 和 parent commits 的 diffs
        for (int i = 0; i < commit.getParentCount(); i++) {
//            System.out.println("===== commit parent information: ======");
//            System.out.println("parent name: " + commit.getParent(i).getName());
            parentNames.add(commit.getParent(i).getName());
            ObjectId head = repository.resolve(commit.getName() + "^{tree}");
            ObjectId old = repository.resolve(commit.getParent(i).getName() + "^{tree}");
            ObjectReader reader = repository.newObjectReader();
            CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
            oldTreeIter.reset(reader, old);
            CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
            newTreeIter.reset(reader, head);
            List<DiffEntry> diffs = git.diff().setNewTree(newTreeIter).setOldTree(oldTreeIter).call();
            String diff = "";
            for (int k = 0; k < diffs.size(); k++) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                DiffFormatter df = new DiffFormatter(out);
                df.setDiffComparator(RawTextComparator.WS_IGNORE_ALL);
                df.setRepository(git.getRepository());
                df.format(diffs.get(k));
                String diffText = out.toString("UTF-8");
//                System.out.println(diffText);
                diff += diffText;
                diffStrs.add(diffs.get(k).getChangeType().name() + " " + diffs.get(k).getOldPath() + " to " + diffs.get(k).getNewPath());
            }
            if(diff.equals("")) continue;
            // 对 diff 进行拆分，暂且以文件作为划分依据（diff --git分割）
            JSONObject diffList = splitDiffs(diff);
            diffInfos.put(commit.getParent(i).getName(), diffList.toString());
        }
        map.put(DIFF_SUMMARY, String.join("\n", diffStrs));
        map.put(DIFF_INFO, diffInfos.toString());
        long commitNodeId = this.getInserter().createNode(map, COMMIT);
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

    private JSONObject splitDiffs(String diff) throws JSONException {
        JSONObject res = new JSONObject();
        List<String> dg = new ArrayList<>();
        Matcher m = Pattern.compile("diff --git.*\\n").matcher(diff);
        while(m.find()) {
            dg.add(m.group());
        }
        int i = 0;
        String filePath = "";
        for(;i < dg.size() - 1;i++) {
            filePath = getFilePath(dg.get(i));
            res.put(filePath, diff.substring(diff.indexOf(dg.get(i)), diff.indexOf(dg.get(i+1))));
        }
        filePath = getFilePath(dg.get(i));
        res.put(filePath ,diff.substring(diff.indexOf(dg.get(i))));
        return res;
    }

    private String getFilePath(String msg) {
        Matcher m = Pattern.compile("a/.*b/").matcher(msg);
        String res = "";
        if(m.find()) {
            res = m.group();
            return res.substring(2, res.length() - 3);
        }
        return res;
    }
}
