package com.waters.radio;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * WATERS RADIO · 语音识别字幕（v8.0，离线 sherpa-onnx 流式 Zipformer，模型内置）
 * ------------------------------------------------------------------
 * v7.1~v7.9 用 Vosk（small-0.15，RTF 0.15~0.19）。v8 换成 sherpa-onnx 流式
 * Zipformer（int8 量化，RTF 0.085~0.10，约快一倍，真实人声 WER 显著更低）。
 * 本地 36s 电台风格语音基准（同口径）：zipformer int8 RTF 0.085~0.10，逐句文本质量
 * 优于 Vosk；APK 因 70MB 模型 + AAR 增至 ~90MB（仍 <100MB 红线）。
 *
 * 数据流（与 v7 完全一致）：网页从 Web Audio 图采 16kHz 单声道 16-bit PCM，
 *       经 JsBridge.asrPcm(base64) → feedPcm → pcmQ(64 包≈16s) → AsrWorker
 *       逐包 acceptWaveform → 流式识别 → partial / 定稿(endpoint) → callAsrJs。
 *       字幕与耳朵听到的是同一路音频，天然同步。
 *
 * 模型：v8 随 APK 内置在 assets/asr/（encoder/decoder/joiner int8 onnx + tokens.txt），
 *       经 AssetManager 直读（不复制、不联网、开箱即用）；首次开启仅做存在性校验。
 * API 门控：sherpa 原生库要求 API≥21；低内存设备 asrAvailable() 返回 false，
 *       网页端「字幕」整项隐藏。
 */
public class AsrController {

    private static final String TAG = "WatersAsr";
    private static final int TARGET_RATE = 16000;

    /* assets/asr/ 内模型文件名（CI 下载并随 APK 打包） */
    private static final String ENC = "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx";
    private static final String DEC = "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx";
    private static final String JOIN = "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx";
    private static final String TOK = "tokens.txt";

    private final RadioPlaybackService service;
    private final Context appContext;

    private volatile boolean enabled = false;
    private volatile String lang = "en";
    private volatile String streamUrl = "";      /* 当前台 URL（仅用于「换台才重置」判断） */

    /* 16kHz 单声道 16-bit PCM 包队列：每包约 0.25s(8000B)，64 包 ≈ 16s 缓冲；
       满了丢最老包（v7.4~v7.7 经验：实时电台宁跳过陈旧音频也要对着耳朵正在听的） */
    private final ArrayBlockingQueue<byte[]> pcmQ = new ArrayBlockingQueue<byte[]>(64);

    private volatile AsrWorker worker = null;
    private volatile OnlineRecognizer recognizer = null;
    private final Object recLock = new Object();

    public AsrController(RadioPlaybackService svc) {
        this.service = svc;
        this.appContext = svc.getApplicationContext();
    }

    /* 能力探测：设备是否支持离线识别（网页端据此显隐开关项） */
    public boolean isAvailable() {
        if (Build.VERSION.SDK_INT < 21) return false;
        try {
            android.app.ActivityManager am =
                (android.app.ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.isLowRamDevice()) return false;
        } catch (Throwable ignored) {}
        return true;
    }

    public void setLang(String l) {
        if (l == null || l.isEmpty()) l = "en";
        /* v8 仅内置英文模型：任何非 en 都回退 en（含旧存档 zh） */
        if (!"en".equals(l)) l = "en";
        lang = l;   /* 单语言，无需重启线程换模型 */
    }

    public String getLang() { return lang; }

    /* 网页切换开关：on=true 开启，false 关闭 */
    public synchronized void setEnabled(boolean on, String url) {
        if (on) {
            if (!isAvailable()) { toast("本设备不支持离线语音识别"); return; }
            if (url != null && !url.isEmpty()) streamUrl = url;
            enabled = true;
            pcmQ.clear();
            startWorker();
        } else {
            enabled = false;
            stopWorker();
        }
    }

