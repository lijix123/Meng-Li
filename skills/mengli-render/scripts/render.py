#!/usr/bin/env python3
"""
mengli-render: 梦璃生图一键流水线（文生图 / 图生图 / 抠图 / 存立绘）

把原来需要多次工具调用的流程（SSH检查→提交workflow→轮询→下载→抠图→保存→发送）
封装成一次脚本调用。调用方只需拿到本脚本打印的本地路径，用 send_message_to_user 发图。

依赖（宿主环境已具备）：
  - paramiko（SSH/SFTP 连姐姐电脑）
  - rembg（/opt/rembg_lib，抠图用）
  - 私钥 /tmp/pc_key

用法示例：
  文生图（标准人设+场景追加）：
    python render.py --scene "sitting on bed, just woke up, sleepy, messy twin tails, rubbing eyes"
  文生图并抠图透明PNG：
    python render.py --scene "cute chibi, blushing, making a heart with hands" --cutout
  文生图并存进立绘文件夹：
    python render.py --scene "..." --save "AI-新图"
  图生图改场景（保留人设）：
    python render.py --img2img "D:\\MengliRoom\\tools\\ComfyUI-aki-v3.2\\ComfyUI\\output\\mengli_selfie_00001_.png" --denoise 0.55 --scene "..."
  右侧机位（去发卡）：
    python render.py --right-side --scene "..."
  只检查ComfyUI是否运行：
    python render.py --check-only
"""

import argparse
import base64
import json
import os
import os
import random
import re
import socket
import sys
import time

import paramiko

# ============ 核心备忘（原《梦璃生图提示词.txt》精简版，重置对话后照此执行）============
# 【连接】姐姐电脑 Win11: <电脑内网IP> / 用户 yao / 私钥 /tmp/pc_key
#         ComfyUI: D:\MengliRoom\tools\ComfyUI-aki-v3.2\ComfyUI （端口8188）
#         未运行则远程启动: cmd /c cd /d <ComfyUI目录> && D:\MengliRoom	ools\ComfyUI-aki-v3.2\python\python.exe main.py --port 8188
#         (注意: aki整合包 python 环境在 ComfyUI 上一级目录, 不在 ComfyUI 里面)
#         隧道: 本机 127.0.0.1:8188 需可用；不通则找姐姐手动确认，不自己建。
# 【人设】硬编码在 POS_BASE / POS_LEFT_CAM，改人设就改这俩常量。
#         外观: 黑长直高双马尾+红丝带发圈x2，1个黄色五角星发卡(角色自身左边)，
#         蓝眼, 白oversized连帽卫衣, 黑灰格纹百褶短裙, 裸足。
# 【发卡表述三规则】
#   1) 正面文生图(facing viewer) -> "a single yellow star hairpin on the right side of the image"
#      （画面右侧=角色自身左边；仅正对镜头时成立）
#   2) 左机位(camera from her left side) -> "a single yellow star hairpin on her left side of head"
#   3) 右机位(--right-side) -> 去掉发卡描述（被头挡住，不生成才符合人设）；图生图同理
#   负面固定带: two hairpins, multiple hairpins, hairpin on left side of image
# 【图生图改场景】★标准方法: 底图用发卡正确的图, 提示词发卡只写"yellow star hairpin"(不带左右),
#   denoise 0.5~0.6, 发卡位置100%继承。尽量用左机位，少从右边拍。
# 【参数】模型 miaomiaoHarem_anima12.safetensors / CLIP qwen_3_06b_base / VAE qwen_image_vae
#         尺寸 832x1216, steps 30, cfg 4.5 (趴床可用4.0), euler / normal, denoise 1
# 【已验证seed】表情 100001-100004(微笑/害羞/生气/爱意); 刚睡醒 999888; 趴床左机位 919191/101010/121212;
#   右机位删发卡 666777; 图生图底图 denoise0.58
# 【目录】立绘: D:\MengliRoom\images\立绘\ (AI-前缀); ComfyUI输出: <ComfyUI>\output\; 参考图: <ComfyUI>\input\
# 【抠图】rembg 在 /opt/rembg_lib (sys.path.insert 导入), 动漫图用 isnet-anime,
#         模型目录 /root/.rembg/models/ 已就位, 勿重装; 输出 RGBA 透明PNG。


