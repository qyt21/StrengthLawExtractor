package com.strengthlaw;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

public final class MeshIO {

    public static java.io.File ensureExt(java.io.File f, String extNoDot) {
        if (f == null) return null;
        String p = f.getPath();
        String dotExt = "." + extNoDot.toLowerCase();
        if (!p.toLowerCase().endsWith(dotExt)) {
            p = p + dotExt;
            f = new java.io.File(p);
        }
        java.io.File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        return f;
    }

    public static void writeCSV(java.io.File outFile, LinkedHashMap<String, Double> metrics) throws IOException {
        outFile = ensureExt(outFile, "csv");
        Path p = outFile.toPath();
        java.io.File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (BufferedWriter w = Files.newBufferedWriter(p)) {
            w.write("Metric,Value\n");
            for (String k : metrics.keySet()) {
                Double v = metrics.get(k);
                w.write(k.replace(',', ' ') + "," + (v == null ? "" : v.toString()));
                w.write("\n");
            }
        }
    }

    private MeshIO() {}
}