    /* 真正换了台 → 清残留 + 重置识别流（避免上一台声学状态串台）；
       暂停后恢复（同 URL）→ 不重置，继续识别 */
    public synchronized void onStreamChanged(String url) {
        if (url == null || url.isEmpty()) return;
        boolean changed = !url.equals(streamUrl);
        streamUrl = url;
        if (!enabled || !changed) return;
        pcmQ.clear();
        resetStream();
    }

    /* 暂停/停播：网页已停止投喂，清掉积压；线程保留空转，恢复后自动续上 */
    public synchronized void onPlaybackStopped() {
        pcmQ.clear();
    }

    public synchronized void release() {
        enabled = false;
        stopWorker();
        synchronized (recLock) {
            if (recognizer != null) { try { recognizer.release(); } catch (Throwable ignored) {} recognizer = null; }
        }
    }

    /* ---------------- PCM 投喂（JsBridge.asrPcm 调入，16kHz 单声道 16-bit） ---------------- */
    public void feedPcm(byte[] pcm16k) {
        if (!enabled || pcm16k == null || pcm16k.length == 0) return;
        while (pcmQ.size() >= 20) pcmQ.poll();   /* v7.4~v7.7：延迟保险丝 ≈5s */
        pcmQ.offer(pcm16k);
        ensureWorker();
    }

    /* ---------------- 识别线程 ---------------- */
    private synchronized void startWorker() {
        if (!enabled) return;
        if (worker != null && worker.isAlive()) return;
        worker = new AsrWorker();
        worker.start();
    }

    private void ensureWorker() {
        AsrWorker w = worker;
        if (w == null || !w.isAlive()) startWorker();
    }

    /* 换台：重建识别流（模型常驻，不重载） */
    private synchronized void resetStream() {
        AsrWorker w = worker;
        if (w != null) w.renewStream();
    }

    private synchronized void stopWorker() {
        AsrWorker w = worker;
        worker = null;
        if (w != null) { w.stop = true; pcmQ.clear(); }
    }

    private class AsrWorker extends Thread {
        private volatile boolean stop = false;
        private volatile OnlineStream stream = null;

        @Override
        public void run() {
            boolean ok = prepareAssets();
            if (!ok) { enabled = false; toast("语音模型准备失败，字幕已关闭"); return; }

            OnlineRecognizer rec;
            synchronized (recLock) {
                if (recognizer == null) {
                    try { recognizer = buildRecognizer(appContext.getAssets(), "asr"); }
                    catch (Throwable t) {
                        Log.e(TAG, "sherpa 识别器创建失败", t);   // 打印完整堆栈，便于真机定位
                        /* v8.0.2：toast 带上异常 message（如 NoClassDefFoundError 的缺失类名），真机可直接定位 */
                        String msg = t.getMessage();
                        enabled = false;
                        toast("语音识别初始化失败：" + t.getClass().getSimpleName()
                              + (msg != null ? " " + msg : ""));
                        return;
                    }
                }
                rec = recognizer;
            }
            stream = rec.createStream("");

            try {
                while (!stop) {
                    byte[] chunk = pcmQ.poll(250, TimeUnit.MILLISECONDS);
                    if (chunk == null) continue;   /* 暂停/断流：空转等待 */
                    try {
                        emitPartial(rec, stream, chunk);
                    } catch (Throwable t) { Log.e(TAG, "acceptWaveForm 失败: " + t); }
                }
                /* 退出时把当前流定稿回传 */
                if (stream != null) {
                    String t = rec.getResult(stream).getText();
                    if (t != null && !t.trim().isEmpty()) service.callAsrJs(t.trim(), true);
                }
            } catch (Throwable t) {
                Log.e(TAG, "识别线程异常: " + t);
            }
        }

