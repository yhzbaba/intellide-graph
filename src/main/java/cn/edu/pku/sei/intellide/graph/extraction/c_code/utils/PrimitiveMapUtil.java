package cn.edu.pku.sei.intellide.graph.extraction.c_code.utils;

import cn.edu.pku.sei.intellide.graph.extraction.c_code.infos.PrimitiveClass;

import java.util.HashMap;
import java.util.Map;

public class PrimitiveMapUtil {
    public static Map<String, PrimitiveClass> primitiveClassMap;

    public static void init() {
        primitiveClassMap = new HashMap<>();
    }

    public static void insert(String typedefName, PrimitiveClass primitiveClass) {
        primitiveClassMap.put(typedefName, primitiveClass);
    }

    public static PrimitiveClass query(String typedefName) {
        return primitiveClassMap.get(typedefName);
    }
}
