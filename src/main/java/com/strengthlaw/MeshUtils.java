package com.strengthlaw;

import net.imagej.mesh.Mesh;
import net.imagej.mesh.Triangles;
import net.imagej.mesh.Vertices;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.logic.BitType;
import net.imglib2.view.Views;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;

public class MeshUtils {

    public static RandomAccessibleInterval<BitType> padSolid(final RandomAccessibleInterval<BitType> in) {
        long[] dims = new long[in.numDimensions()];
        in.dimensions(dims);
        long[] min = new long[]{-1,-1,-1};
        long[] max = new long[]{dims[0], dims[1], dims[2]};
        return Views.zeroMin(Views.interval(Views.extendValue(in, new BitType(false)), min, max));
    }

    public static double surfaceArea(final Mesh mesh, double sz, double sy, double sx) {
        double sum = 0.0;
        Triangles tris = mesh.triangles();
        Vertices verts = mesh.vertices();
        final long n = tris.size();
        for (long i = 0; i < n; i++) {
            long v0i = tris.vertex0(i), v1i = tris.vertex1(i), v2i = tris.vertex2(i);
            double x0 = verts.x(v0i) * sx, y0 = verts.y(v0i) * sy, z0 = verts.z(v0i) * sz;
            double x1 = verts.x(v1i) * sx, y1 = verts.y(v1i) * sy, z1 = verts.z(v1i) * sz;
            double x2 = verts.x(v2i) * sx, y2 = verts.y(v2i) * sy, z2 = verts.z(v2i) * sz;
            double ax = x1 - x0, ay = y1 - y0, az = z1 - z0;
            double bx = x2 - x0, by = y2 - y0, bz = z2 - z0;
            double cx = ay*bz - az*by;
            double cy = az*bx - ax*bz;
            double cz = ax*by - ay*bx;
            sum += 0.5 * Math.sqrt(cx*cx + cy*cy + cz*cz);
        }
        return sum;
    }

    public static double integratedMeanCurvature(final Mesh mesh, double sz, double sy, double sx) {
        final Triangles tris = mesh.triangles();
        final Vertices  verts = mesh.vertices();
        final long fN = tris.size();

        HashMap<Long, long[]> edgeFaces = new HashMap<>();
        for (long i = 0; i < fN; i++) {
            int a = (int) tris.vertex0(i), b = (int) tris.vertex1(i), c = (int) tris.vertex2(i);
            addEdge(edgeFaces, a, b, i);
            addEdge(edgeFaces, b, c, i);
            addEdge(edgeFaces, c, a, i);
        }

        double sum = 0.0;
        for (Long key : edgeFaces.keySet()) {
            int[] ab = unpack(key);
            long[] fl = edgeFaces.get(key);

            double xa = verts.x(ab[0]) * sx, ya = verts.y(ab[0]) * sy, za = verts.z(ab[0]) * sz;
            double xb = verts.x(ab[1]) * sx, yb = verts.y(ab[1]) * sy, zb = verts.z(ab[1]) * sz;
            double L = Math.sqrt((xa - xb) * (xa - xb) + (ya - yb) * (ya - yb) + (za - zb) * (za - zb));
            if (L == 0) continue;

            if (fl[1] >= 0) {
                double[] n1 = triNormalScaled(tris, verts, fl[0], sx, sy, sz);
                double[] n2 = triNormalScaled(tris, verts, fl[1], sx, sy, sz);
                double cos = clamp(n1[0]*n2[0] + n1[1]*n2[1] + n1[2]*n2[2], -1.0, 1.0);
                double theta = Math.acos(cos);
                double phi = Math.PI - theta;
                sum += L * phi;
            } else {
                sum += L * (Math.PI * 0.5);
            }
        }
        return 0.5 * sum;
    }

    private static double[] triNormalScaled(final Triangles tris, final Vertices verts, final long fi,
                                            final double sx, final double sy, final double sz) {
        long v0i = tris.vertex0(fi), v1i = tris.vertex1(fi), v2i = tris.vertex2(fi);
        double x0 = verts.x(v0i) * sx, y0 = verts.y(v0i) * sy, z0 = verts.z(v0i) * sz;
        double x1 = verts.x(v1i) * sx, y1 = verts.y(v1i) * sy, z1 = verts.z(v1i) * sz;
        double x2 = verts.x(v2i) * sx, y2 = verts.y(v2i) * sy, z2 = verts.z(v2i) * sz;
        return triNormal(x0, y0, z0, x1, y1, z1, x2, y2, z2);
    }

