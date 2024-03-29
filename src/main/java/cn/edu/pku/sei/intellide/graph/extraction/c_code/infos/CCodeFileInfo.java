package cn.edu.pku.sei.intellide.graph.extraction.c_code.infos;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.CExtractor;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.process.CHandleASTProcess;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity.PrimitiveClass;
import cn.edu.pku.sei.intellide.graph.extraction.c_code.utils.*;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.cdt.core.dom.ast.*;
import org.eclipse.cdt.core.dom.ast.c.ICASTTypedefNameSpecifier;
import org.eclipse.cdt.internal.core.dom.parser.c.CASTParameterDeclaration;
import org.neo4j.unsafe.batchinsert.BatchInserter;

import java.util.*;

public class CCodeFileInfo {
    @Getter
    private long id = -1;
    @Getter
    private String fileName;
    @Getter
    private String tailFileName;
    @Getter
    private IASTTranslationUnit unit;
    @Getter
    @Setter
    private List<CFunctionInfo> functionInfoList = new ArrayList<>();
    @Getter
    @Setter
    private List<String> includeCodeFileList = new ArrayList<>();
    @Getter
    @Setter
    private List<CDataStructureInfo> dataStructureList = new ArrayList<>();
    @Getter
    @Setter
    private List<CVariableInfo> variableInfoList = new ArrayList<>();

    @Getter
    @Setter
    private List<CVariableInfo> functionPointerList = new ArrayList<>();

    private BatchInserter inserter = null;

    public CCodeFileInfo(String tailFileName, String fileName) {
        this.tailFileName = tailFileName;
        this.fileName = fileName;
    }

//    public CCodeFileInfo(String tailFileName, String fileName, IASTTranslationUnit unit) {
//        this.fileName = fileName;
//        this.tailFileName = tailFileName;
//        this.unit = unit;
//        this.initFunctions();
//        this.initDataStructures();
//        this.initVariables();
//        this.initIncludeCodeFiles();
//    }

    public CCodeFileInfo(BatchInserter inserter, String fileName, String tailFileName, IASTTranslationUnit unit) {
        this.fileName = fileName;
        this.tailFileName = tailFileName;
        this.unit = unit;
        this.inserter = inserter;
        CHandleASTProcess.initSingleAST(this);
        if (inserter != null) {
            this.createNode();
        }
    }

    /**
     * 处理函数声明
     * 暂时不包括宏函数
     */
    public void initFunctions() {
        IASTDeclaration[] declarations = unit.getDeclarations();
        for (IASTDeclaration declaration : declarations) {
            if (declaration instanceof IASTFunctionDefinition) {
                CFunctionInfo functionInfo = new CFunctionInfo();
                IASTFunctionDefinition functionDefinition = (IASTFunctionDefinition) declaration;
                functionInfo.setFunctionDefinition(functionDefinition);
                IASTDeclSpecifier declSpecifier = functionDefinition.getDeclSpecifier();
                IASTDeclarator declarator = functionDefinition.getDeclarator();
                String functionName = declarator.getName().toString();
                functionInfo.setName(functionName);
                String fullFunctionName = declarator.getRawSignature();
                functionInfo.setFullName(fullFunctionName);
//                functionInfo.setContent(functionDefinition.getBody().getRawSignature());
                functionInfo.setBelongTo(fileName);
                functionInfo.setBelongToName(fileName + functionName);
                for (IASTNode child : declarator.getChildren()) {
                    if (child instanceof CASTParameterDeclaration) {
                        CASTParameterDeclaration childP = (CASTParameterDeclaration) child;
                        functionInfo.getFullParams().add(childP.getRawSignature());
                    }
                }
                functionInfo.setIsInline(declSpecifier.isInline());
                functionInfo.setIsConst(declSpecifier.isConst());
                functionInfo.setIsDefine(false);
                FunctionUtil.FUNCTION_HASH_LIST[FunctionUtil.hashFunc(functionName)].add(functionInfo);
                if (this.inserter != null) {
                    functionInfo.createNode(inserter);
                }
                functionInfoList.add(functionInfo);
            }
        }
//        deDuplication(functionInfoList);
    }

