package cn.edu.pku.sei.intellide.graph.extraction.c_code.utils;

import org.eclipse.cdt.core.dom.ast.IASTTranslationUnit;
import org.eclipse.cdt.core.dom.ast.gnu.c.GCCLanguage;
import org.eclipse.cdt.core.model.ILanguage;
import org.eclipse.cdt.core.parser.DefaultLogService;
import org.eclipse.cdt.core.parser.FileContent;
import org.eclipse.cdt.core.parser.IncludeFileContentProvider;
import org.eclipse.cdt.core.parser.ScannerInfo;
import org.eclipse.core.runtime.CoreException;

import java.io.*;

public class GetTranslationUnitUtil {
    private static final ILanguage language = GCCLanguage.getDefault();

    private static String getContent(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static FileContent file2FileContent(File file) throws IOException {
        return FileContent.create(file.getAbsolutePath(), getContent(file).toCharArray());
    }

    public static IASTTranslationUnit getASTTranslationUnit(File file) throws CoreException, IOException {
        FileContent content = file2FileContent(file);
        return language.getASTTranslationUnit(content,
                new ScannerInfo(),
                IncludeFileContentProvider.getEmptyFilesProvider(),
                null,
                ILanguage.OPTION_IS_SOURCE_UNIT,
                new DefaultLogService());
    }

    public static long getLines(File file) throws IOException {
        if (file.exists()) {
            try {
                FileReader fileReader = new FileReader(file);
                LineNumberReader lineNumberReader = new LineNumberReader(fileReader);
                lineNumberReader.skip(Long.MAX_VALUE);
                long lines = lineNumberReader.getLineNumber() + 1;
                fileReader.close();
                lineNumberReader.close();
                return lines;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return 0;
    }
}
