/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from Youer by YouerMC
 * (https://github.com/MohistMC/Youer), licensed under GPL-3.0.
 * Original code Copyright (c) YouerMC.
 */

package io.izzel.arclight.common.compat.prts.feature;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.command.CommandSender;

/** 线程 CPU 耗时剖析（移植自 Youer YouerThreadCost，已去 Youer 化）。 */
public class PRTSThreadCost {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ThreadCost");
    private static final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    static {
        threadMXBean.setThreadCpuTimeEnabled(true);
        if (threadMXBean.isThreadContentionMonitoringSupported()) {
            threadMXBean.setThreadContentionMonitoringEnabled(true);
        }
    }

    public static void dumpThreadCpuTime(CommandSender sender) {
        try {
            List<ThreadCpuTime> list = new ArrayList<>();
            long[] ids = threadMXBean.getAllThreadIds();
            for (long id : ids) {
                ThreadInfo threadInfo = threadMXBean.getThreadInfo(id, 20);
                if (threadInfo != null) {
                    ThreadCpuTime item = new ThreadCpuTime();
                    item.cpuTime = threadMXBean.getThreadCpuTime(id) / 1000000;
                    item.userTime = threadMXBean.getThreadUserTime(id) / 1000000;
                    item.name = threadInfo.getThreadName();
                    item.id = id;
                    item.state = threadInfo.getThreadState().toString();
                    item.blockedTime = threadInfo.getBlockedTime();
                    item.waitedTime = threadInfo.getWaitedTime();
                    item.blockedCount = threadInfo.getBlockedCount();
                    item.waitedCount = threadInfo.getWaitedCount();
                    StringBuilder stackTrace = new StringBuilder();
                    for (StackTraceElement element : threadInfo.getStackTrace()) {
                        stackTrace.append("  at ").append(element.toString()).append("\n");
                    }
                    item.stackTrace = stackTrace.toString();
                    if (threadInfo.getLockInfo() != null) {
                        item.lockInfo = threadInfo.getLockInfo().toString();
                        item.lockOwnerId = threadInfo.getLockOwnerId();
                        if (threadInfo.getLockOwnerId() != -1) {
                            ThreadInfo lockOwnerInfo = threadMXBean.getThreadInfo(threadInfo.getLockOwnerId());
                            if (lockOwnerInfo != null) {
                                item.lockOwnerName = lockOwnerInfo.getThreadName();
                            }
                        }
                    }
                    list.add(item);
                }
            }
            list.sort(Comparator.comparingLong(i -> -i.cpuTime));

            ThreadCostReport report = new ThreadCostReport();
            report.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            report.threads = list;
            report.totalThreads = list.size();
            report.totalCpuTime = list.stream().mapToLong(t -> t.cpuTime).sum();
            report.totalUserTime = list.stream().mapToLong(t -> t.userTime).sum();

            Path exportDir = Paths.get("thread-dumps");
            Files.createDirectories(exportDir);
            String fileName = "thread-cost-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")) + ".json";
            Path filePath = exportDir.resolve(fileName);
            try (FileWriter writer = new FileWriter(filePath.toFile())) {
                writer.write(report.toJson());
            }
            sender.sendMessage("§a[PRTS] 线程 CPU 剖析已导出: " + filePath.toAbsolutePath());
        } catch (IOException e) {
            sender.sendMessage("§c[PRTS] 线程剖析导出失败: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.error("[PRTS-ThreadCost] dump error", e);
        }
    }

    public static class ThreadCpuTime {
        public long id;
        public long cpuTime;
        public long userTime;
        public String name;
        public String state;
        public long blockedTime;
        public long waitedTime;
        public long blockedCount;
        public long waitedCount;
        public String stackTrace;
        public String lockInfo;
        public long lockOwnerId = -1;
        public String lockOwnerName;
    }

    public static class ThreadCostReport {
        public String timestamp;
        public int totalThreads;
        public long totalCpuTime;
        public long totalUserTime;
        public List<ThreadCpuTime> threads;

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"timestamp\": ").append(jsonStr(timestamp)).append(",\n");
            sb.append("  \"totalThreads\": ").append(totalThreads).append(",\n");
            sb.append("  \"totalCpuTimeMs\": ").append(totalCpuTime).append(",\n");
            sb.append("  \"totalUserTimeMs\": ").append(totalUserTime).append(",\n");
            sb.append("  \"threads\": [\n");
            for (int i = 0; i < threads.size(); i++) {
                ThreadCpuTime t = threads.get(i);
                sb.append("    {\n");
                sb.append("      \"id\": ").append(t.id).append(",\n");
                sb.append("      \"name\": ").append(jsonStr(t.name)).append(",\n");
                sb.append("      \"cpuTimeMs\": ").append(t.cpuTime).append(",\n");
                sb.append("      \"userTimeMs\": ").append(t.userTime).append(",\n");
                sb.append("      \"state\": ").append(jsonStr(t.state)).append(",\n");
                sb.append("      \"blockedTimeMs\": ").append(t.blockedTime).append(",\n");
                sb.append("      \"waitedTimeMs\": ").append(t.waitedTime).append(",\n");
                sb.append("      \"blockedCount\": ").append(t.blockedCount).append(",\n");
                sb.append("      \"waitedCount\": ").append(t.waitedCount).append(",\n");
                sb.append("      \"lockInfo\": ").append(jsonStr(t.lockInfo)).append(",\n");
                sb.append("      \"lockOwnerId\": ").append(t.lockOwnerId).append(",\n");
                sb.append("      \"lockOwnerName\": ").append(jsonStr(t.lockOwnerName)).append(",\n");
                sb.append("      \"stackTrace\": ").append(jsonStr(t.stackTrace)).append("\n");
                sb.append("    }");
                if (i < threads.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("  ]\n");
            sb.append("}\n");
            return sb.toString();
        }
    }

    private static String jsonStr(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