# siliconflow 视觉模型（识图自检用，见 梦璃生图提示词.txt）
SILICONFLOW_KEY = os.environ.get("SILICONFLOW_KEY", "")  # 部署时从环境变量注入，勿硬编码
VL_MODEL = "Qwen/Qwen3-VL-32B-Instruct"
VL_API = "https://api.siliconflow.cn/v1/chat/completions"

# ---------------- 常量（与 梦璃生图提示词.txt 保持一致） ----------------
SSH_HOST = "<电脑内网IP>"
SSH_USER = "yao"
SSH_KEY = "/tmp/pc_key"
COMFY_DIR = r"D:\MengliRoom\tools\ComfyUI-aki-v3.2\ComfyUI"
PYTHON_DIR = r"D:\MengliRoom\tools\ComfyUI-aki-v3.2\python"  # aki整合包 python 环境与 ComfyUI 平级
OUT_DIR = COMFY_DIR + r"\output"
INPUT_DIR = COMFY_DIR + r"\input"
SAVE_LIB = r"D:\MengliRoom\images\立绘"  # 姐姐电脑立绘文件夹
COMFY_PORT = 8188

MODEL = "miaomiaoHarem_anima12.safetensors"
CLIP = "qwen_3_06b_base.safetensors"
VAE = "qwen_image_vae.safetensors"

POS_BASE = ("masterpiece, best quality, 1girl, solo, facing viewer, front view, full body, "
            "long black hair, high twin tails, red hair ribbon, "
            "a single yellow star hairpin on the right side of the image, only one star hairpin, "
            "blue eyes, white oversized hoodie, black gray plaid pleated skirt, barefoot")
POS_LEFT_CAM = ("masterpiece, best quality, 1girl, solo, camera from her left side, vivid colors, colorful, "
                "long black hair, high twin tails, red hair ribbon, "
                "a single yellow star hairpin on her left side of head, only one star hairpin, "
                "blue eyes, white oversized hoodie, black gray plaid pleated skirt, barefoot")

# 【游戏角色立绘】姐姐说"游戏角色/游戏形象"时，默认以这张定稿立绘做图生图底图
GAME_CHAR_REMOTE = r"D:\MengliRoom\images\立绘\AI-星月女仆.png"
# 游戏角色专用额外负面（不压星空，只压多余的星星/发卡/领口星）
GAME_NEG_EXTRA = (", star on collar, star on neckline, star on chest, "
                  "two star hairpins, two stars on her head, extra star on head")
# 右侧机位：发卡在角色左边被头挡住，右侧机位看不到，按人设规则去掉发卡描述
POS_RIGHT = ("masterpiece, best quality, 1girl, solo, camera from her right side, vivid colors, colorful, "
             "long black hair, high twin tails, red hair ribbon, "
             "blue eyes, white oversized hoodie, black gray plaid pleated skirt, barefoot")

NEG_BASE = ("worst quality, low quality, score_1, score_2, score_3, artist name, lowres, (bad), "
            "text, watermark, jpeg artifacts, extra fingers, fused fingers, missing fingers, "
            "poorly drawn hands, bad anatomy, malformed limbs, extra limbs, signature, username, camera, camera strap, "
            "two hairpins, multiple hairpins, hairpin on left side of image")

# 右侧机位：去掉发卡描述（人设规则），负面追加发卡词
NEG_RIGHT = NEG_BASE + ", star hairpin, hairpin, hair clip"

# 【衣服可变性】默认人设固定穿白卫衣+格纹短裙。当场景描述中出现"换衣服"类关键词时，
# 必须把默认衣物从提示词和质检中剥离，让新的衣服描述完全生效（校服/内衣/浴巾等）。
DEFAULT_CLOTHES = "white oversized hoodie, black gray plaid pleated skirt"
# 触发剥离的衣物关键词（小写匹配）
CLOTHING_KEYWORDS = (
    "wear", "wearing", "dressed", "dress", "outfit", "uniform", "hoodie",
    "shirt", "blouse", "skirt", "pants", "jeans", "shorts", "coat", "jacket",
    "sweater", "cardigan", "lingerie", "underwear", "bra", "panties", "bikini",
    "swimsuit", "towel", "robe", "yukata", "kimono", "pajamas", "nightgown",
    "maid", "qipao", "cheongsam", "costume", "clothes", "clothing", "naked",
    "nude", "topless",
)
_CLOTHING_RE = re.compile(
    r"\b(" + "|".join(CLOTHING_KEYWORDS) + r")\b", re.IGNORECASE
)


