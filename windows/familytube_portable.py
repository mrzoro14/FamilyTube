"""FamilyTube Portable Windows - a private website that runs on this computer.

Double-click FamilyTube-Portable-Windows.exe: it starts a small server that only this
computer can reach, and opens the site in the browser. Files go to
Downloads\\FamilyTube. The helper tools (yt-dlp, ffmpeg, deno) are fetched from their
official GitHub releases on first run, and yt-dlp is kept up to date once a day.

The app updates itself from portable.json and the exe attached to the latest GitHub
release, so laptop-only updates don't need a new tag (a new tag prompts phones to update).
"""

import hashlib
import json
import os
import queue
import re
import secrets
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import uuid
import webbrowser
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

APP_NAME = "FamilyTube"
VERSION = "1.0"
RELEASES_PAGE = "https://github.com/mrzoro14/FamilyTube/releases/latest"
UPDATE_INFO_URL = RELEASES_PAGE + "/download/portable.json"
UPDATE_EXE_URL = RELEASES_PAGE + "/download/FamilyTube-Portable-Windows.exe"
DATA_DIR = Path(os.environ.get("LOCALAPPDATA", Path.home())) / APP_NAME
TOOLS_DIR = DATA_DIR / "tools"
WORK_DIR = DATA_DIR / "work"
HISTORY = DATA_DIR / "downloads.json"
UPDATE_STAMP = TOOLS_DIR / "last_update.txt"
SAVE_DIR = Path.home() / "Downloads" / APP_NAME

YTDLP = TOOLS_DIR / "yt-dlp.exe"
FFMPEG = TOOLS_DIR / "ffmpeg.exe"
DENO = TOOLS_DIR / "deno.exe"

YTDLP_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
FFMPEG_URL = ("https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest/"
              "ffmpeg-master-latest-win64-gpl.zip")
DENO_URL = ("https://github.com/denoland/deno/releases/latest/download/"
            "deno-x86_64-pc-windows-msvc.zip")

HOST = "127.0.0.1"  # never the network: only this computer can open the site
FIRST_PORT = 8765
LAST_PORT = 8775
MAX_BODY = 64 * 1024
MAX_FINISHED = 200
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

VIDEO_HEIGHT = [360, 720, 1080, 0]  # 0 = no limit
AUDIO_QUALITY = ["128K", "0"]
SUPPORTED_LINK = re.compile(
    r"https?://(?:[\w-]+\.)?(?:youtube\.com|youtu\.be|instagram\.com|instagr\.am)/\S+", re.I)
INSTAGRAM_LINK = re.compile(r"https?://(?:[\w-]+\.)?(?:instagram\.com|instagr\.am)/", re.I)


class Cancelled(Exception):
    pass


class UserError(Exception):
    """A problem to show as-is."""


def find_link(text):
    match = SUPPORTED_LINK.search(text or "")
    return match.group() if match else None


def is_instagram(link):
    return bool(INSTAGRAM_LINK.match(link))


def is_story(link):
    return is_instagram(link) and "/stories/" in link.lower()


def format_time(seconds):
    h, m, s = seconds // 3600, seconds % 3600 // 60, seconds % 60
    return f"{h}:{m:02}:{s:02}" if h else f"{m}:{s:02}"


# ---------------------------------------------------------------- tools setup

class Setup:
    ready = threading.Event()
    message = "Starting..."
    pct = -1
    failed = False


def set_setup(message, pct=-1):
    Setup.message, Setup.pct = message, pct


def fetch_file(url, dest, label):
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": APP_NAME})
    with urllib.request.urlopen(req, timeout=60) as resp, open(tmp, "wb") as out:
        total = int(resp.headers.get("Content-Length") or 0)
        done = 0
        while chunk := resp.read(256 * 1024):
            out.write(chunk)
            done += len(chunk)
            if total:
                pct = done * 100 // total
                set_setup(f"First-time setup: {label} {pct}%", pct)
    tmp.replace(dest)


