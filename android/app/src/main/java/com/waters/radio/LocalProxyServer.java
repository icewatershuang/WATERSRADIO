package com.waters.radio;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * v9.1.21 · 本地流式代理（127.0.0.1）
 * ------------------------------------------------------------------
 * 根因：APK 内 <audio crossorigin="anonymous"> 直连「无 CORS 响应头」的远端流时，
 *   Web Audio 图（createMediaElementSource）取到的是被同源策略污染的静音 →
 *   离线 ASR 收不到声 → 英文字幕不出。HLS 经 hls.js/MSE（blob: 同源）不受影响，
 *   故只有「直连型（尤其 HTTPS 无 CORS 头）流」中招。
 *
 * 修复：本代理在 APK 内起一个 127.0.0.1 的 HTTP 服务，把远端直播流重新吐出并带
 *   Access-Control-Allow-Origin: *，让 Web Audio 取到 CORS 干净的音频。
 *   · 仅 APK 内使用；回环明文已被 network_security_config 放行；
 *   · 流式转发（不整文件缓冲），客户端断开即停止上行，避免空耗流量；
 *   · 上行 HTTPS 证书校验放宽（公共电台证书各异，放宽以最大化可用率）；
 *   · 不上带 Icy-MetaData，吐出纯音频字节（MediaElement 不需要 ICY 内联元数据）。
 */
public class LocalProxyServer {
    private static final String TAG = "WatersProxy";
    private static final String UA =
        "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running = false;
    private int localPort = -1;
    private String baseUrl = "";

