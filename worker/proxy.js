/**
 * WATERS RADIO · HTTPS 流代理（Cloudflare Worker）
 * ------------------------------------------------------------------
 * 作用：把电台库里的 http:// 明文直播流，通过本 Worker 转成 https:// ，
 *       从而绕过浏览器的「混合内容拦截」（Mixed Content Blocking）。
 *
 * 用法：
 *   部署后得到形如  https://waters-radio-proxy.你的账号.workers.dev
 *   播放 http://lhttp.qingting.fm/live/270/64k.mp3 时，改成
 *   https://waters-radio-proxy.你的账号.workers.dev/?u=<URL编码后的原始地址>
 *
 * 支持：
 *   · Range 请求透传（音频拖动 / 边下边播）
 *   · m3u8 / 播放列表：自动把里面的分片、子清单地址也改写成走本代理
 *   · CORS 头，允许任意来源的网页调用
 *   · 只允许 http/https 目标，拒绝内网地址（避免被当作开放代理滥用）
 */

const ALLOWED_SCHEMES = ['http:', 'https:'];

/** 判断是否是需要重写内容地址的播放列表 */
function isPlaylist(contentType, url) {
  if (!contentType) return false;
  const ct = contentType.toLowerCase();
  if (ct.includes('mpegurl') || ct.includes('m3u')) return true;
  return /\.m3u8?($|\?)/i.test(url || '');
}

/** 把播放列表里的相对/绝对地址改写成走本代理 */
function rewritePlaylist(text, baseUrl, proxyOrigin) {
  return text.split('\n').map((line) => {
    const trimmed = line.trim();
    // 空行、注释行（#EXT-X-...）原样保留，但 #EXT-X-KEY / #EXT-X-MAP 里的 URI 需要重写
    if (trimmed === '') return line;

    if (trimmed.startsWith('#')) {
      // 处理带 URI="..." 的标签（加密密钥、初始化段等）
      return line.replace(/URI="([^"]+)"/g, (m, uri) => {
        const abs = toAbsolute(uri, baseUrl);
        return `URI="${encodeURIComponent(proxyOrigin + '/?u=' + abs)}"`;
      });
    }

    // 普通地址行：相对地址先转绝对，再包一层代理
    const abs = toAbsolute(trimmed, baseUrl);
    return proxyOrigin + '/?u=' + encodeURIComponent(abs);
  }).join('\n');
}

/** 把可能是相对的地址补成绝对地址 */
function toAbsolute(u, baseUrl) {
  try {
    return new URL(u, baseUrl).toString();
  } catch (e) {
    return u;
  }
}

/** 目标地址是否安全（禁止内网 / 保留地址） */
function isSafeTarget(u) {
  let url;
  try {
    url = new URL(u);
  } catch (e) {
    return false;
  }
  if (!ALLOWED_SCHEMES.includes(url.protocol)) return false;

  const host = url.hostname.toLowerCase();
  // 拒绝明显的内网 / 回环地址
  if (host === 'localhost' || host === '127.0.0.1' || host === '0.0.0.0') return false;
  if (/^10\./.test(host)) return false;
  if (/^192\.168\./.test(host)) return false;
  if (/^172\.(1[6-9]|2\d|3[01])\./.test(host)) return false;
  if (/^169\.254\./.test(host)) return false;
  if (host.endsWith('.internal') || host.endsWith('.local')) return false;
  return true;
}

/** 统一的 CORS 响应头 */
function corsHeaders(extra) {
  return Object.assign(
    {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
      'Access-Control-Allow-Headers': 'Range, Content-Type, Origin, Accept',
      'Access-Control-Expose-Headers':
        'Content-Length, Content-Range, Content-Type, Accept-Ranges',
      'Access-Control-Max-Age': '86400',
    },
    extra || {}
  );
}

export default {
  async fetch(request) {
    const reqUrl = new URL(request.url);

    // 预检
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    // 目标地址：?u=<原始地址>，也兼容 /http://xxx 这种路径写法
    let target = reqUrl.searchParams.get('u');
    if (target) {
      try {
        target = decodeURIComponent(target);
      } catch (e) {
        /* 保持原样 */
      }
    } else {
      // 支持 https://worker域名/http://example.com/xxx 形式
      target = request.url.slice(request.url.indexOf('/', 8) + 1);
    }

    if (!target) {
      return new Response(
        'WATERS RADIO 代理已就绪。用法：/?u=<URL编码后的目标地址>',
        { status: 200, headers: corsHeaders({ 'Content-Type': 'text/plain; charset=utf-8' }) }
      );
    }

    if (!isSafeTarget(target)) {
      return new Response('目标地址不被允许', {
        status: 403,
        headers: corsHeaders({ 'Content-Type': 'text/plain; charset=utf-8' }),
      });
    }

    // 构造转发请求：只带必要头，避免把浏览器的 Origin/Referer 透传给源站触发防盗链
    const fwdHeaders = new Headers();
    const passThrough = ['range', 'accept', 'accept-language', 'user-agent', 'icy-metadata'];
    passThrough.forEach((h) => {
      const v = request.headers.get(h);
      if (v) fwdHeaders.set(h, v);
    });
    // 部分 CDN 需要合法 UA 才放行
    if (!fwdHeaders.get('user-agent')) {
      fwdHeaders.set(
        'user-agent',
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
      );
    }

    let upstream;
    try {
      upstream = await fetch(target, {
        method: request.method === 'HEAD' ? 'HEAD' : 'GET',
        headers: fwdHeaders,
        redirect: 'follow',
      });
    } catch (e) {
      return new Response('上游请求失败：' + (e && e.message ? e.message : e), {
        status: 502,
        headers: corsHeaders({ 'Content-Type': 'text/plain; charset=utf-8' }),
      });
    }

    const contentType = upstream.headers.get('content-type') || '';

    // 播放列表：读全文，重写里面的地址，再返回
    if (isPlaylist(contentType, target)) {
      const text = await upstream.text();
      const proxyOrigin = reqUrl.origin;
      const rewritten = rewritePlaylist(text, target, proxyOrigin);
      return new Response(rewritten, {
        status: upstream.status,
        headers: corsHeaders({
          'Content-Type': 'application/vnd.apple.mpegurl',
          'Cache-Control': 'no-cache',
        }),
      });
    }

    // 音频流：原样流式回传，保留 Range / 长度信息
    const respHeaders = new Headers(corsHeaders());
    ['content-type', 'content-length', 'content-range', 'accept-ranges', 'cache-control', 'icy-name', 'icy-br']
      .forEach((h) => {
        const v = upstream.headers.get(h);
        if (v) respHeaders.set(h, v);
      });

    return new Response(upstream.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: respHeaders,
    });
  },
};
