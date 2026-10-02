package com.hdsl.core;

import java.util.regex.Pattern;

/** Converts package-manager activity to a phase description without inventing a percentage. */
final class TaskProgress {
    private static final String INSTALL_PREFIX="[安装进度] ";
    private static final Pattern PNPM=Pattern.compile("^Progress: resolved (\\d+), reused (\\d+), downloaded (\\d+), added (\\d+)(?:,.*)?$");
    private TaskProgress(){}
    static String describe(String line){
        String clean=line.replaceAll("\u001b\\[[0-9;]*m","").trim();
        if(clean.startsWith(INSTALL_PREFIX))return clean.substring(INSTALL_PREFIX.length());
        var counts=PNPM.matcher(clean);
        if(counts.matches())return "下载与安装依赖 · 已解析 "+counts.group(1)+"，复用 "+counts.group(2)+"，已下载 "+counts.group(3)+"，已写入 "+counts.group(4);
        return null;
    }
}