def extract(zip_path, names):
    with zipfile.ZipFile(zip_path) as zf:
        for member in zf.namelist():
            base = member.rsplit("/", 1)[-1]
            if base in names:
                with zf.open(member) as src, open(TOOLS_DIR / base, "wb") as dst:
                    shutil.copyfileobj(src, dst)
    zip_path.unlink(missing_ok=True)


def ensure_tools():
    TOOLS_DIR.mkdir(parents=True, exist_ok=True)
    if not YTDLP.exists():
        fetch_file(YTDLP_URL, YTDLP, "getting the downloader...")
        UPDATE_STAMP.write_text(str(time.time()))
    if not FFMPEG.exists():
        zip_path = TOOLS_DIR / "ffmpeg.zip"
        fetch_file(FFMPEG_URL, zip_path, "getting the converter...")
        set_setup("First-time setup: unpacking the converter...")
        extract(zip_path, {"ffmpeg.exe", "ffprobe.exe"})
    if not DENO.exists():
        zip_path = TOOLS_DIR / "deno.zip"
        fetch_file(DENO_URL, zip_path, "getting the helper...")
        extract(zip_path, {"deno.exe"})

    # YouTube changes often; keep yt-dlp current (at most once a day).
    try:
        last = float(UPDATE_STAMP.read_text())
    except (OSError, ValueError):
        last = 0
    if time.time() - last > 24 * 3600:
        set_setup("Checking for updates...")
        try:
            subprocess.run([str(YTDLP), "-U"], capture_output=True,
                           creationflags=NO_WINDOW, timeout=120)
            UPDATE_STAMP.write_text(str(time.time()))
        except (OSError, subprocess.SubprocessError):
            pass  # No internet right now is fine; we'll try again next time.


def setup_thread():
    try:
        ensure_tools()
        Setup.ready.set()
    except Exception as exc:  # noqa: BLE001 - shown to the user
        Setup.failed = True
        set_setup(f"Setup failed. Check the internet and reopen FamilyTube. ({exc})", 0)
    check_for_update()


# ---------------------------------------------------------------- app updates

class Update:
    available = None  # the newer release's portable.json, once one is found
    state = "idle"    # idle, downloading, restarting or failed
    pct = 0
    error = None


def can_self_update():
    return getattr(sys, "frozen", False)  # Only the packaged exe can replace itself.


def is_newer(candidate, current):
    def parts(version):
        return [int(p) for p in re.findall(r"\d+", str(version))]
    return parts(candidate) > parts(current)


def check_for_update():
    """Look for a newer version. Being offline just means no banner."""
    try:
        req = urllib.request.Request(UPDATE_INFO_URL, headers={"User-Agent": APP_NAME})
        with urllib.request.urlopen(req, timeout=20) as resp:
            info = json.load(resp)
    except (OSError, ValueError):
        return False
    if is_newer(info.get("version"), VERSION) and re.fullmatch(
            r"[0-9a-f]{64}", str(info.get("sha256"))):
        Update.available = info
        return True
    return False


def download_update(dest, info):
    digest = hashlib.sha256()
    req = urllib.request.Request(UPDATE_EXE_URL, headers={"User-Agent": APP_NAME})
    with urllib.request.urlopen(req, timeout=60) as resp, open(dest, "wb") as out:
        total = int(resp.headers.get("Content-Length") or 0)
        done = 0
        while chunk := resp.read(256 * 1024):
            out.write(chunk)
            digest.update(chunk)
            done += len(chunk)
            if total:
                Update.pct = done * 100 // total
    if digest.hexdigest() != info["sha256"]:
        raise ValueError("The downloaded update didn't match its checksum.")


def install_update():
    """Swap in the new exe and restart. A running exe can be renamed, not overwritten."""
    exe = Path(sys.executable)
    new = exe.with_name(exe.name + ".new")
    old = exe.with_name(exe.name + ".old")
    Update.state, Update.pct, Update.error = "downloading", 0, None
    try:
        download_update(new, Update.available)
        old.unlink(missing_ok=True)
        exe.rename(old)
        try:
            new.rename(exe)
        except OSError:
            old.rename(exe)  # Put the working version back.
            raise
    except (OSError, ValueError) as exc:
        new.unlink(missing_ok=True)
        Update.state = "failed"
        Update.error = str(exc)
        return
    Update.state = "restarting"
    time.sleep(1.5)  # Let the page see "restarting" before the server goes away.
    env = dict(os.environ, PYINSTALLER_RESET_ENVIRONMENT="1")
    subprocess.Popen([str(exe), "--updated"], env=env, close_fds=True,
                     creationflags=subprocess.DETACHED_PROCESS)
    Handler.httpd.shutdown()


