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
import org.neo4j.graphdb.*;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class GitUpdate extends KnowledgeExtractor {
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

    private Map<String, Node> commitMap = new HashMap<>();
    private Map<String, Node> personMap = new HashMap<>();
    private Map<String, Set<String>> parentsMap = new HashMap<>();
    /* 记录并更新图谱的 TIMESTAMP 节点 */
    private Map<String, Object> timeStampMap = new HashMap<>();
    private boolean flag = false;

    /**
     * 记录此次更新涉及到的commit信息（commit_name作为标识）
     */
    private Map<String, CommitInfo> commitInfos = new HashMap<>();

    public static void main(String[] args) {
        GitExtractor test = new GitExtractor();
        test.setDataDir("E:\\changwenhui\\SoftwareReuse\\knowledgeGraph\\openHarmony\\kernel_liteos_a\\.git");
        test.extraction();
    }

    @Override
    public void extraction() {
        Repository repository = null;
        FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder();
        repositoryBuilder.setMustExist(true);
        repositoryBuilder.setGitDir(new File(this.getDataDir()));

        // 当前图谱的最新的 commit_time
        int timeStamp = getCommitTime();
        System.out.println(timeStamp);
        try {
            repository = repositoryBuilder.build();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (repository.getObjectDatabase().exists()) {
            Git git = new Git(repository);
            Iterable<RevCommit> commits = null;
            try {
                // 获取所有 commit 日志记录
                commits = git.log().call();
//                commits = git.log().setMaxCount(30).call();
            } catch (GitAPIException e) {
                e.printStackTrace();
            }
            for (RevCommit commit : commits) {
                try {
                    // 已处理过的commit数据，跳过
                    if(commit.getCommitTime() < timeStamp) continue;

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
        GraphDatabaseService db = this.getDb();
        try (Transaction tx = db.beginTx()) {
            parentsMap.entrySet().forEach(entry -> {
                Node commitNode = commitMap.get(entry.getKey());
                entry.getValue().forEach(parentName -> {
                    if (commitMap.containsKey(parentName)) {
                        commitNode.createRelationshipTo(commitMap.get(parentName), PARENT);
                    }
                });
            });
            tx.success();
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        // 根据 commitInfos 中的 commit 信息，利用 GumTree 得到 edit actions 从而更新图谱
        new GraphUpdate(commitInfos);

        // 更新 timeStamp
        updateTimeStamp();
    }

    /**
     * 解析单个commit，并创建相关实体的联系
     * @throws IOException
     * @throws GitAPIException
     */
    private void parseCommit(RevCommit commit, Repository repository, Git git) throws IOException, GitAPIException, JSONException {

//        System.out.println(commit.getShortMessage());

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
                diffStrs.add(diffs.get(k).getChangeType().name() + " " + diffs.get(k).getOldPath() + " to " + diffs.get(k).getNewPath());
            }
        }
        map.put(DIFF_SUMMARY, String.join("\n", diffStrs));

        // 如果是最新的commit，记录作为 timeStamp
        if(!flag) {
            flag = true;
            timeStampMap = map;
        }

        // neo4j transaction
        GraphDatabaseService db = this.getDb();
        try (Transaction tx = db.beginTx()) {
            // 创建 commit 节点并设置属性
            Node commitNode = db.createNode(COMMIT);
            commitNode.setProperty(NAME, map.get(NAME));
            commitNode.setProperty(MESSAGE, map.get(MESSAGE));
            commitNode.setProperty(COMMIT_TIME, map.get(COMMIT_TIME));
            commitNode.setProperty(DIFF_SUMMARY, map.get(DIFF_SUMMARY));

            commitMap.put(commit.getName(), commitNode);
            parentsMap.put(commit.getName(), parentNames);
            PersonIdent author = commit.getAuthorIdent();
            String personStr = author.getName() + ": " + author.getEmailAddress();
            if (!personMap.containsKey(personStr)) {
                Map<String, Object> pMap = new HashMap<>();
                String name = author.getName();
                String email = author.getEmailAddress();
                pMap.put(NAME, name != null ? name : "");
                pMap.put(EMAIL_ADDRESS, email != null ? email : "");
                Node personNode = db.createNode(GIT_USER);
                personNode.setProperty(NAME, pMap.get(NAME));
                personNode.setProperty(EMAIL_ADDRESS, pMap.get(EMAIL_ADDRESS));
                personMap.put(personStr, personNode);
                commitNode.createRelationshipTo(personNode, CREATOR);
            } else {
                commitNode.createRelationshipTo(personMap.get(personStr), CREATOR);
            }
            PersonIdent committer = commit.getCommitterIdent();
            personStr = committer.getName() + ": " + committer.getEmailAddress();
            if (!personMap.containsKey(personStr)) {
                Map<String, Object> pMap = new HashMap<>();
                String name = committer.getName();
                String email = committer.getEmailAddress();
                pMap.put(NAME, name != null ? name : "");
                pMap.put(EMAIL_ADDRESS, email != null ? email : "");
                Node personNode = db.createNode(GIT_USER);
                personNode.setProperty(NAME, pMap.get(NAME));
                personNode.setProperty(EMAIL_ADDRESS, pMap.get(EMAIL_ADDRESS));
                personMap.put(personStr, personNode);
                commitNode.createRelationshipTo(personNode, COMMITTER);
            } else {
                commitNode.createRelationshipTo(personMap.get(personStr), COMMITTER);
            }
            tx.success();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        /* 记录 commit 修改文件以及父结点，在图谱更新中进一步处理 */
        addCommitInfo(map, parentNames);
    }

    /**
     * 访问数据库，获取当前图谱最新的commit_time
     */
    private int getCommitTime() {
        GraphDatabaseService db = this.getDb();
        try (Transaction tx = db.beginTx()) {
            ResourceIterator<Node> nodes = db.findNodes(TIMESTAMP);
            if(nodes.hasNext()) {
                Node node = nodes.next();
                int commitTime = (int) node.getProperty(COMMIT_TIME);
                return commitTime;
            }
            tx.success();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return -1;
    }

    /**
     * 将当前commit的信息存入全局变量 commitInfos 中
     * 主要是 diffSummary 属性，用于文件定位
     */
    private void addCommitInfo(Map<String, Object> map, Set<String> parentNames) {
        CommitInfo gitInfo = new CommitInfo();
        gitInfo.name = (String) map.get(NAME);
        gitInfo.diffSummary = Arrays.asList(((String) map.get(DIFF_SUMMARY)).split("\n"));
        gitInfo.parent.addAll(parentNames);
        commitInfos.put(gitInfo.name, gitInfo);
    }

    /**
     * 将图谱的最新commit节点更新
     */
    private void updateTimeStamp() {
        GraphDatabaseService db = this.getDb();
        try (Transaction tx = db.beginTx()) {
            ResourceIterator<Node> nodes = db.findNodes(TIMESTAMP);
            if(nodes.hasNext()) {
                Node node = nodes.next();
                node.delete();
                Node tsNode = db.createNode(TIMESTAMP);
                tsNode.setProperty(NAME, timeStampMap.get(NAME));
                tsNode.setProperty(MESSAGE, timeStampMap.get(MESSAGE));
                tsNode.setProperty(COMMIT_TIME, timeStampMap.get(COMMIT_TIME));
                tsNode.setProperty(DIFF_SUMMARY, timeStampMap.get(DIFF_SUMMARY));
            }
            tx.success();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * 内部类，用于记录图谱更新需要的commit信息
     */
    class CommitInfo {
        String name;

        boolean isHandled = false;

        /* 每个String是: Change_type oldFilePath to newFilePath 的格式 */
        List<String> diffSummary = new ArrayList<>();

        List<String> parent = new ArrayList<>();
    }
}