    public static double eulerCharacteristic(final Mesh mesh) {
        Triangles tris = mesh.triangles();
        Vertices verts = mesh.vertices();
        int Vcount = (int) verts.size();
        int Fcount = (int) tris.size();
        HashSet<Long> edges = new HashSet<>();
        final long n = tris.size();
        for (long i = 0; i < n; i++) {
            int a = (int) tris.vertex0(i), b = (int) tris.vertex1(i), c = (int) tris.vertex2(i);
            addEdge(edges, a, b);
            addEdge(edges, b, c);
            addEdge(edges, c, a);
        }
        int Ecount = edges.size();
        return (double) Vcount - (double) Ecount + (double) Fcount;
    }

    public static void saveMeshAsSTLAscii(
            Path path, Mesh mesh, String name,
            StrengthLawExtractor.ProgressWindow pw, int startPct, int endPct,
            double sz, double sy, double sx) throws IOException {

        Triangles tris = mesh.triangles();
        Vertices verts = mesh.vertices();
        long n = tris.size();
        try (BufferedWriter w = Files.newBufferedWriter(path)) {
            w.write("solid " + (name == null ? "mesh" : name) + "\n");
            for (long i = 0; i < n; i++) {
                long v0i = tris.vertex0(i), v1i = tris.vertex1(i), v2i = tris.vertex2(i);
                double x0 = verts.x(v0i)*sx, y0 = verts.y(v0i)*sy, z0 = verts.z(v0i)*sz;
                double x1 = verts.x(v1i)*sx, y1 = verts.y(v1i)*sy, z1 = verts.z(v1i)*sz;
                double x2 = verts.x(v2i)*sx, y2 = verts.y(v2i)*sy, z2 = verts.z(v2i)*sz;
                double[] nrm = triNormal(x0,y0,z0,x1,y1,z1,x2,y2,z2);
                w.write(String.format("  facet normal %.7e %.7e %.7e%n", nrm[0], nrm[1], nrm[2]));
                w.write("    outer loop\n");
                w.write(String.format("      vertex %.7e %.7e %.7e%n", x0,y0,z0));
                w.write(String.format("      vertex %.7e %.7e %.7e%n", x1,y1,z1));
                w.write(String.format("      vertex %.7e %.7e %.7e%n", x2,y2,z2));
                w.write("    endloop\n  endfacet\n");
                if (pw != null && n > 0) {
                    int pct = startPct + (int) ((i + 1) * (endPct - startPct) / (double) n);
                    pw.set(Math.min(100, pct), "Writing STL");
                }
            }
            w.write("endsolid " + (name == null ? "mesh" : name) + "\n");
        }
        if (pw != null) pw.set(endPct, "STL done");
    }

    public static final class VoxelCounts {
        public long n3;
        public long n2x, n2y, n2z;
        public long n1xy, n1yz, n1zx;
        public long n0;
    }

    public static VoxelCounts countVoxelConfigurations(final RandomAccessibleInterval<BitType> bin) {
        final long[] d = new long[3];
        bin.dimensions(d);
        final long NX = d[0], NY = d[1], NZ = d[2];
        final net.imglib2.RandomAccess<BitType> ra = bin.randomAccess();

        VoxelCounts c = new VoxelCounts();

        for (long z = 0; z < NZ; z++) {
            for (long y = 0; y < NY; y++) {
                for (long x = 0; x < NX; x++) {
                    if (!get(ra, x, y, z)) continue;
                    c.n3++;

                    if (x+1 < NX && get(ra, x+1, y, z)) c.n2x++;
                    if (y+1 < NY && get(ra, x, y+1, z)) c.n2y++;
                    if (z+1 < NZ && get(ra, x, y, z+1)) c.n2z++;

                    if (x+1 < NX && y+1 < NY) {
                        if (get(ra, x+1, y, z) && get(ra, x, y+1, z) && get(ra, x+1, y+1, z)) c.n1xy++;
                    }
                    if (y+1 < NY && z+1 < NZ) {
                        if (get(ra, x, y+1, z) && get(ra, x, y, z+1) && get(ra, x, y+1, z+1)) c.n1yz++;
                    }
                    if (z+1 < NZ && x+1 < NX) {
                        if (get(ra, x, y, z+1) && get(ra, x+1, y, z) && get(ra, x+1, y, z+1)) c.n1zx++;
                    }

                    if (x+1 < NX && y+1 < NY && z+1 < NZ) {
                        if (get(ra, x+1, y, z) && get(ra, x, y+1, z) && get(ra, x, y, z+1) &&
                            get(ra, x+1, y+1, z) && get(ra, x+1, y, z+1) && get(ra, x, y+1, z+1) &&
                            get(ra, x+1, y+1, z+1)) c.n0++;
                    }
                }
            }
        }
        return c;
    }

    private static boolean get(net.imglib2.RandomAccess<BitType> ra, long x, long y, long z) {
        ra.setPosition(new long[]{x,y,z});
        return ra.get().get();
    }