def remove_update_leftovers():
    if can_self_update():
        exe = Path(sys.executable)
        for name in (exe.name + ".old", exe.name + ".new"):
            for _ in range(10):  # The old version may take a moment to exit.
                try:
                    exe.with_name(name).unlink(missing_ok=True)
                    break
                except OSError:
                    time.sleep(0.5)


# ---------------------------------------------------------------- download list

class Store:
    """Every download, newest first. Saved so history survives closing the app."""

    def __init__(self):
        self.lock = threading.Lock()
        self.items = []
        self.load()

    def load(self):
        try:
            self.items = json.loads(HISTORY.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.items = []
        for item in self.items:
            if item["state"] in ("queued", "running"):
                item["state"] = "failed"
                item["error"] = "Stopped before it finished. Press Retry to try again."

    def save(self):
        finished = 0
        keep = []
        for item in self.items:
            if item["state"] in ("done", "failed"):
                finished += 1
                if finished > MAX_FINISHED:
                    continue
            keep.append(item)
        self.items = keep
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        tmp = HISTORY.with_suffix(".tmp")
        tmp.write_text(json.dumps(keep), encoding="utf-8")
        tmp.replace(HISTORY)

    def add(self, item):
        with self.lock:
            self.items.insert(0, item)
            self.save()

    def find(self, item_id):
        with self.lock:
            return next((i for i in self.items if i["id"] == item_id), None)

    def update(self, item, save=False, **changes):
        with self.lock:
            item.update(changes)
            if save:
                self.save()

    def remove(self, item):
        with self.lock:
            if item in self.items:
                self.items.remove(item)
                self.save()

    def clear_finished(self):
        with self.lock:
            self.items = [i for i in self.items if i["state"] in ("queued", "running")]
            self.save()

    def snapshot(self):
        with self.lock:
            return [{k: v for k, v in i.items() if k != "path"} | {"canOpen": bool(i.get("path"))}
                    for i in self.items]


store = Store()
jobs = queue.Queue()


class Current:
    """The download now running, so Cancel can reach its process."""
    item = None
    proc = None
    cancelled_ids = set()


def queue_download(item):
    jobs.put(item["id"])


def cancel_download(item):
    if item["state"] == "queued":
        Current.cancelled_ids.add(item["id"])
        store.remove(item)
    elif item["state"] == "running":
        Current.cancelled_ids.add(item["id"])
        stop_process()


def stop_process():
    proc = Current.proc
    if proc and proc.poll() is None:
        # yt-dlp starts ffmpeg as a child, so end the whole process tree.
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                       capture_output=True, creationflags=NO_WINDOW)


# ---------------------------------------------------------------- downloading

def run_tool(args):
    """Run yt-dlp to completion and return its output as text."""
    done = subprocess.run([str(YTDLP), *args], capture_output=True, creationflags=NO_WINDOW,
                          timeout=120)
    out = done.stdout.decode("utf-8", "replace")
    if done.returncode != 0:
        raise RuntimeError(done.stderr.decode("utf-8", "replace"))
    return out


def base_options():
    return ["--ignore-config", "--encoding", "utf-8", "--no-warnings",
            "--ffmpeg-location", str(TOOLS_DIR), "--js-runtimes", f"deno:{DENO}"]


def fetch_info(link):
    """Title, length and picture for the page, or None if they can't be fetched."""
    if not Setup.ready.is_set():
        return None
    try:
        raw = run_tool([*base_options(), "-J", "--ignore-no-formats-error", "--no-playlist",
                        "--flat-playlist", "--", link])
        info = json.loads(raw[raw.index("{"):])
    except (RuntimeError, ValueError, OSError, subprocess.SubprocessError):
        return None
    thumbs = info.get("thumbnails") or []
    picture = info.get("thumbnail") or (thumbs[-1].get("url") if thumbs else None)
    return {"title": info.get("title"), "duration": int(info.get("duration") or 0),
            "thumbnail": picture}


