package com.waters.radio;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * WATERS RADIO · 语音识别字幕（v7.0，离线 Vosk）
 * ------------------------------------------------------------------
 * 架构（与 ICY 同样的「并行连接」思路——音频实际由 WebView 的 <audio> 播放，
 * 原生层拿不到 WebView 的解码输出，只能自己再解一路）：
 *   1. 网页打开「语音字幕」→ JsBridge.setAsr("1") → 以当前台 URL 再开一条并行连接；
 *   2. MediaExtractor + MediaCodec 把直播流解码成 16-bit PCM；
 *   3. Java 端重采样 / 下混到 16kHz 单声道（Vosk 要求）；
 *   4. 16kHz 单声道 PCM 逐块喂给 Vosk Recognizer（流式）；
 *   5. partial / final 结果经 service.callAsrJs(text, isFinal) 回传主页字幕层。
 *
 * 模型：Vosk 离线模型约 40~50MB，不塞进 APK（避免体积爆炸），首次开启时按需下载到
 *       getFilesDir()/vosk/<lang>，之后完全离线。下载中提示，失败则静默关闭本功能。
 * 能力门控：API < 21（Vosk 原生库要求）或低内存设备 → asrAvailable() 返回 false，
 *       网页端「字幕」整项隐藏，不会误触。
 *
 * ⚠️ 仅支持「直连流」（MP3/AAC/OGG 等），与 ICY 一样跳过 HLS(.m3u8) / 播放列表；
 *   MediaExtractor 对 HLS 音频抓取不稳，且 Vosk 不吃压缩流。
 */
public class AsrController {

    private static final String TAG = "WatersAsr";
    private static final String MODEL_BASE_URL = "https://alphacephei.com/vosk/models/";
    private static final int TARGET_RATE = 16000;

    /* 语言 → 模型 zip 文件名（解压后顶层目录同名） */
    private static final Map<String, String> MODEL_ZIP = new HashMap<>();
    static {
        MODEL_ZIP.put("en", "vosk-model-small-en-us-0.15.zip");   // 英语（小模型 ~40MB）
        MODEL_ZIP.put("zh", "vosk-model-small-zh-cn-0.3.zip");   // 中文（小模型 ~40MB）
    }

    private final RadioPlaybackService service;
    private final Context appContext;

    private volatile boolean enabled = false;
    private volatile String lang = "en";
    private volatile String streamUrl = "";