    public void initDataStructures() {
        IASTDeclaration[] declarations = unit.getDeclarations();
        for (IASTDeclaration declaration : declarations) {
            if (declaration instanceof IASTSimpleDeclaration) {
                IASTSimpleDeclaration simpleDeclaration = (IASTSimpleDeclaration) declaration;
                IASTDeclSpecifier declSpecifier = simpleDeclaration.getDeclSpecifier();
                if (declSpecifier instanceof IASTEnumerationSpecifier) {
                    // 这块区域是enum，包括typedef
                    CDataStructureInfo structureInfo = new CDataStructureInfo(inserter);
                    structureInfo.setSimpleDeclaration(simpleDeclaration);
                    structureInfo.setIsEnum(true);
                    structureInfo.setName(((IASTEnumerationSpecifier) declSpecifier).getName().toString());
                    structureInfo.setTypedefName("");
                    if (ASTUtil.isTypeDef(declSpecifier)) {
                        // 是typedef enum
                        for (IASTDeclarator declarator : simpleDeclaration.getDeclarators()) {
                            structureInfo.setTypedefName(declarator.getName().toString());
                        }
                    }
                    structureInfo.initEnumFieldInfo();
                    if (this.inserter != null) {
                        structureInfo.createNode();
                    }
                    dataStructureList.add(structureInfo);
                } else if (declSpecifier instanceof IASTCompositeTypeSpecifier) {
                    // 结构体 包括typedef
                    CDataStructureInfo structureInfo = new CDataStructureInfo(inserter);
                    structureInfo.setSimpleDeclaration(simpleDeclaration);
//                    structureInfo.setContent(simpleDeclaration.getRawSignature());
                    structureInfo.setIsEnum(false);
                    structureInfo.setName(((IASTCompositeTypeSpecifier) declSpecifier).getName().toString());
                    structureInfo.setTypedefName("");
                    if (ASTUtil.isTypeDef(declSpecifier)) {
                        // 是typedef struct
                        for (IASTDeclarator declarator : simpleDeclaration.getDeclarators()) {
                            structureInfo.setTypedefName(declarator.getName().toString());
                        }
                    }
                    structureInfo.initStructFieldInfo();
                    if (this.inserter != null) {
                        structureInfo.createNode();
                    }
                    dataStructureList.add(structureInfo);
                }
            }
        }
//        deDuplication(dataStructureList);
    }

