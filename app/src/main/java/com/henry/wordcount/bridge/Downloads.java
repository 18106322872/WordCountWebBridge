package com.henry.wordcount.bridge;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

/**
 * v1.1.10：桥接导出的 PDF 下载清单（类似手机浏览器的「下载」管理页）。
 *
 * 背景：此前网页通过 saveFile 把 PDF 存到系统下载目录后只弹 toast，用户必须自己进
 * 「文件管理」才能看，很不方便。现做两件事：
 *   1) 导出完成后把条目登记到本清单，并尽量自动打开（见 BridgeActivity.saveFile）。
 *   2) 提供 DownloadsActivity 列表，用户可在 App 内直接「查看 / 删除」下载的文件，
 *      不必切到系统文件管理器。
 *
 * 存储：应用私有目录下的 wc_bridge_downloads.json（{items:[...]}）。
 * 每个条目同时记录 content uri（Android 10+ 走 MediaStore）或文件路径（旧系统），
 * 删除时按对应方式移除底层文件，保证列表与实际文件一致。
 */
public class Downloads {
    static final String FILE = "wc_bridge_downloads.json";

    /** 一条下载记录 */
    public static class Item {
        public String name;   // 文件名（已做安全化）
        public String uri;    // Android 10+：MediaStore content uri，低版本为空串
        public String path;   // 旧系统：应用外部下载目录绝对路径，10+ 为空串
        public String mime;   // MIME，PDF 导出固定 application/pdf
        public long size;     // 字节数
        public long time;     // 下载完成时间戳（ms）
    }

    /** 登记一条下载（写入持久化清单，最新的排前面） */
    public static synchronized void record(Context ctx, String name, String uri,
                                          String path, String mime, long size) {
        ArrayList<Item> list = read(ctx);
        Item it = new Item();
        it.name = (name == null) ? "文件" : name;
        it.uri = (uri == null) ? "" : uri;
        it.path = (path == null) ? "" : path;
        it.mime = (mime == null || mime.isEmpty()) ? "application/pdf" : mime;
        it.size = size;
        it.time = System.currentTimeMillis();
        list.add(it);
        write(ctx, list);
    }

    /** 读取全部下载（按时间倒序，最新在前） */
    public static synchronized ArrayList<Item> read(Context ctx) {
        ArrayList<Item> out = new ArrayList<>();
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.exists()) return out;
            InputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(
                    new String(bos.toByteArray(), StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Item it = new Item();
                    it.name = o.optString("name", "文件");
                    it.uri = o.optString("uri", "");
                    it.path = o.optString("path", "");
                    it.mime = o.optString("mime", "application/pdf");
                    it.size = o.optLong("size", 0);
                    it.time = o.optLong("time", 0);
                    out.add(it);
                }
            }
        } catch (Throwable ignore) { }
        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    private static void write(Context ctx, ArrayList<Item> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : list) {
                JSONObject o = new JSONObject();
                o.put("name", it.name);
                o.put("uri", it.uri);
                o.put("path", it.path);
                o.put("mime", it.mime);
                o.put("size", it.size);
                o.put("time", it.time);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("items", arr);
            File f = new File(ctx.getFilesDir(), FILE);
            FileOutputStream out = new FileOutputStream(f);
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Throwable ignore) { }
    }

    /** 按列表序号删除一条（同时删除底层文件） */
    public static synchronized void remove(Context ctx, int index) {
        ArrayList<Item> list = read(ctx);
        if (index < 0 || index >= list.size()) return;
        deleteFile(ctx, list.get(index));
        list.remove(index);
        write(ctx, list);
    }

    /** 删除底层文件（MediaStore 用 uri；旧系统用 path） */
    public static void deleteFile(Context ctx, Item it) {
        try {
            if (it.uri != null && !it.uri.isEmpty()) {
                ctx.getContentResolver().delete(Uri.parse(it.uri), null, null);
            } else if (it.path != null && !it.path.isEmpty()) {
                new File(it.path).delete();
            }
        } catch (Throwable ignore) { }
    }

    /**
     * 打开一条下载。Android 10+ 用 MediaStore content uri 直接拉起查看器（带读授权）；
     * 旧系统因无 FileProvider 无法直接跨应用打开，退化为提示文件位置，由用户去文件管理器打开。
     */
    public static void open(Context ctx, Item it) {
        try {
            Uri u = null;
            if (it.uri != null && !it.uri.isEmpty()) {
                u = Uri.parse(it.uri);
            } else if (it.path != null && !it.path.isEmpty()) {
                Toast.makeText(ctx, "请在文件管理器中打开：\n" + it.path, Toast.LENGTH_LONG).show();
                return;
            }
            if (u == null) {
                Toast.makeText(ctx, "文件已不存在", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent v = new Intent(Intent.ACTION_VIEW);
            v.setDataAndType(u, it.mime);
            v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(v);
        } catch (Throwable e) {
            Toast.makeText(ctx, "打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