def build_command(link, audio, level, work, clip=None, entry=0):
    cmd = [str(YTDLP), *base_options(), "--newline", "--progress",
           "--progress-template", "download:PCT %(progress._percent_str)s",
           "--print", "after_move:FILE %(filepath)s", "--no-simulate",
           "--embed-metadata", "-P", str(work)]
    cmd += ["-I", str(entry)] if entry else ["--no-playlist"]
    suffix = ""
    if clip:
        # Copies the part without re-encoding, which is fast. See the Android app.
        cmd += ["--download-sections", f"*{clip[0]}-{clip[1]}"]
        suffix = f" (clip {format_time(clip[0])} to {format_time(clip[1])})".replace(":", ".")
    cmd += ["-o", f"%(title).100B{suffix}.%(ext)s"]
    if audio:
        cmd += ["-f", "bestaudio/best", "-x", "--audio-format", "mp3",
                "--audio-quality", AUDIO_QUALITY[level], "--embed-thumbnail"]
    else:
        # Prefer H.264 + AAC so it plays everywhere. "Best" allows VP9/AV1 above 1080p.
        height = VIDEO_HEIGHT[level]
        cmd += ["-f", "bv*+ba/b", "-S",
                f"res:{height},vcodec:h264,acodec:m4a" if height else "res,acodec:m4a",
                "--merge-output-format", "mp4"]
    return [*cmd, "--", link]


def run_download(item, cmd, prefix=""):
    """Run one yt-dlp download, reporting progress. Returns the finished file."""
    lines, saved = [], None
    Current.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    creationflags=NO_WINDOW)
    for raw in Current.proc.stdout:
        line = raw.decode("utf-8", "replace").rstrip()
        lines.append(line)
        if line.startswith("PCT "):
            try:
                pct = float(line[4:].strip().rstrip("%"))
            except ValueError:
                continue
            store.update(item, pct=round(pct), status=f"{prefix}Downloading... {pct:.0f}%")
        elif line.startswith("FILE "):
            saved = Path(line[5:])
        elif line.startswith(("[Merger]", "[ExtractAudio]", "[EmbedThumbnail]")):
            store.update(item, pct=100, status=f"{prefix}Almost done, finishing the file...")
    Current.proc.wait()
    code, Current.proc = Current.proc.returncode, None
    if item["id"] in Current.cancelled_ids:
        raise Cancelled()
    if code != 0 or not saved or not saved.exists():
        raise RuntimeError("\n".join(lines[-15:]))
    return saved


def unique_path(folder, name):
    path = folder / name
    stem, ext = path.stem, path.suffix
    n = 1
    while path.exists():
        n += 1
        path = folder / f"{stem} ({n}){ext}"
    return path


def keep_file(source):
    SAVE_DIR.mkdir(parents=True, exist_ok=True)
    dest = unique_path(SAVE_DIR, source.name)
    shutil.move(str(source), dest)
    return dest


def best_image(entry):
    """yt-dlp lists a photo's sizes as thumbnails, small to large."""
    best, best_area = None, -1
    for thumb in entry.get("thumbnails") or []:
        area = (thumb.get("width") or 0) * (thumb.get("height") or 0)
        if thumb.get("url") and area >= best_area:
            best, best_area = thumb["url"], area
    return best