    public void initVariables() {
        IASTDeclaration[] declarations = unit.getDeclarations();
        for (IASTDeclaration declaration : declarations) {
            if (declaration instanceof IASTSimpleDeclaration) {
                IASTSimpleDeclaration simpleDeclaration = (IASTSimpleDeclaration) declaration;
                IASTDeclSpecifier declSpecifier = simpleDeclaration.getDeclSpecifier();
                if (declSpecifier instanceof IASTSimpleDeclSpecifier) {
                    for (IASTDeclarator declarator : simpleDeclaration.getDeclarators()) {
                        if (declarator instanceof IASTFunctionDeclarator) {
                            if (declSpecifier.getRawSignature().startsWith("typedef")) {
                                // 定义的是typedef函数指针类型
                                if (declSpecifier.getRawSignature().split(" ").length > 1) {
                                    PrimitiveMapUtil.insert(declarator.getName().toString(), new PrimitiveClass("", true, fileName, declSpecifier.getRawSignature().split(" ")[1]));
                                }
                            }
                            // (*fun)() common_fun() (*fun)() = &PFB_2
                            if (!"".equals(declarator.getName().toString())) {
                                //common_fun()
                                CFunctionInfo functionInfo;
                                if (declSpecifier.getRawSignature().contains("const")) {
                                    functionInfo = new CFunctionInfo(declarator.getName().toString(), simpleDeclaration.getRawSignature(), fileName + declarator.getName().toString(), false, true, true, fileName + declarator.getName().toString(), null);
                                } else {
                                    functionInfo = new CFunctionInfo(declarator.getName().toString(), simpleDeclaration.getRawSignature(), fileName + declarator.getName().toString(), false, false, true, fileName + declarator.getName().toString(), null);
                                }
                                if (this.inserter != null) {
                                    functionInfo.createNode(inserter);
                                }
                                functionInfoList.add(functionInfo);
                            } else {
                                // (*fun)() (*fun)() = &PFB_2
                                CVariableInfo info = new CVariableInfo();
                                info.setContent("");
                                info.setBelongTo(fileName);
                                if (declarator.getNestedDeclarator() != null) {
                                    info.setName(declarator.getNestedDeclarator().getRawSignature());
                                }
                                if (declarator.getInitializer() != null) {
                                    info.setEqualsInitializer(declarator.getInitializer());
                                }
                                info.setIsDefine(false);
                                info.setIsStructVariable(false);
                                info.setIsFunctionPointer(true);
                                if (this.inserter != null) {
                                    info.createNode(inserter);
                                }
                                variableInfoList.add(info);
                                functionPointerList.add(info);
                                FunctionPointerUtil.FUNCTION_POINTER_HASH_LIST[FunctionPointerUtil.hashFunctionPointer(info.getName())].add(info);
                                VariableUtil.VARIABLE_HASH_LIST[VariableUtil.hashVariable(info.getName())].add(info);
                            }
                        } else {
                            IASTSimpleDeclSpecifier simpleDeclSpecifier = (IASTSimpleDeclSpecifier) declSpecifier;
                            CVariableInfo variableInfo = new CVariableInfo();
                            variableInfo.setSpecifier(simpleDeclSpecifier);
                            variableInfo.setSimpleDeclaration(simpleDeclaration);
                            String variableName = declarator.getName().toString();
                            if (declarator.getInitializer() != null) {
                                variableInfo.setEqualsInitializer(declarator.getInitializer());
                            }
                            variableInfo.setName(variableName);
                            variableInfo.setBelongTo(fileName);
                            // typedef long long ll;
                            variableInfo.setIsDefine(ASTUtil.isTypeDef(declSpecifier));
                            variableInfo.setContent(declaration.getRawSignature());
                            variableInfo.setIsStructVariable(false);
                            if (this.inserter != null) {
                                variableInfo.createNode(inserter);
                            }
                            variableInfoList.add(variableInfo);
                            VariableUtil.VARIABLE_HASH_LIST[VariableUtil.hashVariable(variableName)].add(variableInfo);
                        }
                    }
                } else if (declSpecifier instanceof IASTElaboratedTypeSpecifier) {
                    // 不使用typedef名字进行声明的结构体变量
                    IASTElaboratedTypeSpecifier elaboratedTypeSpecifier = (IASTElaboratedTypeSpecifier) declSpecifier;
                    CVariableInfo variableInfo = new CVariableInfo();
                    variableInfo.setSpecifier(elaboratedTypeSpecifier);
                    variableInfo.setSimpleDeclaration(simpleDeclaration);
                    variableInfo.setBelongTo(fileName);
                    // typedef long long ll;
                    variableInfo.setIsDefine(ASTUtil.isTypeDef(declSpecifier));
                    String variableName = "";
                    for (IASTDeclarator declarator : simpleDeclaration.getDeclarators()) {
                        variableName = declarator.getName().toString();
                        variableInfo.setName(variableName);
                        if (declarator.getInitializer() != null) {
                            variableInfo.setEqualsInitializer(declarator.getInitializer());
                        }
                    }
                    variableInfo.setContent(declaration.getRawSignature());
                    variableInfo.setIsStructVariable(true);
                    if (variableInfo.getName() == null) {
                        variableInfo.setName("default_name");
                    }
                    if (this.inserter != null) {
                        variableInfo.createNode(inserter);
                    }
                    variableInfoList.add(variableInfo);
                    VariableUtil.VARIABLE_HASH_LIST[VariableUtil.hashVariable(variableName)].add(variableInfo);
                } else if (declSpecifier instanceof ICASTTypedefNameSpecifier) {
                    for (IASTNode node : declaration.getChildren()) {
                        if (node instanceof IASTFunctionDeclarator) {
                            CFunctionInfo functionInfo = new CFunctionInfo();
                            functionInfo.setFunctionDefinition(null);
                            functionInfo.setBelongTo(fileName);
                            functionInfo.setName(((IASTFunctionDeclarator) node).getName().toString());
                            functionInfo.setFullName(declaration.getRawSignature());
                            functionInfo.setBelongToName(fileName + ((IASTFunctionDeclarator) node).getName().toString());
                            functionInfo.setIsInline(false);
                            functionInfo.setIsDefine(true);
                            if (declSpecifier.getRawSignature().contains("const")) {
                                functionInfo.setIsConst(true);
                            } else {
                                functionInfo.setIsConst(false);
                            }
                            if (this.inserter != null) {
                                functionInfo.createNode(inserter);
                            }
                            functionInfoList.add(functionInfo);
                        } else if (node instanceof IASTDeclarator) {
                            // 使用typedef名字进行声明的结构体变量
                            ICASTTypedefNameSpecifier typedefNameSpecifier = (ICASTTypedefNameSpecifier) declSpecifier;
                            CVariableInfo variableInfo = new CVariableInfo();
                            variableInfo.setSpecifier(typedefNameSpecifier);
                            variableInfo.setSimpleDeclaration(simpleDeclaration);
                            variableInfo.setBelongTo(fileName);
                            // typedef long long ll;
                            variableInfo.setIsDefine(ASTUtil.isTypeDef(declSpecifier));
                            String variableName = "";
                            for (IASTDeclarator declarator : simpleDeclaration.getDeclarators()) {
                                variableName = declarator.getName().toString();
                                variableInfo.setName(variableName);
                                if (declarator.getInitializer() != null) {
                                    variableInfo.setEqualsInitializer(declarator.getInitializer());
                                }
                            }
                            variableInfo.setImpInvokeInfoMap(new HashMap<>());
                            variableInfo.setIsStructVariable(true);
                            variableInfo.setContent(declaration.getRawSignature());
                            variableInfo.setIsStructVariable(true);
                            if (this.inserter != null) {
                                variableInfo.createNode(inserter);
                            }
                            variableInfoList.add(variableInfo);
                            VariableUtil.VARIABLE_HASH_LIST[VariableUtil.hashVariable(variableName)].add(variableInfo);
                        }
                    }
                }
            }
        }
    }