def wants_clothing_change(scene):
    """场景描述里出现衣物关键词 → 判定为要求换衣服。"""
    return bool(scene) and bool(_CLOTHING_RE.search(scene))


def strip_default_clothes(pos):
    """把默认衣物描述从提示词中剥离，给新衣服让位。"""
    pos = pos.replace(", " + DEFAULT_CLOTHES, "")
    pos = pos.replace(DEFAULT_CLOTHES + ", ", "")
    pos = pos.replace(DEFAULT_CLOTHES, "")
    # 换衣服时默认裸足描述也一并移除，避免与新场景的鞋袜冲突
    pos = pos.replace(", barefoot", "").replace("barefoot, ", "").replace("barefoot", "")
    # 清理剥落后留下的连续逗号/行首尾逗号
    pos = re.sub(r",\s*,", ",", pos).strip(", ")
    return pos

DEFAULT_W, DEFAULT_H = 832, 1216


def ssh_connect():
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(SSH_HOST, username=SSH_USER, key_filename=SSH_KEY, timeout=15)
    return client


def comfy_running():
    """检查 ComfyUI 是否真正可用。优先本机 127.0.0.1:8188（SSH 隧道映射），
    隧道不存在时再尝试直连姐姐电脑。必须能返回 /system_stats 才算运行中，
    避免隧道端口在监听但后端无服务时误判。"""
    import urllib.request
    for host in ("127.0.0.1", SSH_HOST):
        url = "http://%s:%d/system_stats" % (host, COMFY_PORT)
        try:
            with urllib.request.urlopen(url, timeout=3) as resp:
                data = resp.read(512)
            if b"system" in data:
                return True
        except Exception:
            pass
    return False