    public synchronized void start() {
        if (running) return;
        // 先尝试固定端口，失败再让系统分配空闲端口（port=0）
        int[] candidates = { 8123, 0 };
        for (int port : candidates) {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
                localPort = serverSocket.getLocalPort();
                baseUrl = "http://127.0.0.1:" + localPort + "/stream?u=";
                running = true;
                acceptThread = new Thread(new AcceptLoop(), "WatersLocalProxy");
                acceptThread.setDaemon(true);
                acceptThread.start();
                Log.i(TAG, "本地代理已启动：" + baseUrl);
                return;
            } catch (IOException e) {
                Log.w(TAG, "端口 " + port + " 绑定失败：" + e.getMessage());
                closeSocket();
            }
        }
        Log.e(TAG, "本地代理启动失败：无可用端口");
    }

    public synchronized void stop() {
        running = false;
        closeSocket();
        if (acceptThread != null) {
            try { acceptThread.interrupt(); } catch (Throwable ignored) {}
            acceptThread = null;
        }
    }

    private void closeSocket() {
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Throwable ignored) {}
            serverSocket = null;
        }
    }

    /** 返回形如 http://127.0.0.1:8123/stream?u= 的前缀；未启动返回 null */
    public String getBaseUrl() { return running ? baseUrl : null; }

    private class AcceptLoop implements Runnable {
        @Override
        public void run() {
            while (running && serverSocket != null) {
                try {
                    final Socket client = serverSocket.accept();
                    if (!running) { try { client.close(); } catch (Throwable ignored) {} break; }
                    new Thread(new Handler(client), "WatersProxyConn").start();
                } catch (IOException e) {
                    if (running) Log.w(TAG, "accept 异常：" + e.getMessage());
                    try { Thread.sleep(50); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
            }
        }
    }

    private class Handler implements Runnable {
        private final Socket client;
        Handler(Socket c) { this.client = c; }

        @Override
        public void run() {
            try {
                client.setSoTimeout(30000); // 读请求头超时
                InputStream cis = client.getInputStream();
                OutputStream cos = client.getOutputStream();

                // 读请求行（GET /stream?u=... HTTP/1.1）
                String requestLine = readLine(cis);
                if (requestLine == null || requestLine.isEmpty()) return;
                String method = requestLine.split(" ")[0];
                String path = "";
                int sp1 = requestLine.indexOf(' ');
                int sp2 = requestLine.indexOf(' ', sp1 + 1);
                if (sp1 > 0 && sp2 > 0) path = requestLine.substring(sp1 + 1, sp2);

                // 跳过其余请求头
                String line;
                while ((line = readLine(cis)) != null && !line.isEmpty()) { /* drain */ }

                String target = extractTarget(path);
                if (target == null || target.isEmpty()) {
                    sendStatus(cos, 400, "Bad Request", false);
                    return;
                }
                if (method.equalsIgnoreCase("OPTIONS")) {
                    sendStatus(cos, 200, "OK", false); // 预检：只回 CORS 头
                    return;
                }

                relay(target, cos);
            } catch (Throwable t) {
                // 异常静默（客户端断开等）
            } finally {
                try { client.close(); } catch (Throwable ignored) {}
            }
        }

        private String extractTarget(String path) {
            try {
                int q = path.indexOf('?');
                if (q < 0) return null;
                String query = path.substring(q + 1);
                for (String kv : query.split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq < 0) continue;
                    String k = kv.substring(0, eq);
                    String v = kv.substring(eq + 1);
                    if (k.equalsIgnoreCase("u")) return java.net.URLDecoder.decode(v, "UTF-8");
                }
            } catch (Throwable ignored) {}
            return null;
        }

        private void relay(String target, OutputStream cos) throws IOException {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(target);
                conn = (HttpURLConnection) u.openConnection();
                // 上行 HTTPS 证书校验放宽（公共电台证书各异，本地代理仅重吐音频）
                if (conn instanceof HttpsURLConnection) {
                    try {
                        SSLContext sc = SSLContext.getInstance("TLS");
                        sc.init(null, new TrustManager[]{ new NaiveTrust() }, new SecureRandom());
                        ((HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
                    } catch (Throwable ignored) {}
                }
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Connection", "close");
                // 部分 CDN/电台会校验 Origin/Referer，按其自身 host 补上，提升可用率
                try {
                    URL tu = new URL(target);
                    String origin = tu.getProtocol() + "://" + tu.getHost();
                    if (tu.getPort() != -1) origin += ":" + tu.getPort();
                    conn.setRequestProperty("Origin", origin);
                    conn.setRequestProperty("Referer", origin + "/");
                } catch (Throwable ignored) {}
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(0); // 直播流持续读
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                int code = conn.getResponseCode();
                // 上行非 2xx：如实回状态码给客户端，让媒体层快速失败 → 触发网页回退直连
                if (code < 200 || code >= 300) {
                    sendStatus(cos, code, conn.getResponseMessage(), true);
                    return;
                }

                String contentType = conn.getContentType();

                // 响应头（带 CORS）
                StringBuilder head = new StringBuilder();
                head.append("HTTP/1.1 200 OK\r\n");
                head.append("Access-Control-Allow-Origin: *\r\n");
                head.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
                head.append("Access-Control-Allow-Headers: *\r\n");
                head.append("Access-Control-Expose-Headers: *\r\n");
                if (contentType != null && !contentType.isEmpty()) {
                    head.append("Content-Type: ").append(contentType).append("\r\n");
                }
                head.append("Connection: close\r\n");
                head.append("\r\n");
                cos.write(head.toString().getBytes("UTF-8"));
                cos.flush();

                // 流式转发：上行 → 客户端；客户端断开（write 抛错）即停
                InputStream in = conn.getInputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    cos.write(buf, 0, n);
                }
            } catch (Throwable t) {
                // 客户端断开或上行失败：静默结束
            } finally {
                if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
            }
        }

        /* sendStatus：仅回状态行 + CORS 头（+ 可选空 body），用于错误/预检 */
        private void sendStatus(OutputStream cos, int code, String msg, boolean withCorsBody) throws IOException {
            String body = "HTTP/1.1 " + code + " " + (msg == null ? "" : msg) + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: *\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
            cos.write(body.getBytes("UTF-8"));
            cos.flush();
        }

        private String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            int count = 0;
            while ((c = in.read()) != -1) {
                if (++count > 4096) break; // 防御异常超长行
                if (c == '\r') {
                    int c2 = in.read();
                    if (c2 == '\n') break;
                    if (c2 != -1) sb.append((char) c2);
                    break;
                }
                if (c == '\n') break;
                sb.append((char) c);
            }
            if (c == -1 && sb.length() == 0) return null;
            return sb.toString();
        }
    }

    /** 放宽 HTTPS 证书校验（本地代理重吐公共电台流，仅用于音频） */
    private static class NaiveTrust implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