    public static double voxelSurfaceAreaFromCounts(VoxelCounts c, double vx, double vy, double vz) {
        if (vx == vy && vy == vz) {
            long n2 = c.n2x + c.n2y + c.n2z;
            long Sunit = 6L * c.n3 - 2L * n2;
            double s2 = vx * vx;
            return Sunit * s2;
        } else {
            double Ax = vy * vz, Ay = vx * vz, Az = vx * vy;
            long exposedX = 2L * c.n3 - 2L * c.n2x;
            long exposedY = 2L * c.n3 - 2L * c.n2y;
            long exposedZ = 2L * c.n3 - 2L * c.n2z;
            return exposedX * Ax + exposedY * Ay + exposedZ * Az;
        }
    }

    public static double voxelIntegratedMeanCurvatureFromCounts(VoxelCounts c, double vx, double vy, double vz) {
        if (vx == vy && vy == vz) {
            long n2 = c.n2x + c.n2y + c.n2z;
            long n1 = c.n1xy + c.n1yz + c.n1zx;
            double s = vx;
            return Math.PI * (3L * c.n3 - 2L * n2 + n1) * s;
        } else {
            double Lx = vx, Ly = vy, Lz = vz;
            double totalEdgeLen = c.n1yz * Lx + c.n1zx * Ly + c.n1xy * Lz;
            return 0.5 * Math.PI * totalEdgeLen;
        }
    }

    public static long voxelEulerFromCounts(VoxelCounts c) {
        long n2 = c.n2x + c.n2y + c.n2z;
        long n1 = c.n1xy + c.n1yz + c.n1zx;
        return c.n3 - n2 + n1 - c.n0;
    }

    public static void saveMeshWireframeOBJ(Path path, Mesh mesh, double sz, double sy, double sx,
                                            String name, StrengthLawExtractor.ProgressWindow pw,
                                            int startPct, int endPct) throws IOException {
        Triangles tris = mesh.triangles();
        Vertices verts = mesh.vertices();
        long fN = tris.size();

        try (BufferedWriter w = Files.newBufferedWriter(path)) {
            w.write("# OBJ wireframe " + (name == null ? "mesh" : name) + "\n");

            for (int vi = 0, n = (int) verts.size(); vi < n; vi++) {
                double x = verts.x(vi) * sx, y = verts.y(vi) * sy, z = verts.z(vi) * sz;
                w.write(String.format(java.util.Locale.ROOT, "v %.7f %.7f %.7f%n", x, y, z));
            }

            java.util.HashSet<Long> edges = new java.util.HashSet<>();
            for (long fi = 0; fi < fN; fi++) {
                int a = (int) tris.vertex0(fi), b = (int) tris.vertex1(fi), c = (int) tris.vertex2(fi);
                addEdge(edges, a, b); addEdge(edges, b, c); addEdge(edges, c, a);
                if (pw != null && (fi & 0xFFFF) == 0) {
                    int pct = startPct + (int) ((fi + 1) * (endPct - startPct) / (double) fN);
                    pw.set(Math.min(100, pct), "Collecting edges");
                }
            }

            int count = 0, total = edges.size();
            for (Long key : edges) {
                int[] ab = unpack(key);
                w.write("l " + (ab[0] + 1) + " " + (ab[1] + 1) + "\n");
                if (pw != null && (++count & 0x3FFFF) == 0) {
                    int pct = startPct + (int) (count * 1.0 * (endPct - startPct) / total);
                    pw.set(Math.min(100, pct), "Writing edges");
                }
            }
        }
        if (pw != null) pw.set(endPct, "OBJ wireframe (mesh) done");
    }