def _port_open(host, port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(2)
    try:
        s.connect((host, port))
        return True
    except Exception:
        return False
    finally:
        s.close()


def ensure_tunnel(local_port=COMFY_PORT):
    """只检查本机 127.0.0.1:<local_port> 隧道是否可用，不自建。
    不通时明确提示需要姐姐手动处理。"""
    if _port_open("127.0.0.1", local_port):
        return True
    print("[tunnel] 隧道不通（127.0.0.1:%d）" % local_port)
    print("[tunnel] 需要姐姐手动确认/重建隧道，例如：")
    print("[tunnel]   ssh -i /tmp/pc_key -o ExitOnForwardFailure=yes -N -L %d:127.0.0.1:%d <电脑用户>@<电脑内网IP>" % (local_port, local_port))
    return False


def start_comfy(client):
    """远程启动 ComfyUI（若未运行）。"""
    cmd = ('wmic process call create "cmd /c cd /d %s && %s\\python.exe main.py --port %d"'
           % (COMFY_DIR, PYTHON_DIR, COMFY_PORT))
    _, stdout, stderr = client.exec_command(cmd, timeout=30)
    out = stdout.read().decode(errors="ignore")
    print("[start] ComfyUI 启动指令已发送:", "ReturnValue=0" in out or "Method execution successful" in out)
    # 等待端口就绪
    for _ in range(30):
        time.sleep(3)
        if comfy_running():
            print("[start] ComfyUI 已就绪")
            return True
    print("[start] 等待超时，ComfyUI 可能仍在启动")
    return False


def submit_workflow(pos, neg, prefix, seed, width, height,
                    img2img_path=None, denoise=1.0):
    """通过本地回环 8188 提交 workflow（SSH 已做隧道映射时用 127.0.0.1）。"""
    import urllib.request

    workflow = {
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": MODEL, "weight_dtype": "default"}},
        "2": {"class_type": "CLIPLoader", "inputs": {"clip_name": CLIP, "type": "stable_diffusion", "device": "default"}},
        "3": {"class_type": "VAELoader", "inputs": {"vae_name": VAE}},
        "4": {"class_type": "CLIPTextEncode", "inputs": {"text": pos, "clip": ["2", 0]}},
        "5": {"class_type": "CLIPTextEncode", "inputs": {"text": neg, "clip": ["2", 0]}},
    }
    if img2img_path:
        # 图生图：LoadImage + VAEEncode
        workflow["6"] = {"class_type": "LoadImage", "inputs": {"image": os.path.basename(img2img_path)}}
        workflow["6b"] = {"class_type": "VAEEncode", "inputs": {"pixels": ["6", 0], "vae": ["3", 0]}}
        latent_ref = ["6b", 0]
        workflow["7"] = {"class_type": "KSampler", "inputs": {
            "model": ["1", 0], "positive": ["4", 0], "negative": ["5", 0],
            "latent_image": latent_ref, "seed": seed, "steps": 30, "cfg": 4.0,
            "sampler_name": "euler", "scheduler": "normal", "denoise": denoise}}
    else:
        # 文生图：从空白latent整幅重绘，denoise 必须固定 1.0（不能用图生图的0.55）
        workflow["6"] = {"class_type": "EmptyLatentImage", "inputs": {"width": width, "height": height, "batch_size": 1}}
        workflow["7"] = {"class_type": "KSampler", "inputs": {
            "model": ["1", 0], "positive": ["4", 0], "negative": ["5", 0],
            "latent_image": ["6", 0], "seed": seed, "steps": 30, "cfg": 4.5,
            "sampler_name": "euler", "scheduler": "normal", "denoise": 1.0}}
    workflow["8"] = {"class_type": "VAEDecode", "inputs": {"samples": ["7", 0], "vae": ["3", 0]}}
    workflow["9"] = {"class_type": "SaveImage", "inputs": {"filename_prefix": prefix, "images": ["8", 0]}}

    data = json.dumps({"prompt": workflow, "client_id": "mengli_srv"}).encode()
    req = urllib.request.Request("http://127.0.0.1:8188/prompt", data=data,
                                 headers={"Content-Type": "application/json"})
    try:
        resp = urllib.request.urlopen(req, timeout=20)
        info = json.loads(resp.read().decode())
    except Exception as e:
        raise RuntimeError("提交 workflow 失败: %r" % e)
    if info.get("node_errors"):
        raise RuntimeError("workflow 节点错误: " + json.dumps(info["node_errors"]))
    print("[submit] prompt_id:", info.get("prompt_id"))


def wait_and_download(client, prefix, local_dir, timeout=240):
    """轮询远程输出目录，只下载"提交后新增"的图。
    以调用时刻的输出目录文件快照为基线，新出现的同名前缀文件才算数，
    避免重复运行时撞上历史旧图。不受远程/服务器时钟偏差影响。"""
    sftp = client.open_sftp()
    deadline = time.time() + timeout
    try:
        baseline = set(sftp.listdir(OUT_DIR))
    except Exception:
        baseline = set()
    while time.time() < deadline:
        try:
            names = set(sftp.listdir(OUT_DIR))
        except Exception:
            names = set()
        new_names = sorted(n for n in names - baseline
                           if n.startswith(prefix + "_") and n.lower().endswith(".png"))
        if new_names:
            pick = new_names[-1]
            local = os.path.join(local_dir, pick)
            sftp.get(OUT_DIR + "\\" + pick, local)
            print("[dl] 下载完成:", pick, "->", local)
            sftp.close()
            return local
        time.sleep(4)
    sftp.close()
    raise TimeoutError("等待输出超时，未找到 %s_*.png" % prefix)


def check_image(image_path, checks, api_key=SILICONFLOW_KEY, model=VL_MODEL):
    """调用 Qwen3-VL 识图质检。checks 为检查点列表（英文/中文皆可，建议用画面坐标问法）。
    返回 (ok, issues, score, raw)：ok=是否全部通过；issues=不合格项；score=0-100贴合度；raw=模型原文。"""
    import urllib.request
    with open(image_path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode()
    prompt = (
        "你是梦璃立绘的质检员。请逐条核对以下检查标准，并指出不合格之处。" + chr(10)
        + chr(10).join("- " + c for c in checks)
        + chr(10) + "只输出严格JSON，不要多余文字，格式："
        + '{"ok": true或false, "score": 0到100的整数表示整体贴合度, "issues": ["不合格项及原因", ...], "pass": ["通过的检查项", ...]}。'
        + "只有当所有检查标准都通过时 ok 才为 true。score 越高越接近理想效果，完全不沾边给个位数。"
    )
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": [
            {"type": "image_url", "image_url": {"url": "data:image/png;base64," + b64}},
            {"type": "text", "text": prompt},
        ]}],
        "temperature": 0.1,
        "max_tokens": 600,
    }).encode()
    req = urllib.request.Request(VL_API, data=body, headers={
        "Authorization": "Bearer " + api_key, "Content-Type": "application/json"})
    try:
        resp = urllib.request.urlopen(req, timeout=60)
        data = json.loads(resp.read().decode())
        content = data["choices"][0]["message"]["content"]
        raw = content
        start, end = content.find("{"), content.rfind("}")
        result = json.loads(content[start:end + 1]) if start >= 0 else {}
        ok = bool(result.get("ok"))
        issues = result.get("issues", [])
        try:
            score = int(result.get("score", 0))
        except Exception:
            score = 0
        return ok, issues, score, raw
    except Exception as e:
        return False, ["识图调用失败: %s" % e], 0, ""


