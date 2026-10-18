"""FamilyTube - a very simple YouTube video / audio downloader for the family.

Paste a link, pick Video or Audio, press Download. Files go to
Downloads\\FamilyTube. On first run the helper tools (yt-dlp, ffmpeg, deno)
are fetched from their official GitHub releases into
%LOCALAPPDATA%\\FamilyTube\\tools, and yt-dlp is kept up to date once a day.
"""

import os
import re
import shutil
import subprocess
import sys
import threading
import time
import tkinter as tk
import urllib.request
import zipfile
from pathlib import Path
from tkinter import messagebox, ttk

APP_NAME = "FamilyTube"
TOOLS_DIR = Path(os.environ.get("LOCALAPPDATA", Path.home())) / APP_NAME / "tools"
SAVE_DIR = Path.home() / "Downloads" / APP_NAME
UPDATE_STAMP = TOOLS_DIR / "last_update.txt"

YTDLP = TOOLS_DIR / "yt-dlp.exe"
FFMPEG = TOOLS_DIR / "ffmpeg.exe"
DENO = TOOLS_DIR / "deno.exe"

YTDLP_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
FFMPEG_URL = ("https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest/"
              "ffmpeg-master-latest-win64-gpl.zip")
DENO_URL = ("https://github.com/denoland/deno/releases/latest/download/"
            "deno-x86_64-pc-windows-msvc.zip")

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
YOUTUBE_LINK = re.compile(r"^https?://([\w-]+\.)?(youtube\.com|youtu\.be)/", re.I)


# ---------------------------------------------------------------- tools setup

def _fetch(url, dest, report):
    """Download url to dest, calling report(percent) as it goes."""
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": APP_NAME})
    with urllib.request.urlopen(req, timeout=60) as resp, open(tmp, "wb") as out:
        total = int(resp.headers.get("Content-Length") or 0)
        done = 0
        while chunk := resp.read(256 * 1024):
            out.write(chunk)
            done += len(chunk)
            if total:
                report(done * 100 / total)
    tmp.replace(dest)


def _extract(zip_path, names):
    """Pull the named .exe files out of a zip (from any sub-folder)."""
    with zipfile.ZipFile(zip_path) as zf:
        for member in zf.namelist():
            base = member.rsplit("/", 1)[-1]
            if base in names:
                with zf.open(member) as src, open(TOOLS_DIR / base, "wb") as dst:
                    shutil.copyfileobj(src, dst)
    zip_path.unlink(missing_ok=True)


def ensure_tools(status):
    """Make sure yt-dlp, ffmpeg and deno are present and yt-dlp is fresh."""
    TOOLS_DIR.mkdir(parents=True, exist_ok=True)

    if not YTDLP.exists():
        _fetch(YTDLP_URL, YTDLP,
               lambda p: status(f"First-time setup: getting downloader... {p:.0f}%", p))
        UPDATE_STAMP.write_text(str(time.time()))
    if not FFMPEG.exists():
        z = TOOLS_DIR / "ffmpeg.zip"
        _fetch(FFMPEG_URL, z,
               lambda p: status(f"First-time setup: getting converter... {p:.0f}%", p))
        status("First-time setup: unpacking converter...", 100)
        _extract(z, {"ffmpeg.exe", "ffprobe.exe"})
    if not DENO.exists():
        z = TOOLS_DIR / "deno.zip"
        _fetch(DENO_URL, z,
               lambda p: status(f"First-time setup: getting helper... {p:.0f}%", p))
        _extract(z, {"deno.exe"})

    # YouTube changes often; keep yt-dlp current (at most once a day).
    try:
        last = float(UPDATE_STAMP.read_text())
    except (OSError, ValueError):
        last = 0
    if time.time() - last > 24 * 3600:
        status("Checking for updates...", 0)
        subprocess.run([str(YTDLP), "-U"], capture_output=True,
                       creationflags=NO_WINDOW, timeout=120)
        UPDATE_STAMP.write_text(str(time.time()))


# ---------------------------------------------------------------- downloading

def build_command(url, audio_only):
    cmd = [
        str(YTDLP), url,
        "--ignore-config", "--no-playlist", "--encoding", "utf-8",
        "--ffmpeg-location", str(TOOLS_DIR),
        "--js-runtimes", f"deno:{DENO}",
        "-P", str(SAVE_DIR), "-o", "%(title).150B.%(ext)s",
        "--newline", "--progress",
        "--progress-template", "download:PCT %(progress._percent_str)s",
        "--print", "after_move:FILE %(filepath)s", "--no-simulate",
        "--embed-metadata",
    ]
    if audio_only:
        cmd += ["-f", "bestaudio/best", "-x",
                "--audio-format", "mp3", "--audio-quality", "0",
                "--embed-thumbnail"]
    else:
        # Up to 1080p, prefer H.264 + AAC so it plays on phones, TVs and old PCs.
        cmd += ["-f", "bv*+ba/b", "-S", "res:1080,vcodec:h264,acodec:m4a",
                "--merge-output-format", "mp4"]
    return cmd


def friendly_error(lines):
    text = "\n".join(lines).lower()
    if "private video" in text or "sign in" in text:
        return "This video is private or needs a sign-in, so it can't be downloaded."
    if "unavailable" in text:
        return "This video is unavailable (removed, blocked, or a wrong link)."
    if "unable to download" in text or "timed out" in text or "getaddrinfo" in text:
        return "Couldn't reach YouTube. Check the internet connection and try again."
    errors = [l for l in lines if "ERROR" in l]
    return errors[-1] if errors else "Something went wrong. Please try again."