    private volatile DecodeThread decodeThread = null;
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
        this.lang = MODEL_ZIP.containsKey(l) ? l : "en";
    }

    public String getLang() { return lang; }

    /* 网页切换开关：on="1" 开启（用当前流地址），on="0" 关闭 */
    public synchronized void setEnabled(boolean on, String url) {
        if (on) {
            if (!isAvailable()) { toast("本设备不支持离线语音识别"); return; }
            if (url != null) this.streamUrl = url;
            enabled = true;
            startDecode();
        } else {
            enabled = false;
            stopDecode();
        }
    }

    /* 播放站台变化时（onNowPlaying 带新 URL）调用：若已开启则换流重启识别 */
    public synchronized void onStreamChanged(String url) {
        if (url == null || url.isEmpty()) return;
        if (!enabled) { this.streamUrl = url; return; }
        if (url.equals(this.streamUrl)) return;
        this.streamUrl = url;
        restartDecode();
    }

    /* 停止播放时调用：关掉识别，避免空耗流量与 CPU */
    public synchronized void onPlaybackStopped() {
        enabled = false;
        stopDecode();
    }

    public synchronized void release() {
        enabled = false;
        stopDecode();
        synchronized (modelLock) {
            if (model != null) { try { model.close(); } catch (Throwable ignored) {} model = null; }
        }
    }

    /* ---------------- 解码 + 识别线程 ---------------- */
    private synchronized void startDecode() {
        if (decodeThread != null && decodeThread.isAlive()) return;
        if (streamUrl.isEmpty() || !enabled) return;
        decodeThread = new DecodeThread();
        decodeThread.start();
    }

    private synchronized void restartDecode() {
        stopDecode();
        if (enabled) startDecode();
    }

    private synchronized void stopDecode() {
        if (decodeThread != null) { decodeThread.requestStop(); decodeThread = null; }
    }

    private class DecodeThread extends Thread {
        private volatile boolean stop = false;
        private MediaExtractor extractor;
        private MediaCodec decoder;

        void requestStop() {
            stop = true;
            try { if (extractor != null) extractor.release(); } catch (Throwable ignored) {}
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Throwable ignored) {}
            this.interrupt();
        }

        @Override
        public void run() {
            String url = streamUrl;
            /* 与 ICY 一致：跳过 HLS / 播放列表（MediaExtractor 抓其音频不稳，且 Vosk 不吃压缩流） */
            String u = (url == null ? "" : url.toLowerCase());
            if (u.contains(".m3u8") || u.contains(".m3u") || u.contains(".pls")) {
                enabled = false;
                toast("该格式暂不支持识别（如 HLS 直播）");
                return;
            }
            File modelDir = prepareModel(lang);
            if (modelDir == null) { enabled = false; toast("语音模型下载失败，字幕已关闭"); return; }

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
                extractor = new MediaExtractor();
                extractor.setDataSource(url);
                int track = pickAudioTrack(extractor);
                if (track < 0) { enabled = false; toast("该格式暂不支持识别（如 HLS 直播）"); return; }
                extractor.selectTrack(track);
                MediaFormat fmt = extractor.getTrackFormat(track);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                decoder = MediaCodec.createDecoderByType(mime);
                decoder.configure(fmt, null, null, 0);
                decoder.start();

                /* 源采样率/声道数取自抽取器轨道格式（音频轨道自带，无需等解码器输出格式回调，
                   避免 start() 后立即 getOutputFormat() 在某些机型上抛 IllegalStateException）。
                   MediaCodec 音频解码器不重采样，输出率/声道与输入一致。 */
                int srcRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
                int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

                Resampler res = new Resampler(srcRate, channels, TARGET_RATE);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputDone = false;

                while (!stop) {
                    if (!inputDone) {
                        int inIdx = decoder.dequeueInputBuffer(2000);
                        if (inIdx >= 0) {
                            java.nio.ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                            if (inBuf != null) {
                                int sampleSize = extractor.readSampleData(inBuf, 0);
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    inputDone = true;
                                } else {
                                    decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.getSampleTime(), 0);
                                    extractor.advance();
                                }
                            }
                        }
                    }

                    int outIdx = decoder.dequeueOutputBuffer(info, 2000);
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat outFmt = decoder.getOutputFormat();   // 仅在此回调内有效
                        srcRate = outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : srcRate;
                        channels = outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : channels;
                        res = new Resampler(srcRate, channels, TARGET_RATE);
                        continue;
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        continue;
                    } else if (outIdx >= 0) {
                        java.nio.ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
                        if (outBuf != null && info.size > 0) {
                            byte[] pcm;
                            try {
                                outBuf.position(info.offset);
                                outBuf.limit(info.offset + info.size);
                                pcm = new byte[info.size];
                                outBuf.get(pcm);
                            } finally {
                                decoder.releaseOutputBuffer(outIdx, false);
                            }
                            res.put(pcm);
                            drainVosk(rec, res);
                        } else {
                            decoder.releaseOutputBuffer(outIdx, false);
                        }
                    }
                    /* 直播流断流（inputDone 且无新输出）则退出，等 onStreamChanged 重启 */
                    if (inputDone) {
                        int again = decoder.dequeueOutputBuffer(info, 500);
                        if (again < 0) break;
                    }
                }
                drainVosk(rec, res);
                emit(rec.getFinalResult(), true);
            } catch (Throwable t) {
                Log.e(TAG, "解码/识别异常: " + t);
            } finally {
                try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Throwable ignored) {}
                try { if (extractor != null) extractor.release(); } catch (Throwable ignored) {}
                try { rec.close(); } catch (Throwable ignored) {}
            }
        }

        private void drainVosk(Recognizer rec, Resampler res) {
            while (res.hasData()) {
                byte[] chunk = res.take(8000);   // 0.5s @16k
                if (chunk == null) break;
                try {
                    boolean isFinal = rec.acceptWaveForm(chunk, chunk.length);
                    emit(isFinal ? rec.getResult() : rec.getPartialResult(), isFinal);
                } catch (Throwable t) { Log.e(TAG, "acceptWaveform 失败: " + t); }
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

    /* ---------------- 模型准备（下载 + 解压 + 定位） ---------------- */
    private File prepareModel(String l) {
        File base = new File(appContext.getFilesDir(), "vosk");
        File langDir = new File(base, l);
        File existing = findModelDir(langDir);
        if (existing != null) return existing;
        String zipName = MODEL_ZIP.get(l);
        if (zipName == null) return null;
        toast("正在下载离线语音模型（约 40MB，仅首次）…");
        File zip = new File(base, zipName);
        try {
            download(new URL(MODEL_BASE_URL + zipName), zip);
            unzip(zip, langDir);
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

    private void unzip(File zip, File destDir) throws IOException {
        destDir.mkdirs();
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zip)));
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

    /* 选第一个 audio/* 轨道；跳过 HLS/播放列表（返回 -1 让上层提示不支持） */
    private static int pickAudioTrack(MediaExtractor ex) {
        int n = ex.getTrackCount();
        for (int i = 0; i < n; i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return -1;
    }

    /* ---------------- 重采样 / 下混：任意采样率+声道 → 16kHz 单声道 ---------------- */
    private static class Resampler {
        private final int srcRate, channels, dstRate;
        private final java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
        private double pos = 0.0;
        private double lastSample = 0.0;

        Resampler(int srcRate, int channels, int dstRate) {
            this.srcRate = srcRate; this.channels = Math.max(1, channels); this.dstRate = dstRate;
        }

        /* 输入：解码出的 16-bit 交织 PCM（channels 个声道），转成目标 16kHz 单声道并缓存 */
        synchronized void put(byte[] pcm16) {
            int samples = pcm16.length / 2 / channels;
            for (int i = 0; i < samples; i++) {
                int mono = 0;
                for (int ch = 0; ch < channels; ch++) {
                    int off = (i * channels + ch) * 2;
                    short s = (short) ((pcm16[off] & 0xff) | ((pcm16[off + 1] & 0xff) << 8));
                    mono += s;
                }
                mono /= channels;
                double cur = mono;
                double step = (double) dstRate / srcRate;
                while (pos < i + 1) {
                    double frac = pos - i;
                    double v = (i == 0) ? cur : (lastSample + (cur - lastSample) * Math.max(0, Math.min(1, frac)));
                    short out = (short) Math.max(-32768, Math.min(32767, v));
                    acc.write(out & 0xff); acc.write((out >> 8) & 0xff);
                    pos += step;
                }
                lastSample = cur;
            }
        }

        synchronized boolean hasData() { return acc.size() > 0; }

        /* 取出最多 maxBytes（对齐到偶数）字节的 16kHz 单声道 PCM */
        synchronized byte[] take(int maxBytes) {
            byte[] all = acc.toByteArray();
            if (all.length == 0) return null;
            int n = Math.min(all.length, maxBytes - (maxBytes % 2));
            byte[] out = new byte[n];
            System.arraycopy(all, 0, out, 0, n);
            acc.reset();
            if (n < all.length) acc.write(all, n, all.length - n);
            return out;
        }
    }
}
