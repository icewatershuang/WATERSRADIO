#!/usr/bin/env python3
# =============================================================================
# WATERS RADIO · v8.0.1 修复：把 libonnxruntime.so 的 ELF 最小 SDK 注记从 27 降到 21
# -----------------------------------------------------------------------------
# 现象：v8.0 在部分真机（Android < 8.1 / API 27）开启字幕时弹
#       「语音识别初始化失败」。根因：sherpa-onnx 1.13.8 AAR 内置的
#       libonnxruntime.so 的 .note.android min SDK = 27，Android linker 在
#       设备 API < 27 时直接拒绝加载该库（"has min SDK version 27 but the
#       device is XX"），System.loadLibrary 抛 UnsatisfiedLinkError，被
#       AsrController 的 catch(Throwable) 捕获 → 该 toast。
#
# 该库实际引用的 libc 符号全是 API 1/23 级（clock_gettime 自 API 23 起在
# libc，__system_property_get/fcntl 等为 API 1），并无真正需要 API 27 的符号，
# 故把注记降到 21（与同包 sherpa 库及 app minSdk 对齐）即可在 API 21+ 正常加载。
#
# 用法：
#   python patch_onnx_minapi.py <file.so>            # 直接改 .so（测试用）
#   python patch_onnx_minapi.py <file.aar>           # 改 AAR 内所有 libonnxruntime.so
# =============================================================================
import sys, struct, zipfile, os, io

TARGET_MIN_API = 21

def read_fields(d, is64):
    en = '<' if d[5] == 1 else '>'
    if is64:
        e_shoff = struct.unpack_from(en+'Q', d, 40)[0]
        e_shentsize = struct.unpack_from(en+'H', d, 58)[0]
        e_shnum = struct.unpack_from(en+'H', d, 60)[0]
        e_shstrndx = struct.unpack_from(en+'H', d, 62)[0]
    else:
        e_shoff = struct.unpack_from(en+'I', d, 32)[0]
        e_shentsize = struct.unpack_from(en+'H', d, 46)[0]
        e_shnum = struct.unpack_from(en+'H', d, 48)[0]
        e_shstrndx = struct.unpack_from(en+'H', d, 50)[0]
    return e_shoff, e_shentsize, e_shnum, e_shstrndx

def find_note_android_minapi(d):
    """Return (abs_offset_of_desc_first_uint32, current_value) or None."""
    if d[:4] != b'\x7fELF':
        return None
    is64 = (d[4] == 2); en = '<' if d[5]==1 else '>'
    shoff, shs, shnum, shstr = read_fields(d, is64)
    sh=[]
    for i in range(shnum):
        o=shoff+i*shs
        if is64:
            (nm,ty,fl,ad,of,sz,ln,inf,al,es)=struct.unpack_from(en+'IIQQQQIIQQ', d, o)
        else:
            (nm,ty,fl,ad,of,sz,ln,inf,al,es)=struct.unpack_from(en+'IIIIIIIIII', d, o)
        sh.append(dict(nm=nm,ty=ty,of=of,sz=sz,es=es))
    sst=sh[shstr]
    def sname(i):
        e=d.index(0,sst['of']+i); return d[sst['of']+i:e]
    align=8 if is64 else 4
    for s in sh:
        if s['ty']==7:
            off=s['of']; end=off+s['sz']
            while off+12<=end:
                namesz,descsz,ntype=struct.unpack_from('<III', d, off)
                off+=12
                name=d[off:off+namesz].rstrip(b'\x00')
                off+=(namesz+align-1)//align*align
                desc_off=off
                off+=(descsz+align-1)//align*align
                if name==b'Android' and ntype==1 and descsz>=4:
                    val=struct.unpack_from('<I', d, desc_off)[0]
                    return (desc_off, val)
    return None

def patch_so(data):
    """Patch min SDK note 27->TARGET_MIN_API. Returns (new_data, old_val, new_val, changed)."""
    hit = find_note_android_minapi(data)
    if hit is None:
        return (data, None, None, False)
    off, old = hit
    if old is None or old <= TARGET_MIN_API:
        return (data, old, old, False)
    new = bytearray(data)
    struct.pack_into('<I', new, off, TARGET_MIN_API)
    return (bytes(new), old, TARGET_MIN_API, True)

def patch_aar(aar_path):
    with zipfile.ZipFile(aar_path, 'r') as z:
        infos = z.infolist()
        entries = [(info, z.read(info.filename)) for info in infos]
    changed_any = False
    report = []
    out = []
    for info, b in entries:
        if info.filename.endswith('libonnxruntime.so'):
            new, old, nv, ch = patch_so(b)
            if ch:
                changed_any = True
                report.append(f"  patched {info.filename}: min API {old} -> {nv}")
                # write modified bytes with same compression as original
                ni = zipfile.ZipInfo(info.filename)
                ni.compress_type = info.compress_type
                ni.external_attr = info.external_attr
                out.append((ni, new))
            else:
                report.append(f"  (skip) {info.filename}: min API {old} (<= {TARGET_MIN_API})")
                out.append((info, b))
        else:
            out.append((info, b))
    if changed_any:
        tmp = aar_path + '.tmp'
        with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_STORED) as z:
            for info, b in out:
                if isinstance(info, zipfile.ZipInfo):
                    z.writestr(info, b)
                else:
                    z.writestr(info, b, compress_type=info.compress_type)
        os.replace(tmp, aar_path)
    return report, changed_any

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("usage: patch_onnx_minapi.py <file.so | file.aar>"); sys.exit(2)
    p = sys.argv[1]
    if p.endswith('.so'):
        d0 = open(p,'rb').read()
        hit = find_note_android_minapi(d0)
        print(f"[before] {p}: min SDK = {hit[1] if hit else 'N/A'}")
        new, old, nv, ch = patch_so(d0)
        if ch:
            open(p,'wb').write(new)
            print(f"[after ] patched -> min SDK {nv}")
        else:
            print(f"[after ] no change (was {old})")
    elif p.endswith('.aar'):
        rep, ch = patch_aar(p)
        for line in rep: print(line)
        print("AAR patched:" , ch)
    else:
        print("unsupported file type"); sys.exit(2)
