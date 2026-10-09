package com.waters.radio;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * WATERS RADIO · 语音识别字幕（v7.2，离线 Vosk，模型内置）
 * ------------------------------------------------------------------
 * v7.0 架构（已废弃）：原生以当前台 URL 再开一条并行连接，MediaExtractor +
 *            MediaCodec 解码成 PCM 后喂 Vosk。缺点：MediaExtractor 解不了
 *            HLS(.m3u8)，遇 HLS 弹「该格式暂不支持识别」，且同一台流要下载两遍。
 * v7.1 架构：音频本来就在 WebView 里播（HLS 经 hls.js/MSE 喂进 <audio>），
 *            网页从 Web Audio 图直接采 PCM（单声道下混 + 16kHz 重采样），
 *            经 JsBridge.asrPcm(base64) 推过来 → 队列 → Vosk 流式识别。
 *            凡网页能播的源（HLS / ICY / https 直连）都支持；字幕与听到的
 *            是同一路音频，天然同步；也不再双倍消耗流量。
 *
 * 数据流：网页(约 0.25s/包) → asrPcm(base64) → feedPcm → pcmQ(64 包 ≈ 16s)
 *        → AsrWorker 逐包 acceptWaveForm → partial/final → callAsrJs → 字幕层
 *
 * 模型：v7.2 起随 APK 内置（assets/vosk/*.zip，英/中两个小模型），首次开启时
 *       解压到 getFilesDir()/vosk/<lang>，之后完全离线；**开箱即用，不联网**。
 *       （v7.0/v7.1 曾靠首次联网下载，用户网络到 alphacephei 不通 →
 *       「语音模型下载失败，字幕已关闭」。联网下载仅保留作 assets 缺失时的兜底。）
 * 能力门控：API < 21（Vosk 原生库要求）或低内存设备 → asrAvailable() 返回
 *       false，网页端「字幕」整项隐藏，不会误触。
 */
public class AsrController {

    private static final String TAG = "WatersAsr";
    private static final String MODEL_BASE_URL = "https://alphacephei.com/vosk/models/";
    private static final int TARGET_RATE = 16000;

    /* 语言 → 模型 zip 文件名（解压后顶层目录同名） */
    private static final Map<String, String> MODEL_ZIP = new HashMap<>();
    static {
        MODEL_ZIP.put("en", "vosk-model-small-en-us-0.15.zip");   // 英语（小模型 ~40MB）
        MODEL_ZIP.put("zh", "vosk-model-small-cn-0.22.zip");     // 中文（官方小模型 ~42MB；⚠️ 官方名是 small-cn-0.22，不存在 small-zh-cn-0.3）
    }

    private final RadioPlaybackService service;
    private final Context appContext;

    private volatile boolean enabled = false;
    private volatile String lang = "en";
    private volatile String streamUrl = "";      /* 当前台 URL（仅用于「换台才重置」判断） */

    /* 16kHz 单声道 16-bit PCM 包队列：每包约 0.25s(8000B)，64 包 ≈ 16s 缓冲；
       满了丢新包（宁可短暂缺字也不撑爆内存）；暂停时网页停止投喂即自然断流 */
    private final ArrayBlockingQueue<byte[]> pcmQ = new ArrayBlockingQueue<byte[]>(64);

