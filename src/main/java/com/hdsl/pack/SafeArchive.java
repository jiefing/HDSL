package com.hdsl.pack;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.CRC32;

/** Portable path rules intentionally also apply when importing on Linux. */
final class SafeArchive implements AutoCloseable {
    static final long MAX_ARCHIVE = 2L << 30, MAX_UNPACKED = 6L << 30;
    static final long MAX_FILE = 2L << 30, MAX_METADATA = 4L << 20;
    static final int MAX_ENTRIES = 400_000;
    final ZipFile zip;
    final LinkedHashMap<String, ZipArchiveEntry> entries = new LinkedHashMap<>();
    long total;

    SafeArchive(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_ARCHIVE)
            throw new IOException("整合包不是普通文件或超过 2 GiB 上限");
        zip = ZipFile.builder().setPath(path).get();
        try {
            var seen = new HashSet<String>();
            var casing = new HashMap<String, String>();
            var files = new HashSet<String>();
            var directories = new HashSet<String>();
            var iter = zip.getEntries();
            int count = 0;
            while (iter.hasMoreElements()) {
                var entry = iter.nextElement();
                if (++count > MAX_ENTRIES) throw new IOException("归档条目超过上限");
                // Commons Compress normalizes DOS backslashes in getName(); inspect the original bytes too.
                byte[] rawName = entry.getRawName();
                if (rawName != null) for (byte b : rawName) if (b == '\\') throw new IOException("ZIP 原始路径包含反斜杠");
                String name = safe(entry.getName(), entry.isDirectory());
                String key = key(name);
                if (!seen.add(key)) throw new IOException("归档路径重复或大小写冲突: " + name);
                if (entry.isUnixSymlink() || (entry.getUnixMode() != 0 &&
                        (entry.getUnixMode() & 0170000) != 0 &&
                        (entry.getUnixMode() & 0170000) != (entry.isDirectory() ? 0040000 : 0100000)))
                    throw new IOException("ZIP 内不允许符号链接或特殊文件: " + name);
                if (!zip.canReadEntryData(entry)) throw new IOException("不支持的 ZIP 压缩或加密: " + name);
                String[] parts = name.split("/");
                String current = "";
                for (String part : parts) {
                    current += (current.isEmpty() ? "" : "/") + part;
                    String old = casing.putIfAbsent(key(current), current);
                    if (old != null && !old.equals(current)) throw new IOException("大小写或 Unicode 路径冲突: " + name);
                    if (!current.equals(name) && files.contains(key(current))) throw new IOException("文件与目录冲突: " + name);
                    if (!current.equals(name)) directories.add(key(current));
                }
                if (!entry.isDirectory()) {
                    if (directories.contains(key))
                        throw new IOException("文件与目录冲突: " + name);
                    files.add(key);
                    long size = entry.getSize(), compressed = entry.getCompressedSize();
                    if (size < 0 || size > MAX_FILE || compressed < 0 || (size > 1L << 20 && size / Math.max(1, compressed) > 1000))
                        throw new IOException("归档文件大小或压缩率超过限制: " + name);
                    total = Math.addExact(total, size);
                    if (total > MAX_UNPACKED) throw new IOException("归档展开体积超过 6 GiB");
                } else directories.add(key);
                entries.put(name, entry);
            }
        } catch (Exception error) {
            zip.close();
            if (error instanceof IOException io) throw io;
            throw new IOException("归档索引损坏", error);
        }
    }

    static String key(String s) { return Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
    static String safe(String raw, boolean directory) throws IOException {
        if (raw == null || raw.isEmpty() || raw.length() > 1000 || raw.contains("\\") || raw.startsWith("/"))
            throw new IOException("非法归档路径: " + raw);
        String value = directory && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        for (String part : value.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ") ||
                    part.matches(".*[\\x00-\\x1f<>:\"|?*].*") ||
                    part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))
                throw new IOException("非法或不可移植的路径: " + raw);
        }
        return value;
    }

    byte[] bytes(String name, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(name, out, limit);
        return out.toByteArray();
    }

    void copy(String name, OutputStream out, long limit) throws IOException {
        ZipArchiveEntry e = entries.get(name);
        if (e == null || e.isDirectory()) throw new IOException("归档文件不存在: " + name);
        if (e.getSize() > limit) throw new IOException("文件超过读取上限: " + name);
        var crc = new CRC32();
        long count = 0;
        try (InputStream in = zip.getInputStream(e)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                interrupted();
                count += n;
                if (count > limit || count > e.getSize()) throw new IOException("ZIP 实际体积超过声明: " + name);
                crc.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
        }
        if (count != e.getSize() || crc.getValue() != e.getCrc()) throw new IOException("ZIP 大小或 CRC 校验失败: " + name);
    }

    static Path target(Path root, String rel) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(safe(rel, false)).normalize();
        if (!target.startsWith(base)) throw new IOException("路径越过目标目录");
        noLinks(base);
        noLinks(target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("目标不是普通文件: " + target);
        return target;
    }

    static void noLinks(Path path) throws IOException {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                var a = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isSymbolicLink() || a.isOther()) throw new IOException("目标路径包含链接或 Windows junction: " + current);
                // toRealPath resolves Windows reparse points even when their basic attributes look like directories.
                if (!current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS)))
                    throw new IOException("目标路径包含重解析点: " + current);
            }
            current = current.getParent();
        }
    }

    static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("整合包操作已取消");
    }
    static String sha(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] b = new byte[64 * 1024]; int n;
            while ((n = in.read(b)) != -1) { interrupted(); digest.update(b, 0, n); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    @Override public void close() throws IOException { zip.close(); }
}
