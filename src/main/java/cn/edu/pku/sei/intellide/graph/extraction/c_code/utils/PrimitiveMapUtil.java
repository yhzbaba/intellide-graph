package cn.edu.pku.sei.intellide.graph.extraction.c_code.utils;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.supportEntity.PrimitiveClass;

import java.util.HashMap;
import java.util.Map;

public class PrimitiveMapUtil {
    public static Map<String, PrimitiveClass> primitiveClassMap;

    public static void init() {
        primitiveClassMap = new HashMap<>();
        insert("void", new PrimitiveClass());
        insert("int", new PrimitiveClass());
        insert("float", new PrimitiveClass());
        insert("double", new PrimitiveClass());
        insert("char", new PrimitiveClass());
        insert("long", new PrimitiveClass());
        insert("short", new PrimitiveClass());
    }

    public static void insert(String typedefName, PrimitiveClass primitiveClass) {
        primitiveClassMap.put(typedefName, primitiveClass);
    }

    public static PrimitiveClass query(String typedefName) {
        return primitiveClassMap.get(typedefName);
    }
}