    public static void saveVoxelBoundaryWireframeOBJ(Path path,
            RandomAccessibleInterval<BitType> bin,
            double sz, double sy, double sx,
            StrengthLawExtractor.ProgressWindow pw, int startPct, int endPct) throws IOException {

        final long[] dims = new long[3];
        bin.dimensions(dims);
        final long NX = dims[0], NY = dims[1], NZ = dims[2];
        final net.imglib2.RandomAccess<BitType> ra = bin.randomAccess();

        try (BufferedWriter w = Files.newBufferedWriter(path)) {
            w.write("# OBJ wireframe from voxel boundary\n");

            for (long z = 0; z < NZ; z++) {
                for (long y = 0; y < NY; y++) {
                    for (long x = 0; x < NX; x++) {
                        if (!getAt(ra, x, y, z)) continue;

                        if (x + 1 >= NX || !getAt(ra, x + 1, y, z))
                            writeRectEdgesOBJ(w, (x + 1) * sx, y * sy, z * sz, (x + 1) * sx, (y + 1) * sy, (z + 1) * sz, 'x');
                        if (x == 0 || !getAt(ra, x - 1, y, z))
                            writeRectEdgesOBJ(w, x * sx, y * sy, z * sz, x * sx, (y + 1) * sy, (z + 1) * sz, 'x');
                        if (y + 1 >= NY || !getAt(ra, x, y + 1, z))
                            writeRectEdgesOBJ(w, x * sx, (y + 1) * sy, z * sz, (x + 1) * sx, (y + 1) * sy, (z + 1) * sz, 'y');
                        if (y == 0 || !getAt(ra, x, y - 1, z))
                            writeRectEdgesOBJ(w, x * sx, y * sy, z * sz, (x + 1) * sx, y * sy, (z + 1) * sz, 'y');
                        if (z + 1 >= NZ || !getAt(ra, x, y, z + 1))
                            writeRectEdgesOBJ(w, x * sx, y * sy, (z + 1) * sz, (x + 1) * sx, (y + 1) * sy, (z + 1) * sz, 'z');
                        if (z == 0 || !getAt(ra, x, y, z - 1))
                            writeRectEdgesOBJ(w, x * sx, y * sy, z * sz, (x + 1) * sx, (y + 1) * sy, z * sz, 'z');
                    }
                }
                if (pw != null) {
                    int pct = startPct + (int) ((z + 1) * 1.0 * (endPct - startPct) / NZ);
                    pw.set(Math.min(100, pct), "Voxel wireframe z=" + (z + 1) + "/" + NZ);
                }
            }
        }
        if (pw != null) pw.set(endPct, "OBJ wireframe (voxel) done");
    }

    private static boolean getAt(net.imglib2.RandomAccess<BitType> ra, long x, long y, long z) {
        ra.setPosition(new long[]{x, y, z});
        return ra.get().get();
    }

    private static void writeRectEdgesOBJ(BufferedWriter w,
            double x0, double y0, double z0,
            double x1, double y1, double z1,
            char axis) throws IOException {

        double[][] pts = new double[4][3];
        if (axis == 'x') {
            pts[0] = new double[]{x0, y0, z0};
            pts[1] = new double[]{x0, y1, z0};
            pts[2] = new double[]{x0, y1, z1};
            pts[3] = new double[]{x0, y0, z1};
        } else if (axis == 'y') {
            pts[0] = new double[]{x0, y0, z0};
            pts[1] = new double[]{x1, y0, z0};
            pts[2] = new double[]{x1, y0, z1};
            pts[3] = new double[]{x0, y0, z1};
        } else {
            pts[0] = new double[]{x0, y0, z0};
            pts[1] = new double[]{x1, y0, z0};
            pts[2] = new double[]{x1, y1, z0};
            pts[3] = new double[]{x0, y1, z0};
        }

        for (int i = 0; i < 4; i++) {
            double[] a = pts[i], b = pts[(i + 1) % 4];
            w.write(String.format(java.util.Locale.ROOT, "v %.7f %.7f %.7f%n", a[0], a[1], a[2]));
            w.write(String.format(java.util.Locale.ROOT, "v %.7f %.7f %.7f%n", b[0], b[1], b[2]));
            w.write("l -2 -1\n");
        }
    }

    private static void addEdge(HashMap<Long,long[]> map, int a, int b, long faceIdx) {
        int i = Math.min(a,b), j = Math.max(a,b);
        long key = (((long)i) << 32) | (j & 0xffffffffL);
        long[] arr = map.get(key);
        if (arr == null) map.put(key, new long[]{faceIdx, -1});
        else if (arr[1] < 0) arr[1] = faceIdx;
    }

    private static void addEdge(HashSet<Long> set, int a, int b){
        int i=Math.min(a,b), j=Math.max(a,b);
        long key = (((long)i) << 32) | (j & 0xffffffffL);
        set.add(key);
    }

    private static int[] unpack(long key){
        int i = (int)(key >> 32);
        int j = (int)(key & 0xffffffffL);
        return new int[]{i,j};
    }

    private static double[] triNormal(double x0,double y0,double z0,double x1,double y1,double z1,double x2,double y2,double z2){
        double ax=x1-x0, ay=y1-y0, az=z1-z0;
        double bx=x2-x0, by=y2-y0, bz=z2-z0;
        double nx=ay*bz-az*by, ny=az*bx-ax*bz, nz=ax*by-ay*bx;
        double len = Math.sqrt(nx*nx+ny*ny+nz*nz);
        if (len>0){ nx/=len; ny/=len; nz/=len; }
        return new double[]{nx,ny,nz};
    }

    private static double clamp(double v, double lo, double hi){ return Math.max(lo, Math.min(hi, v)); }
}
