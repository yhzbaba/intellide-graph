package cn.edu.pku.sei.intellide.graph.extraction.c_code.utils;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class ProjectFilesReader {
    File curFile;

    List<ProjectFilesReader> fileComponentList = new ArrayList<>();

    public ProjectFilesReader(File file) {
        curFile = file;

        File[] childFiles = curFile.listFiles();

        if(null != childFiles && childFiles.length > 0) {
            for (File child : childFiles) {
                ProjectFilesReader childFileComp = new ProjectFilesReader(child);
                fileComponentList.add(childFileComp);
            }
        }
    }

    public ProjectFilesReader(String filePath) {
        this(new File(filePath));
    }

    public List<File> getAllFilesAndDirsList() {
        List<File> files = new ArrayList<>();
        files.add(curFile);
        for (ProjectFilesReader child: fileComponentList) {
            files.addAll(child.getAllFilesAndDirsList());
        }
        return files;
    }
}
