package com.hdsl.pack;

import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import java.io.*;
import java.util.*;

/** Validate npm tarballs before passing them to a package manager. No package code is executed. */
final class TarSafety {
    static long inspect(SafeArchive archive, String path) throws Exception {
        try (InputStream raw = archive.zip.getInputStream(archive.entries.get(path));
             var gzip = GzipCompressorInputStream.builder().setInputStream(raw).setDecompressConcatenated(true).get();
             var bounded = new ExpandedLimit(gzip);
             var tar = new TarArchiveInputStream(bounded) {
                 @Override protected byte[] readRecord() throws IOException {
                     byte[] header = super.readRecord();
                     // PAX/GNU metadata is consumed internally by getNextEntry; bound it before allocation.
                     if (header != null && header.length >= 512) {
                         byte type = header[156];
                         if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                             try {
                                 long size = TarUtils.parseOctalOrBinary(header, 124, 12);
                                 if (size < 0 || size > SafeArchive.MAX_METADATA) throw new IOException("tar 元数据超过上限");
                             } catch (IllegalArgumentException error) { throw new IOException("tar 元数据大小损坏", error); }
                         }
                     }
                     return header;
                 }
             }) {
            long total = 0; int count = 0; boolean packageJson = false;
            Map<String, String> casePaths = new HashMap<>(); Set<String> seen = new HashSet<>(), files = new HashSet<>(), dirs = new HashSet<>();
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                SafeArchive.interrupted();
                String name = SafeArchive.safe(entry.getName(), entry.isDirectory());
                if (!(name.equals("package") && entry.isDirectory()) && !name.startsWith("package/")) throw new IOException("npm tarball 文件必须位于 package/ 下: " + path);
                if (!entry.isCheckSumOK() || entry.isSparse() || entry.isSymbolicLink() || entry.isLink() || (!entry.isFile() && !entry.isDirectory())) throw new IOException("tarball 含链接、特殊文件或损坏头: " + path);
                if (++count > SafeArchive.MAX_ENTRIES || entry.getSize() < 0 || entry.getSize() > SafeArchive.MAX_FILE || (total += entry.getSize()) > SafeArchive.MAX_UNPACKED) throw new IOException("tarball 展开超出限制: " + path);
                String key = SafeArchive.key(name); if (!seen.add(key)) throw new IOException("tarball 路径重复: " + path);
                String current = "";
                for (String part : name.split("/")) {
                    current += (current.isEmpty() ? "" : "/") + part;
                    String old = casePaths.putIfAbsent(SafeArchive.key(current), current);
                    if (old != null && !old.equals(current)) throw new IOException("tarball 大小写路径冲突: " + path);
                    if (!current.equals(name)) {
                        if (files.contains(SafeArchive.key(current))) throw new IOException("tarball 文件/目录冲突: " + path);
                        dirs.add(SafeArchive.key(current));
                    }
                }
                if (entry.isDirectory()) dirs.add(key);
                else {
                    if (dirs.contains(key)) throw new IOException("tarball 文件/目录冲突: " + path);
                    files.add(key);
                    if (name.equals("package/package.json")) {
                        if (entry.getSize() > SafeArchive.MAX_METADATA) throw new IOException("tarball package.json 太大");
                        packageJson = true;
                    }
                }
                // Consume the body here so truncation and expanded-byte limits are checked immediately.
                byte[] buffer = new byte[64 * 1024]; while (tar.read(buffer) != -1) SafeArchive.interrupted();
            }
            byte[] remainder = new byte[8192]; int trailing;
            while ((trailing = bounded.read(remainder)) != -1) {
                SafeArchive.interrupted();
                for (int i = 0; i < trailing; i++) if (remainder[i] != 0) throw new IOException("tarball 结束标记后含额外内容: " + path);
            }
            if (!packageJson) throw new IOException("npm tarball 缺少 package/package.json: " + path);
            return total;
        }
    }
    private static final class ExpandedLimit extends FilterInputStream {
        long total;
        ExpandedLimit(InputStream input) { super(input); }
        private void add(long n) throws IOException { if (n > 0 && (total += n) > SafeArchive.MAX_UNPACKED) throw new IOException("tarball 解压流超过 6 GiB"); }
        @Override public int read() throws IOException { int value = in.read(); if (value != -1) add(1); return value; }
        @Override public int read(byte[] b, int off, int len) throws IOException { int n = in.read(b, off, len); add(n); return n; }
        @Override public long skip(long count) throws IOException {
            long total = 0; byte[] b = new byte[8192];
            while (total < count) { int n = read(b, 0, (int)Math.min(b.length, count - total)); if (n == -1) break; total += n; }
            return total;
        }
    }
}