# ---------------------------------------------------------------- window

class App:
    def __init__(self, root):
        self.root = root
        self.busy = True
        root.title(APP_NAME)
        root.geometry("560x380")
        root.minsize(480, 360)

        big = ("Segoe UI", 14)
        style = ttk.Style()
        style.configure("Big.TButton", font=("Segoe UI", 16, "bold"), padding=10)
        style.configure("TRadiobutton", font=big)

        frame = ttk.Frame(root, padding=20)
        frame.pack(fill="both", expand=True)

        ttk.Label(frame, text="1. Paste the YouTube link here:", font=big).pack(anchor="w")
        row = ttk.Frame(frame)
        row.pack(fill="x", pady=(4, 14))
        self.url = tk.StringVar()
        entry = ttk.Entry(row, textvariable=self.url, font=big)
        entry.pack(side="left", fill="x", expand=True)
        ttk.Button(row, text="Paste", command=self.paste).pack(side="left", padx=(8, 0))

        ttk.Label(frame, text="2. What do you want?", font=big).pack(anchor="w")
        self.audio = tk.BooleanVar(value=False)
        ttk.Radiobutton(frame, text="Video (MP4)", variable=self.audio,
                        value=False).pack(anchor="w", padx=10)
        ttk.Radiobutton(frame, text="Music / audio only (MP3)", variable=self.audio,
                        value=True).pack(anchor="w", padx=10, pady=(0, 14))

        self.go = ttk.Button(frame, text="Download", style="Big.TButton",
                             command=self.start_download, state="disabled")
        self.go.pack(fill="x")

        self.bar = ttk.Progressbar(frame, maximum=100)
        self.bar.pack(fill="x", pady=(14, 4))
        self.status = ttk.Label(frame, text="Starting...", font=("Segoe UI", 11),
                                wraplength=500)
        self.status.pack(anchor="w")

        ttk.Button(frame, text="Open my downloads folder",
                   command=self.open_folder).pack(anchor="e", side="bottom")

        entry.focus_set()
        threading.Thread(target=self.setup, daemon=True).start()

    # thread-safe UI updates
    def set_status(self, text, pct=None):
        def apply():
            self.status.config(text=text)
            if pct is not None:
                self.bar["value"] = pct
        self.root.after(0, apply)

    def paste(self):
        try:
            self.url.set(self.root.clipboard_get().strip())
        except tk.TclError:
            pass

    def open_folder(self):
        SAVE_DIR.mkdir(parents=True, exist_ok=True)
        os.startfile(SAVE_DIR)

    def setup(self):
        try:
            ensure_tools(self.set_status)
            self.set_status("Ready. Paste a link and press Download.", 0)
            self.root.after(0, lambda: self.go.config(state="normal"))
        except Exception as exc:  # noqa: BLE001 - show anything to the user
            self.set_status("Setup failed. Check the internet and reopen the app.", 0)
            self.root.after(0, lambda: messagebox.showerror(
                APP_NAME, f"Couldn't finish first-time setup:\n\n{exc}"))
        finally:
            self.busy = False

    def start_download(self):
        url = self.url.get().strip()
        if not YOUTUBE_LINK.match(url):
            messagebox.showwarning(APP_NAME, "That doesn't look like a YouTube link.\n\n"
                                   "In YouTube, tap Share > Copy link, then press Paste here.")
            return
        if self.busy:
            return
        self.busy = True
        self.go.config(state="disabled")
        threading.Thread(target=self.download, args=(url, self.audio.get()),
                         daemon=True).start()

    def download(self, url, audio_only):
        SAVE_DIR.mkdir(parents=True, exist_ok=True)
        self.set_status("Starting download...", 0)
        lines, saved = [], None
        try:
            proc = subprocess.Popen(build_command(url, audio_only),
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    creationflags=NO_WINDOW)
            for raw in proc.stdout:
                line = raw.decode("utf-8", "replace").rstrip()
                lines.append(line)
                if line.startswith("PCT "):
                    try:
                        pct = float(line[4:].strip().rstrip("%"))
                        self.set_status(f"Downloading... {pct:.0f}%", pct)
                    except ValueError:
                        pass
                elif line.startswith("FILE "):
                    saved = Path(line[5:])
                elif line.startswith(("[Merger]", "[ExtractAudio]", "[EmbedThumbnail]")):
                    self.set_status("Almost done, finishing the file...", 100)
            proc.wait()
            if proc.returncode == 0:
                name = saved.name if saved else "your file"
                self.set_status(f"Done! Saved: {name}", 100)
                self.root.after(0, lambda: self.url.set(""))
            else:
                msg = friendly_error(lines)
                self.set_status("Download failed.", 0)
                self.root.after(0, lambda: messagebox.showerror(APP_NAME, msg))
        except Exception as exc:  # noqa: BLE001
            self.set_status("Download failed.", 0)
            self.root.after(0, lambda: messagebox.showerror(APP_NAME, str(exc)))
        finally:
            self.busy = False
            self.root.after(0, lambda: self.go.config(state="normal"))


def main():
    if sys.platform != "win32":
        print("FamilyTube is made for Windows.")
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