def save_image(url, name):
    req = urllib.request.Request(url, headers={"Referer": "https://www.instagram.com/",
                                               "User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        kind = resp.headers.get("Content-Type", "")
        ext = "webp" if "webp" in kind else "heic" if "heic" in kind else "jpg"
        SAVE_DIR.mkdir(parents=True, exist_ok=True)
        dest = unique_path(SAVE_DIR, re.sub(r'[\\/:*?"<>|]', "_", name) + "." + ext)
        with open(dest, "wb") as out:
            shutil.copyfileobj(resp, out)
    return dest


def download_instagram_post(item, work):
    """Save everything in a post: photos directly, videos through yt-dlp."""
    link = item["link"]
    store.update(item, status="Looking at the post...", pct=-1)
    raw = run_tool([*base_options(), "-J", "--ignore-no-formats-error", "--", link])
    info = json.loads(raw[raw.index("{"):])
    is_post = info.get("_type") == "playlist"
    entries = [e for e in info.get("entries") or [] if e] if is_post else [info]
    owner = info.get("channel") or "instagram"
    post = info.get("id") or "post"
    saved = []
    for n, entry in enumerate(entries, 1):
        step = f"({n} of {len(entries)}) " if len(entries) > 1 else ""
        if item["id"] in Current.cancelled_ids:
            raise Cancelled()
        if entry.get("formats"):
            cmd = build_command(link, False, item["level"], work, entry=n if is_post else 0)
            saved.append(keep_file(run_download(item, cmd, step)))
        elif url := best_image(entry):
            store.update(item, status=f"{step}Saving photo...", pct=(n - 1) * 100 // len(entries))
            saved.append(save_image(url, f"{owner}_{post}" + (f"_{n}" if len(entries) > 1 else "")))
    if not saved:
        raise UserError("Couldn't find any photo or video in this Instagram post.")
    name = saved[0].name if len(saved) == 1 else f"{len(saved)} photos/videos from @{owner}"
    return saved[0], name


def do_download(item):
    link = item["link"]
    if is_story(link):
        raise UserError("Instagram Stories can't be saved. Instagram only shows them to people "
                        "who are logged in, and FamilyTube doesn't log in, to keep your account "
                        "safe. Posts, photos and Reels work.")
    work = WORK_DIR / item["id"]
    work.mkdir(parents=True, exist_ok=True)
    try:
        if is_instagram(link) and not item["audio"]:
            return download_instagram_post(item, work)
        clip = item.get("clip")
        status = "Getting your clip..." if clip else "Starting download..."
        store.update(item, status=status, pct=-1 if clip else 0)
        cmd = build_command(link, item["audio"], item["level"], work, clip)
        path = keep_file(run_download(item, cmd))
        return path, path.name
    finally:
        shutil.rmtree(work, ignore_errors=True)


def friendly_error(message):
    m = (message or "").lower()
    if "[instagram]" in m and "no video" in m:
        return "This Instagram post is a photo, so it can't be saved as music. Choose Save post."
    if "[instagram]" in m and any(w in m for w in ("login", "rate-limit", "rate limit",
                                                   "empty media", "cookies", "not available")):
        return ("Instagram didn't allow this one. Private accounts and some posts need a login, "
                "which FamilyTube doesn't use. If it's public, wait a few minutes and try again.")
    if "private video" in m or "sign in" in m:
        return "This video is private or needs a sign-in, so it can't be downloaded."
    if "unavailable" in m:
        return "This video is unavailable (removed, blocked, or a wrong link)."
    if any(w in m for w in ("unable to download", "timed out", "getaddrinfo", "urlopen error")):
        return "Couldn't reach the website. Check the internet and try again."
    errors = [line for line in (message or "").splitlines() if "ERROR" in line]
    detail = errors[-1][:300] if errors else ""
    return f"Something went wrong. Please try again.\n\n{detail}".strip()


def worker_thread():
    while True:
        item_id = jobs.get()
        Setup.ready.wait()
        item = store.find(item_id)
        if item is None or item_id in Current.cancelled_ids:
            Current.cancelled_ids.discard(item_id)
            continue
        store.update(item, state="running", status="Starting...", pct=-1)
        Current.item = item
        try:
            path, name = do_download(item)
            store.update(item, save=True, state="done", pct=100, name=name, path=str(path),
                         status="Done", time=time.time())
        except Cancelled:
            store.remove(item)
        except UserError as exc:
            store.update(item, save=True, state="failed", error=str(exc), time=time.time())
        except Exception as exc:  # noqa: BLE001 - shown to the user
            store.update(item, save=True, state="failed", time=time.time(),
                         error=friendly_error(str(exc)))
        Current.item = None
        Current.cancelled_ids.discard(item_id)


def open_item(item, folder):
    path = Path(item.get("path") or "")
    if folder or not path.exists() or SAVE_DIR not in path.parents:
        SAVE_DIR.mkdir(parents=True, exist_ok=True)
        if path.exists() and SAVE_DIR in path.parents:
            subprocess.Popen(["explorer", "/select,", str(path)])
        else:
            os.startfile(SAVE_DIR)
    else:
        os.startfile(path)


# ---------------------------------------------------------------- website

def resource(name):
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return (base / "web" / name).read_text(encoding="utf-8")


class Handler(BaseHTTPRequestHandler):
    server_version = "FamilyTube"
    sys_version = ""
    token = secrets.token_urlsafe(32)
    port = FIRST_PORT
    page = None
    httpd = None

    def log_message(self, *args):
        pass  # No console in the packaged app.

    def send_body(self, code, body, kind="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy",
                         "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; "
                         "img-src https:; connect-src 'self'; base-uri 'none'; form-action 'none'")
        self.end_headers()
        self.wfile.write(body)

    def reply(self, data, code=200):
        self.send_body(code, json.dumps(data).encode("utf-8"))

    def trusted_host(self):
        # Stops other websites reaching this server through a look-alike address.
        return self.headers.get("Host") in (f"127.0.0.1:{self.port}", f"localhost:{self.port}")

    def trusted(self):
        return self.trusted_host() and secrets.compare_digest(
            self.headers.get("X-FamilyTube-Token", ""), self.token)

    def do_GET(self):
        if not self.trusted_host():
            self.send_error(403)
        elif self.path == "/":
            html = Handler.page.replace("__TOKEN__", self.token)
            self.send_body(200, html.encode("utf-8"), "text/html; charset=utf-8")
        elif self.path == "/api/ping":
            self.reply({"app": APP_NAME})
        elif self.path == "/api/state":
            if not self.trusted():
                self.send_error(403)
                return
            self.reply({"ready": Setup.ready.is_set(), "failed": Setup.failed,
                        "message": Setup.message, "pct": Setup.pct,
                        "downloads": store.snapshot(), "folder": str(SAVE_DIR),
                        "version": VERSION, "releasesPage": RELEASES_PAGE,
                        "update": {"version": (Update.available or {}).get("version"),
                                   "notes": (Update.available or {}).get("notes"),
                                   "state": Update.state, "pct": Update.pct,
                                   "error": Update.error}})
        else:
            self.send_error(404)

    def do_POST(self):
        if not self.trusted() or self.headers.get("Content-Type") != "application/json":
            self.send_error(403)
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size > MAX_BODY:
                raise ValueError
            body = json.loads(self.rfile.read(size) or b"{}")
            if not isinstance(body, dict):
                raise ValueError
        except ValueError:
            self.send_error(400)
            return
        action = {"/api/info": self.api_info, "/api/download": self.api_download,
                  "/api/cancel": self.api_item, "/api/retry": self.api_item,
                  "/api/remove": self.api_item, "/api/open": self.api_item,
                  "/api/folder": self.api_folder, "/api/clear": self.api_clear,
                  "/api/update/check": self.api_update_check,
                  "/api/update/install": self.api_update_install,
                  "/api/quit": self.api_quit}.get(self.path)
        if action:
            action(body)
        else:
            self.send_error(404)

    def api_info(self, body):
        link = find_link(str(body.get("url", "")))
        if not link:
            self.reply({"error": "That doesn't look like a YouTube or Instagram link."}, 400)
            return
        self.reply({"link": link, "instagram": is_instagram(link), "story": is_story(link),
                    "info": fetch_info(link) if not is_story(link) else None})

    def api_download(self, body):
        link = find_link(str(body.get("url", "")))
        if not link:
            self.reply({"error": "That doesn't look like a YouTube or Instagram link."}, 400)
            return
        audio = body.get("audio") is True
        level = body.get("level")
        if not isinstance(level, int) or isinstance(level, bool) \
                or not 0 <= level < len(AUDIO_QUALITY if audio else VIDEO_HEIGHT):
            self.reply({"error": "Please choose a quality."}, 400)
            return
        clip = body.get("clip")
        if clip is not None:
            if (not isinstance(clip, list) or len(clip) != 2
                    or not all(type(n) is int and n >= 0 for n in clip) or clip[1] <= clip[0]):
                self.reply({"error": "The To time must be after the From time."}, 400)
                return
            if is_instagram(link):
                self.reply({"error": "Clips work with YouTube links."}, 400)
                return
        label = str(body.get("label") or "Download")[:300]
        item = {"id": uuid.uuid4().hex, "label": label, "link": link, "audio": audio,
                "level": level, "clip": clip, "state": "queued", "pct": 0, "status": "Waiting...",
                "error": None, "name": None, "path": None, "time": time.time()}
        store.add(item)
        queue_download(item)
        self.reply({"id": item["id"]})

    def api_item(self, body):
        item = store.find(str(body.get("id", "")))
        if item is None:
            self.reply({"error": "That download is no longer in the list."}, 404)
            return
        if self.path == "/api/cancel":
            cancel_download(item)
        elif self.path == "/api/retry" and item["state"] == "failed":
            store.update(item, save=True, state="queued", error=None, pct=0,
                         status="Waiting...", time=time.time())
            queue_download(item)
        elif self.path == "/api/remove" and item["state"] in ("done", "failed"):
            store.remove(item)  # Only the list entry; the saved file stays.
        elif self.path == "/api/open":
            open_item(item, body.get("folder") is True)
        self.reply({"ok": True})

    def api_folder(self, _body):
        SAVE_DIR.mkdir(parents=True, exist_ok=True)
        os.startfile(SAVE_DIR)
        self.reply({"ok": True})

    def api_clear(self, _body):
        store.clear_finished()
        self.reply({"ok": True})

    def api_update_check(self, _body):
        self.reply({"available": check_for_update()})

    def api_update_install(self, _body):
        if not Update.available or not can_self_update():
            self.reply({"error": "There's no update to install."}, 400)
        elif Update.state in ("downloading", "restarting"):
            self.reply({"ok": True})
        elif any(d["state"] in ("queued", "running") for d in store.snapshot()):
            self.reply({"error": "Please wait for your downloads to finish first."}, 409)
        else:
            threading.Thread(target=install_update, daemon=True).start()
            self.reply({"ok": True})

    def api_quit(self, _body):
        self.reply({"ok": True})
        stop_process()
        threading.Thread(target=Handler.httpd.shutdown, daemon=True).start()


class Server(ThreadingHTTPServer):
    # On Windows this option would let a second copy share the port by mistake.
    allow_reuse_address = False
    daemon_threads = True


def already_running(port):
    try:
        url = f"http://{HOST}:{port}/api/ping"
        with urllib.request.urlopen(url, timeout=2) as resp:
            return json.load(resp).get("app") == APP_NAME
    except (OSError, ValueError):
        return False


def start_server():
    for port in range(FIRST_PORT, LAST_PORT + 1):
        if already_running(port):
            webbrowser.open(f"http://{HOST}:{port}/")
            return None
        try:
            server = Server((HOST, port), Handler)
        except OSError:
            continue
        Handler.port = port
        Handler.httpd = server
        return server
    raise OSError("No free port")


def main():
    Handler.page = resource("index.html")
    updated = "--updated" in sys.argv
    if updated:  # Started by the old version: wait for it to give up the port.
        for _ in range(30):
            if not already_running(FIRST_PORT):
                break
            time.sleep(0.5)
    server = start_server()
    if server is None:
        return  # Already open: the browser was pointed at it.
    threading.Thread(target=remove_update_leftovers, daemon=True).start()
    threading.Thread(target=setup_thread, daemon=True).start()
    threading.Thread(target=worker_thread, daemon=True).start()
    # After an update the open page reloads itself, so no new tab is needed.
    if not updated and "--no-browser" not in sys.argv:
        webbrowser.open(f"http://{HOST}:{Handler.port}/")
    server.serve_forever()


if __name__ == "__main__":
    main()