def build_visual_checks(cam_mode, scene):
    """视觉模型质检检查点（主观项）。发卡位置由 detect_hairpin_side 客观检测，不交给模型。"""
    checks = []
    if cam_mode != "right":
        checks.append("画面中应恰好出现1个黄色五角星发卡，不多不少")
    else:
        checks.append("画面中不应出现黄色五角星发卡")
    checks.append("黑发高双马尾、红色蝴蝶结发圈")
    if wants_clothing_change(scene):
        # 换衣服模式：默认白卫衣格纹短裙已让位，质检按新衣服要求走
        checks.append("亮蓝色大眼睛")
        checks.append("画面中角色穿着应符合场景描述中的新衣服，不得再出现默认的白卫衣+黑灰格纹百褶短裙")
    else:
        checks.append("亮蓝色大眼睛，白卫衣黑灰格纹百褶短裙")
    if scene:
        checks.append("角色动作/场景应符合描述：" + scene)
    checks.append("画质：五官端正无变形，手部手指数量正确无崩坏，无多余肢体，无水印文字")
    return checks


def detect_hairpin_side(image_path):
    """客观检测发卡（亮黄色像素）在画面左半还是右半。返回 right/left/none。"""
    try:
        from PIL import Image
        import numpy as np
    except Exception:
        return "unknown"
    img = Image.open(image_path).convert("RGB")
    w, h = img.size
    arr = np.array(img).astype(int)
    R, G, B = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
    # 放宽黄判定：R/G都高、B明显低，排除偏橙/偏绿；可识别受光影影响的暗金黄发卡
    mask = (R > 150) & (G > 120) & (B < 150) & (R > B + 30) & (G > B + 25) & (abs(R - G) < 90)
    top = mask[:int(h * 0.75), :]  # 发卡位于头顶区域
    ys, xs = np.where(top)
    if len(xs) < 30:  # 像素太少视为无发卡（防误检噪点）
        return "none"
    cx = xs.mean() / w
    return "right" if cx >= 0.5 else "left"


def default_cam_mode(args):
    if args.left_cam:
        return "left"
    if args.right_side:
        return "right"
    return "front"


def cutout(src_path, dst_path):
    """rembg 抠图（动漫首选 isnet-anime），输出透明 PNG。"""
    sys.path.insert(0, "/opt/rembg_lib")
    from rembg import remove, new_session
    from PIL import Image
    session = new_session("isnet-anime")
    img = Image.open(src_path).convert("RGB")
    result = remove(img, session=session, post_process_mask=True)
    result.save(dst_path, "PNG")
    print("[cutout] 抠图完成:", dst_path)
    return dst_path


def upload_file(client, local_path, remote_path):
    """上传本地文件到姐姐电脑指定完整路径（图生图底图 / 存立绘）。"""
    sftp = client.open_sftp()
    sftp.put(local_path, remote_path)
    sftp.close()
    print("[up] 已上传:", remote_path)
    return remote_path


def copy_remote_to_input(client, src_remote, dst_name):
    """把姐姐电脑上的远程文件复制到 ComfyUI input 目录（ASCII 文件名），
    避免中文路径/完整路径在 LoadImage 加载时出问题。"""
    sftp = client.open_sftp()
    dst = INPUT_DIR + "\\" + dst_name
    with sftp.open(src_remote, "rb") as fr, sftp.open(dst, "wb") as fw:
        while True:
            chunk = fr.read(65536)
            if not chunk:
                break
            fw.write(chunk)
    sftp.close()
    print("[pre] 游戏角色底图已复制到 input:", dst_name)
    return dst_name