    private volatile AsrWorker worker = null;
    private volatile Model model = null;
    private volatile String modelLang = "";
    private final Object modelLock = new Object();

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
        String next = MODEL_ZIP.containsKey(l) ? l : "en";
        boolean changed = !next.equals(lang);
        lang = next;
        /* v7.1：播放中切语言 → 重启识别线程换模型（v7.0 只存值不生效，这里顺手修掉） */
        if (changed && enabled) restartWorker();
    }

    public String getLang() { return lang; }

    /* 网页切换开关：on=true 开启（PCM 由网页经 asrPcm 持续推来），false 关闭 */
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

    /* 播放状态变化时（onNowPlaying 带新 URL）调用：
       - 真正换了台 → 清残留 + 重开识别（避免上一台的声学状态串台）；
       - 暂停后恢复（同 URL，每次缓冲恢复都会触发 playing）→ 不重置，继续识别 */
    public synchronized void onStreamChanged(String url) {
        if (url == null || url.isEmpty()) return;
        boolean changed = !url.equals(streamUrl);
        streamUrl = url;
        if (!enabled || !changed) return;
        pcmQ.clear();
        restartWorker();
    }

    /* 暂停/停播时调用：网页已停止投喂，这里只需清掉积压；线程保留空转（开销≈0），
       恢复播放后网页继续推包即自动续上（v7.0 会整个关掉导致恢复后字幕失活，已修） */
    public synchronized void onPlaybackStopped() {
        pcmQ.clear();
    }

    public synchronized void release() {
        enabled = false;
        stopWorker();
        synchronized (modelLock) {
            if (model != null) { try { model.close(); } catch (Throwable ignored) {} model = null; }
        }
    }

    /* ---------------- PCM 投喂（JsBridge.asrPcm 调入，16kHz 单声道 16-bit） ---------------- */
    public void feedPcm(byte[] pcm16k) {
        if (!enabled || pcm16k == null || pcm16k.length == 0) return;
        /* v7.4：延迟钉死 —— 队列积压超过 12 包(≈3s)就丢最老的。
           v7.3 及之前是「满了丢新包」：一旦某次卡顿造成积压，队列常年保持 64 包满载
           （drop-newest 挡住新音频、旧音频压在队头），字幕会永久落后实况最多 16s，
           用户实测就是「字幕慢半拍、跟耳朵对不上」。实时电台宁可直接跳过几分钟前
           的陈旧音频，也要让识别始终对着耳朵正在听的内容 —— 丢包缺口只在卡顿瞬间
           出现（≤3s 窗口），随后立刻追平。 */
        while (pcmQ.size() >= 12) pcmQ.poll();
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

    private synchronized void restartWorker() {
        stopWorker();
        if (enabled) startWorker();
    }

    private synchronized void stopWorker() {
        AsrWorker w = worker;
        worker = null;
        if (w != null) { w.stop = true; pcmQ.clear(); }
    }

    private class AsrWorker extends Thread {
        private volatile boolean stop = false;

        @Override
        public void run() {
            File modelDir = prepareModel(lang);
            if (modelDir == null) { enabled = false; toast("语音模型准备失败，字幕已关闭"); return; }

            Model m;
            synchronized (modelLock) {
                if (model != null && modelLang.equals(lang)) {
                    m = model;
                } else {
                    try {
                        if (model != null) model.close();
                        m = new Model(modelDir.getAbsolutePath());
                        model = m; modelLang = lang;
                    } catch (Throwable t) {
                        Log.e(TAG, "Vosk 模型加载失败: " + t);
                        enabled = false; toast("语音模型加载失败，字幕已关闭");
                        return;
                    }
                }
            }

            Recognizer rec;
            try { rec = new Recognizer(m, (float) TARGET_RATE); }
            catch (Throwable t) { enabled = false; toast("语音识别初始化失败"); return; }

            try {
                while (!stop) {
                    byte[] chunk = pcmQ.poll(250, TimeUnit.MILLISECONDS);
                    if (chunk == null) continue;   /* 暂停/断流：空转等待，开销≈0 */
                    try {
                        boolean isFinal = rec.acceptWaveForm(chunk, chunk.length);
                        emit(isFinal ? rec.getResult() : rec.getPartialResult(), isFinal);
                    } catch (Throwable t) { Log.e(TAG, "acceptWaveForm 失败: " + t); }
                }
                emit(rec.getFinalResult(), true);
            } catch (Throwable t) {
                Log.e(TAG, "识别线程异常: " + t);
            } finally {
                try { rec.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /* 把 Vosk 的 JSON 结果抽出文本并回传主页 */
    private void emit(String voskJson, boolean isFinal) {
        if (voskJson == null) return;
        String text = "";
        try { text = new JSONObject(voskJson).optString(isFinal ? "text" : "partial", ""); } catch (Throwable ignored) {}
        if (text == null) text = "";
        service.callAsrJs(text, isFinal);
    }

    /* ---------------- 模型准备（内置 assets 优先 → 联网兜底 + 解压 + 定位） ----------------
       v7.2：模型 zip 随 APK 打包在 assets/vosk/，首次开启直接解压，全程离线、
       开箱即用（v7.1 及之前靠联网下载，网络不通的用户会「语音模型下载失败」）。
       联网下载仅保留为 assets 缺失时的兜底（如未来出精简版未内置某语言）。 */
    private File prepareModel(String l) {
        File base = new File(appContext.getFilesDir(), "vosk");
        File langDir = new File(base, l);
        File existing = findModelDir(langDir);
        if (existing != null) return existing;
        String zipName = MODEL_ZIP.get(l);
        if (zipName == null) return null;

        /* ① 内置模型：assets/vosk/<zip> → 解压到 filesDir/vosk/<lang> */
        InputStream ain = null;
        try { ain = appContext.getAssets().open("vosk/" + zipName); } catch (Throwable t) { ain = null; }
        if (ain != null) {
            try {
                toast("正在解压内置语音模型（仅首次，约 40MB）…");
                unzip(ain, langDir);
                File r = findModelDir(langDir);
                if (r != null) return r;
            } catch (Throwable t) {
                Log.e(TAG, "内置模型解压失败: " + t);
            } finally {
                try { ain.close(); } catch (Throwable ignored) {}
            }
        }

        /* ② 兜底：assets 里没有该模型时才联网下载（老版本升级 / 精简包场景） */
        toast("正在下载离线语音模型（约 40MB，仅首次）…");
        File zip = new File(base, zipName);
        try {
            download(new URL(MODEL_BASE_URL + zipName), zip);
            FileInputStream fin = new FileInputStream(zip);
            try { unzip(fin, langDir); } finally { try { fin.close(); } catch (Throwable ignored) {} }
            zip.delete();
            return findModelDir(langDir);
        } catch (Throwable t) {
            Log.e(TAG, "模型下载/解压失败: " + t);
            return null;
        }
    }

    private File findModelDir(File langDir) {
        if (langDir == null) return null;
        if (new File(langDir, "conf").exists() || new File(langDir, "am").exists()
            || new File(langDir, "ivector").exists()) return langDir;
        File[] subs = langDir.listFiles();
        if (subs != null) for (File s : subs) {
            if (s.isDirectory() && (new File(s, "conf").exists() || new File(s, "am").exists()))
                return s;
        }
        return null;
    }

    private void download(URL u, File out) throws IOException {
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(0);
        c.connect();
        InputStream in = new BufferedInputStream(c.getInputStream());
        FileOutputStream fos = new FileOutputStream(out);
        byte[] b = new byte[8192];
        int r;
        long last = System.currentTimeMillis(), got = 0;
        while ((r = in.read(b)) > 0) {
            fos.write(b, 0, r);
            got += r;
            if (System.currentTimeMillis() - last > 2000) {
                last = System.currentTimeMillis();
                toast("正在下载离线语音模型… " + (got / 1048576) + "MB");
            }
        }
        fos.close(); in.close(); c.disconnect();
    }

    private void unzip(InputStream in, File destDir) throws IOException {
        destDir.mkdirs();
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in));
        ZipEntry e;
        byte[] buf = new byte[8192];
        while ((e = zis.getNextEntry()) != null) {
            File f = new File(destDir, e.getName());
            if (e.isDirectory()) { f.mkdirs(); continue; }
            f.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(f);
            int r;
            while ((r = zis.read(buf)) > 0) fos.write(buf, 0, r);
            fos.close();
        }
        zis.close();
    }

    private void toast(String msg) { service.callAsrToast(msg); }
}
