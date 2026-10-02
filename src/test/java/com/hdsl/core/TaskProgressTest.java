package com.hdsl.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskProgressTest {
    @Test void reportsCountsWithoutTreatingResolvedAsDownloadTotal(){
        assertEquals("下载与安装依赖 · 已解析 600，复用 0，已下载 528，已写入 528",TaskProgress.describe("Progress: resolved 600, reused 0, downloaded 528, added 528"));
        assertEquals("下载与安装依赖 · 已解析 600，复用 528，已下载 0，已写入 528",TaskProgress.describe("Progress: resolved 600, reused 528, downloaded 0, added 528, done"));
    }
    @Test void onlyExplicitProgressCanReplaceTaskStatus(){
        assertEquals("核对发行依赖：已核对 12 项",TaskProgress.describe("[安装进度] 核对发行依赖：已核对 12 项"));
        assertNull(TaskProgress.describe("WARN Tarball download average speed 7 KiB/s"));
        assertNull(TaskProgress.describe("dsh web: http://127.0.0.1:3080/"));
    }
}