        /* 把一包 PCM 喂入、解码、回传 partial；遇到 endpoint 则定稿 + 重置流 */
        private void emitPartial(OnlineRecognizer rec, OnlineStream st, byte[] pcm16k) {
            float[] floats = toFloat(pcm16k);
            st.acceptWaveform(floats, TARGET_RATE);
            while (rec.isReady(st)) rec.decode(st);
            String partial = rec.getResult(st).getText();
            if (partial != null && !partial.trim().isEmpty()) service.callAsrJs(partial.trim(), false);
            if (rec.isEndpoint(st)) {
                String fin = rec.getResult(st).getText();
                if (fin != null && !fin.trim().isEmpty()) service.callAsrJs(fin.trim(), true);
                rec.reset(st);
            }
        }

        /* 换台：丢弃当前流、新建一条（模型常驻，不重新加载） */
        void renewStream() {
            if (recognizer == null) return;
            try { if (stream != null) recognizer.reset(stream); } catch (Throwable ignored) {}
            stream = recognizer.createStream("");
        }
    }

    /* 16-bit PCM(byte[]) → float[]([-1,1]) */
    private static float[] toFloat(byte[] b) {
        int n = b.length / 2;
        float[] f = new float[n];
        for (int i = 0; i < n; i++) {
            int lo = b[2 * i] & 0xff;
            int hi = b[2 * i + 1];
            int s = (lo | (hi << 8));
            if (s > 32767) s -= 65536;
            f[i] = s / 32768.0f;
        }
        return f;
    }

    /* 构造 sherpa-onnx 流式识别器（模型从 APK assets 经 AssetManager 读取） */
    private OnlineRecognizer buildRecognizer(android.content.res.AssetManager amgr, String assetDir) {
        String dir = assetDir + "/";
        OnlineTransducerModelConfig tr = new OnlineTransducerModelConfig();
        tr.setEncoder(dir + ENC);
        tr.setDecoder(dir + DEC);
        tr.setJoiner(dir + JOIN);

        OnlineModelConfig mc = new OnlineModelConfig();
        mc.setTransducer(tr);
        mc.setTokens(dir + TOK);
        mc.setNumThreads(2);
        mc.setDebug(false);

        /* endpoint：尾静音 0.5s 触发定稿（与 v7 字幕手感一致） */
        EndpointRule r1 = new EndpointRule(false, 0.5f, 0.0f);
        EndpointRule r2 = new EndpointRule(true, 0.5f, 0.0f);
        EndpointRule r3 = new EndpointRule(false, 0.0f, 20.0f);
        EndpointConfig ep = new EndpointConfig(r1, r2, r3);

        OnlineRecognizerConfig cfg = new OnlineRecognizerConfig();
        cfg.setFeatConfig(new FeatureConfig());
        cfg.setModelConfig(mc);
        cfg.setEndpointConfig(ep);
        cfg.setEnableEndpoint(true);
        cfg.setDecodingMethod("greedy_search");

        /* 构造器唯一签名：(AssetManager, OnlineRecognizerConfig) —— 模型按 assets 相对路径读取 */
        return new OnlineRecognizer(amgr, cfg);
    }

    /* ---------------- 模型准备：确认 assets/asr/ 四件套就位（无需复制，AssetManager 直读） ----------------
       v8 模型随 APK 内置在 assets/asr/，开箱即用、不联网；此处仅做存在性校验。
       用 open() 而非 openFd()——对压缩/未压缩资产都安全（build.gradle 已对 .onnx 设 noCompress）。 */
    private boolean prepareAssets() {
        String[] names = {ENC, DEC, JOIN, TOK};
        for (String nm : names) {
            try {
                java.io.InputStream is = appContext.getAssets().open("asr/" + nm);
                is.close();
            } catch (Throwable t) {
                Log.e(TAG, "模型缺失: " + nm + " → " + t);
                return false;
            }
        }
        return true;
    }

    private void toast(String msg) { service.callAsrToast(msg); }
}