    public void initIncludeCodeFiles() {
        IASTPreprocessorStatement[] ps = unit.getAllPreprocessorStatements();
        for (IASTPreprocessorStatement statement : ps) {
            if (statement instanceof IASTPreprocessorFunctionStyleMacroDefinition) {
                // 宏函数
                IASTPreprocessorFunctionStyleMacroDefinition macroDefinition
                        = (IASTPreprocessorFunctionStyleMacroDefinition) statement;
                CFunctionInfo functionInfo = new CFunctionInfo();
                functionInfo.setMacroDefinition(macroDefinition);
                functionInfo.setIsDefine(true);
                functionInfo.setName(macroDefinition.getName().toString());
                for (IASTFunctionStyleMacroParameter parameter : macroDefinition.getParameters()) {
                    functionInfo.getFullParams().add(parameter.toString());
                }
                functionInfo.setFullName("");
                functionInfo.setIsConst(false);
                functionInfo.setIsInline(true);
//                functionInfo.setContent(macroDefinition.getRawSignature());
                functionInfo.setBelongTo(fileName);
                functionInfo.setBelongToName(fileName + macroDefinition.getName().toString());
                if (this.inserter != null) {
                    functionInfo.createNode(inserter);
                }
                functionInfoList.add(functionInfo);
            }
            if (statement instanceof IASTPreprocessorIncludeStatement) {
                IASTPreprocessorIncludeStatement includeStatement = (IASTPreprocessorIncludeStatement) statement;
                if (!includeStatement.isSystemInclude()) {
//                    includeCodeFileList.add(fileName.substring(0, fileName.lastIndexOf('/') + 1) + includeStatement.getName().toString());
                    includeCodeFileList.add(includeStatement.getName().toString());
                }
            } else if (statement instanceof IASTPreprocessorMacroDefinition) {
                // 宏定义
                IASTPreprocessorMacroDefinition macroDefinition = (IASTPreprocessorMacroDefinition) statement;
                CVariableInfo variableInfo = new CVariableInfo();
                variableInfo.setName(macroDefinition.getName().toString());
                variableInfo.setIsDefine(true);
                variableInfo.setIsStructVariable(false);
                variableInfo.setBelongTo(fileName);
                variableInfo.setContent(macroDefinition.getExpansion());
                if (inserter != null) {
                    variableInfo.createNode(inserter);
                }
                variableInfoList.add(variableInfo);
                VariableUtil.VARIABLE_HASH_LIST[VariableUtil.hashVariable(macroDefinition.getName().toString())].add(variableInfo);
            }
        }
//        deDuplication(includeCodeFileList);
    }

    private <T> void deDuplication(List<T> list) {
        Set<T> set = new HashSet<>(list);
        list.clear();
        list.addAll(set);
    }

    private long createNode() {
        if (id != -1) {
            return id;
        }
        Map<String, Object> map = new HashMap<>();
        map.put(CExtractor.FILENAME, fileName);
        map.put(CExtractor.TAILFILENAME, tailFileName);
        id = inserter.createNode(map, CExtractor.c_code_file);
        return id;
    }
}