def main():
    ap = argparse.ArgumentParser(description="梦璃生图一键流水线")
    ap.add_argument("--scene", default="", help="追加到人设后的场景/动作描述（英文）")
    ap.add_argument("--pos", default=None, help="完整自定义正面提示词（覆盖默认人设）")
    ap.add_argument("--neg", default=None, help="完整自定义负面提示词（默认用标准负面）")
    ap.add_argument("--seed", type=int, default=0, help="随机种子（0=随机）")
    ap.add_argument("--width", type=int, default=DEFAULT_W)
    ap.add_argument("--height", type=int, default=DEFAULT_H)
    ap.add_argument("--prefix", default="mengli", help="输出文件名前缀")
    ap.add_argument("--left-cam", action="store_true", help="左侧机位（趴床/躺姿专用，发卡稳定）")
    ap.add_argument("--right-side", action="store_true", help="右侧机位（去掉发卡描述）")
    ap.add_argument("--img2img", default=None, help="图生图：远程底图路径")
    ap.add_argument("--denoise", type=float, default=None, help="图生图降噪强度（默认0.55，--game-char时0.5）")
    ap.add_argument("--game-char", action="store_true", help="以游戏角色立绘（星月女仆）为底图做图生图，服装统一")
    ap.add_argument("--cutout", action="store_true", help="抠图生成透明PNG")
    ap.add_argument("--save", default=None, help="保存到立绘文件夹的文件名（不带扩展名，如 AI-新图）")
    ap.add_argument("--check-only", action="store_true", help="只检查ComfyUI是否运行")
    ap.add_argument("--local-dir", default="/tmp/mengli_out", help="本地下载目录")
    ap.add_argument("--debug", action="store_true", help="调试模式：生图→识图→判定，不合格自动换seed重画")
    ap.add_argument("--max-attempts", type=int, default=4, help="调试模式最大尝试次数")
    ap.add_argument("--criteria", default=None, help="自定义质检检查点，用 | 分隔（覆盖默认检查点）")
    args = ap.parse_args()

    # 线路互斥：游戏角色生图独占，忽略审核线路
    if args.game_char and args.debug:
        print("[info] --game-char 独占线路，忽略 --debug 审核")
        args.debug = False

    # --game-char：denoise 默认 0.45；底图锚定在 ssh 连接后执行
    if args.denoise is None:
        args.denoise = 0.45 if args.game_char else 0.55
    if args.game_char and not args.scene:
        args.scene = ("the game character design, standing upright, calm cool cute expression, "
                     "full body, wearing the same black and white maid dress")

    os.makedirs(args.local_dir, exist_ok=True)

    # 只检查模式
    if args.check_only:
        print("ComfyUI running:", comfy_running())
        return 0

    # 组装提示词
    if args.pos:
        pos = args.pos + (", " + args.scene if args.scene else "")
    elif args.left_cam:
        pos = POS_LEFT_CAM + (", " + args.scene if args.scene else "")
    elif args.right_side:
        pos = POS_RIGHT + (", " + args.scene if args.scene else "")
    else:
        pos = POS_BASE + (", " + args.scene if args.scene else "")
    # 衣服可变性：场景要求换衣服时，去掉默认人设里的固定衣物，让新衣服生效
    if args.game_char or wants_clothing_change(args.scene):
        pos = strip_default_clothes(pos)
    neg = args.neg or (NEG_RIGHT if args.right_side else NEG_BASE)
    if args.game_char and not args.neg:
        neg = neg + GAME_NEG_EXTRA
    seed = args.seed or random.randint(1, 999999)

    # 连接 + 先确保本地隧道可用（不通直接退出，不误发启动命令）
    client = ssh_connect()
    if not ensure_tunnel():
        client.close()
        print("RESULT_JSON=" + json.dumps({"ok": False, "error": "本地隧道不通，请姐姐手动确认/重建隧道"}, ensure_ascii=False))
        return 1
    # 再检查 ComfyUI 是否真正响应（本地隧道端口通但服务可能没起）
    if not comfy_running():
        print("[pre] ComfyUI 未运行，尝试启动...")
        start_comfy(client)
    else:
        print("[pre] ComfyUI 运行中")

    # --game-char：把定稿立绘复制到 input 目录（ASCII 文件名）作为底图
    if args.game_char and not args.img2img:
        args.img2img = copy_remote_to_input(client, GAME_CHAR_REMOTE, "game_char_base.png")
        print("[pre] 使用游戏角色立绘底图:", GAME_CHAR_REMOTE)

    # 图生图底图：若传的是本地路径先上传到 input 目录
    img2img_remote = None
    if args.img2img:
        if os.path.exists(args.img2img) and not args.img2img.startswith("D:"):
            img2img_remote = upload_file(client, args.img2img, INPUT_DIR + "\\" + os.path.basename(args.img2img))
        else:
            img2img_remote = args.img2img
        print("[pre] 图生图底图:", img2img_remote)

    # ---- 生成+质检循环（debug模式）或单次生成 ----
    cam = default_cam_mode(args)
    if args.criteria:
        checks = [c.strip() for c in args.criteria.split("|") if c.strip()]
    else:
        checks = build_visual_checks(cam, args.scene)

    attempts = args.max_attempts if args.debug else 1
    local = None
    final_seed = None
    all_issues = []
    ok = False
    used = 0
    candidates = []  # (score, local_path, seed, issues)

    for i in range(1, attempts + 1):
        used = i
        cur_seed = seed if (i == 1 and args.seed) else random.randint(1, 999999)
        prefix_i = "%s_a%d" % (args.prefix, i)
        submit_workflow(pos, neg, prefix_i, cur_seed, args.width, args.height,
                        img2img_path=img2img_remote, denoise=args.denoise)
        print("[attempt %d/%d] seed=%d" % (i, attempts, cur_seed))
        cur_local = wait_and_download(client, prefix_i, args.local_dir)
        final_seed = cur_seed
        local = cur_local

        if not args.debug:
            ok = True
            break

        # 调试模式：质检（发卡位置用像素客观检测 + 其余主观项交视觉模型）
        issues = []
        score = 0
        if not args.criteria:
            side = detect_hairpin_side(cur_local)
            if cam in ("front", "left"):
                if side != "right":
                    issues.append("发卡位置客观检测：在画面%s半（应右半）" % side)
                    score -= 30
                else:
                    score += 15
            elif cam == "right":
                if side != "none":
                    issues.append("右侧机位不应出现发卡（检测到%s半）" % side)
                    score -= 25
                else:
                    score += 10
        good, vis_issues, vis_score, raw = check_image(cur_local, checks)
        score += vis_score
        issues += vis_issues
        candidates.append((score, cur_local, cur_seed, list(issues)))
        if good and not issues:
            print("[qa] 第 %d 张合格 (score=%d)" % (i, score))
            ok = True
            break
        all_issues.append("seed %d: %s" % (cur_seed, "; ".join(issues[:4])))
        print("[qa] 第 %d 张不合格 (score=%d): %s" % (i, score, "; ".join(issues[:4])))

    if not ok:
        # 4次均不合格：挑最贴近要求的一版（score最高），不拦截，供姐姐查看
        if candidates:
            best = max(candidates, key=lambda x: x[0])
            local, final_seed = best[1], best[2]
            print("[warn] 4次均不合格，选最贴近要求的一版 (score=%d, seed=%d)" % (best[0], best[2]))
        else:
            print("[warn] 4次均不合格，且无候选图")
        print("[warn] 问题记录：")
        for it in all_issues:
            print("  -", it)

    results = {"ok": ok, "local": local, "seed": final_seed, "remote_out": OUT_DIR}
    if args.debug:
        results["attempts_used"] = used
        if not ok:
            results["issues"] = all_issues

    # 抠图
    if args.cutout and local:
        cut_local = os.path.splitext(local)[0] + "_transparent.png"
        cutout(local, cut_local)
        results["local_cutout"] = cut_local

    # 存立绘
    if args.save and local:
        save_remote = upload_file(client, local, SAVE_LIB + "\\" + args.save + ".png")
        results["saved_remote"] = save_remote

    client.close()
    print("[done] RESULT_JSON=" + json.dumps(results, ensure_ascii=False))
    return 0


def _safe_main():
    try:
        return main()
    except Exception as e:
        print("RESULT_JSON=" + json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    sys.exit(_safe_main())
