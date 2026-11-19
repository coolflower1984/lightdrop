package com.lightdrop.transfer.net;

import android.content.Context;

import com.lightdrop.transfer.core.Incoming;
import com.lightdrop.transfer.core.Util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.URLDecoder;
import java.util.Locale;

/**
 * 极简 HTTP 上传服务，跟二进制传输共用同一个 TCP 端口。
 *
 * 用途：当两台设备互相发现不了（路由器开了 AP 隔离、访客网络、广播被拦），
 * 手机浏览器打开平板显示的地址，就能直接选文件上传，不必装任何东西。
 */
public class MiniHttp {

    private static final String HTML = "<!doctype html><html lang=\"zh-CN\"><head>"
            + "<meta charset=\"utf-8\"><meta name=\"viewport\" "
            + "content=\"width=device-width,initial-scale=1\"><title>发送到平板</title>"
            + "<style>"
            + "body{margin:0;padding:24px;font-family:-apple-system,system-ui,sans-serif;"
            + "background:#F5F7FB;color:#111}"
            + "h1{font-size:20px;margin:0 0 6px}"
            + "p{color:#6B7280;font-size:13px;margin:0 0 20px}"
            + ".box{background:#fff;border-radius:16px;padding:20px;border:1px solid #EDF0F5}"
            + "input{width:100%;font-size:14px}"
            + "button{margin-top:16px;width:100%;height:48px;border:0;border-radius:12px;"
            + "background:#3B6EF6;color:#fff;font-size:16px;font-weight:600}"
            + "button:disabled{opacity:.5}"
            + "#log{margin-top:16px;font-size:12px;color:#6B7280;white-space:pre-wrap;"
            + "line-height:1.6}"
            + "</style></head><body>"
            + "<h1>发送到平板</h1><p>选中的文件会直接存到平板 Download/轻传</p>"
            + "<div class=\"box\"><input id=\"f\" type=\"file\" multiple>"
            + "<button id=\"b\" onclick=\"go()\">开始发送</button>"
            + "<div id=\"log\"></div></div>"
            + "<script>"
            + "async function go(){"
            + "var fs=[].slice.call(document.getElementById('f').files);"
            + "if(!fs.length){document.getElementById('log').textContent='先选择文件';return}"
            + "var b=document.getElementById('b');b.disabled=true;"
            + "var log=document.getElementById('log');log.textContent='';"
            + "for(var i=0;i<fs.length;i++){var f=fs[i];"
            + "log.textContent+='正在发送 '+f.name+'…\\n';"
            + "try{var r=await fetch('/u?name='+encodeURIComponent(f.name),"
            + "{method:'POST',body:f});"
            + "log.textContent+=(r.ok?'✓ ':'✗ ')+f.name+'\\n';}"
            + "catch(e){log.textContent+='✗ '+f.name+' '+e+'\\n';}}"
            + "log.textContent+='\\n全部完成';b.disabled=false;}"
            + "</script></body></html>";

    private MiniHttp() {
    }

    public static boolean looksHttp(byte[] head, int n) {
        if (n < 3) return false;
        return (head[0] == 'G' && head[1] == 'E' && head[2] == 'T')
                || (head[0] == 'P' && head[1] == 'O' && head[2] == 'S')
                || (head[0] == 'H' && head[1] == 'E' && head[2] == 'A');
    }

    public static void handle(Context ctx, PushbackInputStream in, OutputStream rawOut,
                              TransferServer.Listener listener) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null) return;

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return;
        String method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];

        int contentLength = -1;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String k = line.substring(0, c).trim().toLowerCase(Locale.US);
            if ("content-length".equals(k)) {
                try {
                    contentLength = Integer.parseInt(line.substring(c + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if ("POST".equals(method) && target.startsWith("/u")) {
            String name = queryParam(target, "name");
            if (name == null || name.isEmpty()) name = "file-" + System.currentTimeMillis();
            if (contentLength <= 0) {
                write(rawOut, 400, "text/plain; charset=utf-8", "缺少文件内容");
                return;
            }
            receive(ctx, in, rawOut, name, contentLength, listener);
            return;
        }

        if ("HEAD".equals(method)) {
            write(rawOut, 200, "text/html; charset=utf-8", "");
            return;
        }

        write(rawOut, 200, "text/html; charset=utf-8", HTML);
    }

    private static void receive(Context ctx, InputStream in, OutputStream out,
                                String rawName, int length,
                                TransferServer.Listener listener) throws IOException {
        String name = Util.safeName(rawName);
        Incoming inc = null;
        try {
            if (listener != null) listener.onSessionStart(1);
            if (listener != null) listener.onFileStart(0, name, length);
            inc = Incoming.open(ctx, name, length);
            byte[] buf = new byte[64 * 1024];
            int remaining = length;
            long done = 0;
            long last = 0;
            while (remaining > 0) {
                int want = Math.min(buf.length, remaining);
                int r = in.read(buf, 0, want);
                if (r < 0) throw new IOException("上传中断");
                inc.write(buf, 0, r);
                remaining -= r;
                done += r;
                long now = System.currentTimeMillis();
                if (listener != null && now - last > 150) {
                    last = now;
                    listener.onProgress(0, done, length);
                }
            }
            String uri = inc.finish();
            if (listener != null) {
                listener.onFileDone(0, name, uri, length);
                listener.onSessionEnd(true, null);
            }
            inc = null;
            write(out, 200, "text/plain; charset=utf-8", "ok");
        } catch (Exception e) {
            if (inc != null) inc.abort();
            if (listener != null) listener.onSessionEnd(false, e.getMessage());
            try {
                write(out, 500, "text/plain; charset=utf-8", "失败: " + e.getMessage());
            } catch (IOException ignored) {
            }
        }
    }

    private static void write(OutputStream out, int code, String type, String body)
            throws IOException {
        byte[] b = body == null ? new byte[0] : body.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "ERROR") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        if (b.length > 0) out.write(b);
        out.flush();
    }

    private static String queryParam(String target, String key) {
        int q = target.indexOf('?');
        if (q < 0) return null;
        String[] pairs = target.substring(q + 1).split("&");
        for (String p : pairs) {
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            if (!key.equals(p.substring(0, eq))) continue;
            try {
                return URLDecoder.decode(p.substring(eq + 1), "UTF-8");
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(96);
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') bo.write(c);
            if (bo.size() > 8192) break;
        }
        if (!any) return null;
        return new String(bo.toByteArray(), "UTF-8");
    }
}
