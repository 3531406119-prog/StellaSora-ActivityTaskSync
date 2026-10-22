import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * StellaSora 本地服（Nebula）活动任务进度同步工具。
 *
 * 问题：模拟器只创建了 ActivityTask 的任务条目、从不写 curProgress，也没有 ActivityTaskRewardReceive 处理器，
 *      所以活动关卡打通了、活动任务永远停在 0。
 * 本工具不"一键全通"，而是按你真实的通关记录结算进度：
 *   - CompleteCond 149（单局得分类）：进度 = 通关记录里最高的 build 分数（上限为任务目标）
 *   - CompleteCond 150（通关指定关卡）：该关有通关记录 → 1
 *   - CompleteCond 110（指定关卡 3 星）：该关星级=3 → 1
 *   - 其它条件（例如"等主线更新"的占位任务）不动
 * 可选：把本次"刚好达标"的任务奖励用 GM 邮件补发（因为领奖按钮没有服务端处理器）。
 *
 * 重要：必须在服务端停止时运行（服务端会缓存玩家数据并覆盖库里的值）。
 *       推荐用法：--watch —— 守着 java 进程，等它退出（你关游戏）后自动结算一次。
 */
public class ActivityTaskSync {

    public static void main(String[] args) throws Exception {
        String server = null;
        String uidArg = null;
        boolean dry = false;
        boolean watch = false;
        boolean sendMail = false;
        int interval = 30;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--server".equals(a) && i + 1 < args.length) {
                server = args[++i];
            } else if ("--uid".equals(a) && i + 1 < args.length) {
                uidArg = args[++i];
            } else if ("--dry".equals(a)) {
                dry = true;
            } else if ("--watch".equals(a)) {
                watch = true;
            } else if ("--send-mail".equals(a)) {
                sendMail = true;
            } else if ("--interval".equals(a) && i + 1 < args.length) {
                interval = Integer.parseInt(args[++i]);
            } else if ("-h".equals(a) || "--help".equals(a)) {
                usage();
                return;
            }
        }
        if (server == null || uidArg == null) {
            usage();
            return;
        }
        quietLogs();
        forceUtf8Console();

        File root = new File(server);
        long uid = Long.parseLong(uidArg);

        if (watch) {
            System.out.println("[watch] 等 Nebula 服务端退出后自动结算（Ctrl+C 退出监控）");
            boolean wasRunning = false;
            while (true) {
                boolean running = isServerRunning();
                if (wasRunning && !running) {
                    System.out.println("[watch] 服务端已退出，开始结算...");
                    try {
                        sync(root, uid, dry, sendMail);
                    } catch (Throwable t) {
                        System.out.println("[watch] 结算失败: " + t.getMessage());
                    }
                    System.out.println("[watch] 本轮结束，继续监控");
                }
                wasRunning = running;
                Thread.sleep(interval * 1000L);
            }
        }
        sync(root, uid, dry, sendMail);
    }

    private static void sync(File root, long uid, boolean dry, boolean sendMail) throws Exception {
        File serverDir = new File(root, "Server");
        if (!new File(serverDir, "config.json").isFile()) {
            serverDir = root;
        }
        File cfgFile = new File(serverDir, "config.json");
        File binDir = new File(serverDir, "Resources/bin");

        JsonObject cfg = JsonParser.parseString(read(cfgFile)).getAsJsonObject();
        JsonObject dbCfg = cfg.getAsJsonObject("accountDatabase");
        String uri = dbCfg.get("uri").getAsString();
        String dbName = dbCfg.get("collection").getAsString();
        String locale = cfg.has("language") ? cfg.get("language").getAsString() : "en_US";
        JsonObject httpCfg = cfg.has("httpServer") ? cfg.getAsJsonObject("httpServer") : new JsonObject();
        String httpPort = httpCfg.has("bindPort") ? httpCfg.get("bindPort").getAsString() : "5210";
        String httpAddr = httpCfg.has("publicAddress") ? httpCfg.get("publicAddress").getAsString() : "127.0.0.1";
        String adminKey = "";
        if (cfg.has("remoteCommand")) {
            JsonObject rc = cfg.getAsJsonObject("remoteCommand");
            if (rc.has("serverAdminKey")) {
                adminKey = rc.get("serverAdminKey").getAsString();
            }
        }

        JsonObject tasks = JsonParser.parseString(read(new File(binDir, "ActivityTask.json"))).getAsJsonObject();
        JsonObject levelsDef = JsonParser.parseString(read(new File(binDir, "ActivityLevelsLevel.json"))).getAsJsonObject();
        JsonObject langTask = JsonParser.parseString(read(new File(serverDir, "Resources/language/" + locale + "/ActivityTask.json"))).getAsJsonObject();
        JsonObject langLevel = JsonParser.parseString(read(new File(serverDir, "Resources/language/" + locale + "/ActivityLevelsLevel.json"))).getAsJsonObject();

        // 关卡名 → 关卡 id（用于把任务描述里的关卡名对到关卡）
        Map<String, java.util.List<String>> levelNameToId = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> e : levelsDef.entrySet()) {
            String lid = e.getKey();
            String nk = "ActivityLevelsLevel." + lid + ".1";
            if (langLevel.has(nk)) {
                String name = langLevel.get(nk).getAsString().trim();
                if (!name.isEmpty()) {
                    levelNameToId.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(lid);
                }
            }
        }

        try (MongoClient mc = MongoClients.create(uri)) {
            MongoDatabase db = mc.getDatabase(dbName);
            MongoCollection<Document> activity = db.getCollection("activity");
            Document doc = activity.find(Filters.eq("_id", uid)).first();
            if (doc == null) {
                System.out.println("[失败] 数据库里没有 uid=" + uid + " 的 activity 记录（先登录一次游戏）");
                return;
            }

            Document activities = (Document) doc.get("activities");
            if (activities == null) {
                System.out.println("[失败] activity 记录为空");
                return;
            }

            // 1) 收集已通关关卡：id → {star, buildId}
            Map<String, Document> cleared = new HashMap<>();
            for (Map.Entry<String, Object> e : activities.entrySet()) {
                Document act = (Document) e.getValue();
                if (!String.valueOf(act.get("_t")).contains("Level")) {
                    continue;
                }
                Document lv = (Document) act.get("levels");
                if (lv == null) {
                    continue;
                }
                for (Map.Entry<String, Object> le : lv.entrySet()) {
                    cleared.put(le.getKey(), (Document) le.getValue());
                }
            }

            // 2) 最高单局分数：取通关记录引用到的 build 里的最大 score
            long bestScore = 0;
            MongoCollection<Document> builds = db.getCollection("builds");
            for (Document info : cleared.values()) {
                Object bid = info.get("buildId");
                if (!(bid instanceof Number)) {
                    continue;
                }
                Document b = builds.find(Filters.eq("_id", ((Number) bid).longValue())).first();
                if (b != null && b.get("score") instanceof Number) {
                    bestScore = Math.max(bestScore, ((Number) b.get("score")).longValue());
                }
            }

            System.out.println("已通关关卡: " + cleared.size() + " 个；最高单局分数: " + bestScore);
            System.out.println();

            int changed = 0;
            Map<String, Long> mailReward = new TreeMap<>();
            StringBuilder detail = new StringBuilder();

            for (Map.Entry<String, Object> e : activities.entrySet()) {
                Document act = (Document) e.getValue();
                if (!String.valueOf(act.get("_t")).contains("Task")) {
                    continue;
                }
                Document quests = (Document) act.get("quests");
                if (quests == null) {
                    continue;
                }
                for (Map.Entry<String, Object> qe : quests.entrySet()) {
                    String qid = qe.getKey();
                    Document q = (Document) qe.getValue();
                    long max = num(q.get("maxProgress"));
                    long cur = num(q.get("curProgress"));
                    if (max <= 0 || cur >= max) {
                        continue;
                    }
                    JsonObject def = tasks.has(qid) ? tasks.getAsJsonObject(qid) : null;
                    if (def == null) {
                        continue;
                    }
                    long cond = def.has("CompleteCond") ? def.get("CompleteCond").getAsLong() : -1;
                    long target = -1;
                    String why = "";
                    if (cond == 149) {
                        target = Math.min(bestScore, max);
                        why = "单局分数 " + bestScore + "/" + max;
                    } else if (cond == 150 || cond == 110) {
                        String lid = findLevelId(qid, langTask, levelNameToId, cleared);
                        if (lid != null && cleared.containsKey(lid)) {
                            Document info = cleared.get(lid);
                            long star = num(info.get("star"));
                            if (cond == 110) {
                                target = star >= 3 ? 1 : 0;
                                why = "关卡 " + lid + " 星级 " + star;
                            } else {
                                target = 1;
                                why = "关卡 " + lid + " 已通关";
                            }
                        } else {
                            why = "关卡未通关";
                        }
                    } else {
                        continue;
                    }
                    if (target > cur) {
                        q.put("curProgress", target);
                        changed++;
                        boolean nowDone = target >= max;
                        detail.append("  ").append(qid).append(": ").append(cur).append(" -> ").append(target)
                                .append(" / ").append(max).append("  (").append(why).append(")")
                                .append(nowDone ? "  [完成]" : "").append('\n');
                        if (nowDone) {
                            addReward(mailReward, def, "Tid1", "Qty1");
                            addReward(mailReward, def, "Tid2", "Qty2");
                        }
                    }
                }
            }

            System.out.println(detail.length() == 0 ? "没有任务需要更新（你已经打到的进度都已经记上了）" : detail.toString());
            System.out.println("本次更新任务: " + changed + " 个" + (mailReward.isEmpty() ? "" : "；新达标任务奖励: " + mailReward));

            if (dry) {
                System.out.println("[dry] 只检查，未写库");
                return;
            }
            if (changed > 0) {
                activity.updateOne(Filters.eq("_id", uid), Updates.set("activities", activities));
                System.out.println("[完成] 已写入数据库（重新进游戏后生效）");
            }
            if (!mailReward.isEmpty()) {
                StringBuilder hint = new StringBuilder("mail \"活动任务奖励\" \"活动任务达标，奖励自动补发。\"");
                for (Map.Entry<String, Long> r : mailReward.entrySet()) {
                    hint.append(' ').append(r.getKey()).append(':').append(r.getValue());
                }
                hint.append(" @").append(uid);
                System.out.println();
                System.out.println("[奖励] 这些任务刚达标，奖励可以这样补发（游戏在线时用 GM 工具/接口执行）：");
                System.out.println("       " + hint);
            }
            if (sendMail && !mailReward.isEmpty() && !adminKey.isEmpty()) {
                StringBuilder cmd = new StringBuilder("mail \"活动任务奖励\" \"活动任务达标，奖励自动补发。\"");
                for (Map.Entry<String, Long> r : mailReward.entrySet()) {
                    cmd.append(' ').append(r.getKey()).append(':').append(r.getValue());
                }
                cmd.append(" @").append(uid);
                String body = "{\"token\":" + toJson(adminKey) + ",\"command\":" + toJson(cmd.toString()) + "}";
                String url = "http://" + httpAddr + ":" + httpPort + "/api/command";
                String resp = post(url, body);
                System.out.println("[邮件] " + (resp.length() > 180 ? resp.substring(0, 180) + "..." : resp));
                if (resp.contains("not found")) {
                    System.out.println("[提示] 玩家不在线，邮件没发出去；下次登录后单独跑一次 --send-mail 即可");
                }
            }
        }
    }

    private static String findLevelId(String qid, JsonObject langTask, Map<String, java.util.List<String>> nameToId, Map<String, Document> cleared) {
        String key = "ActivityTask." + qid + ".2";
        if (!langTask.has(key)) {
            return null;
        }
        String desc = langTask.get(key).getAsString();
        String best = null;
        for (String name : nameToId.keySet()) {
            if (desc.contains(name) && (best == null || name.length() > best.length())) {
                best = name;
            }
        }
        if (best == null) {
            return null;
        }
        java.util.List<String> ids = nameToId.get(best);
        for (String id : ids) {
            if (cleared.containsKey(id)) {
                return id;
            }
        }
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static void addReward(Map<String, Long> reward, JsonObject task, String tidKey, String qtyKey) {
        if (!task.has(tidKey) || !task.has(qtyKey)) {
            return;
        }
        String tid = task.get(tidKey).getAsString();
        long qty = task.get(qtyKey).getAsLong();
        if (tid.isEmpty() || qty <= 0) {
            return;
        }
        reward.merge(tid, qty, Long::sum);
    }

    private static boolean isServerRunning() {
        try {
            Process p = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq java.exe", "/NH").redirectErrorStream(true).start();
            String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
            return out.toLowerCase().contains("java.exe");
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }

    private static long num(Object o) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o instanceof String) {
            try {
                return Long.parseLong((String) o);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(Paths.get(f.getAbsolutePath())), StandardCharsets.UTF_8);
    }

    private static String toJson(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String post(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = c.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        return new String(readAll(in), StandardCharsets.UTF_8);
    }

    private static void quietLogs() {
        try {
            org.slf4j.Logger root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            if (root instanceof ch.qos.logback.classic.Logger) {
                ((ch.qos.logback.classic.Logger) root).setLevel(ch.qos.logback.classic.Level.WARN);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void forceUtf8Console() {
        try {
            System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    private static void usage() {
        System.out.println("用法: java -cp \"<本目录>;<包根目录>\\Server\\lib\\Nebula.jar\" ActivityTaskSync --server \"D:\\Stella Sora\" --uid 10003 [--dry] [--watch] [--send-mail]");
        System.out.println("  --server     本地服包根目录");
        System.out.println("  --uid        玩家 UID");
        System.out.println("  --dry        只统计不写库");
        System.out.println("  --watch      一直守着，服务端退出（你关游戏）后自动结算一次");
        System.out.println("  --send-mail  把本次刚好达标任务的奖励用 GM 邮件补发（需要玩家在线）");
        System.out.println("  --interval   watch 模式的轮询秒数，默认 30");
    }
}
