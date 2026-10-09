import asyncio
import hashlib
import json
import logging
import os
import shutil
import sqlite3
import mimetypes
import re
import time
import uuid
from pathlib import Path
from fastapi import FastAPI, Request, HTTPException, Query, UploadFile, File, Form
from fastapi.responses import HTMLResponse, FileResponse, StreamingResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel
from contextlib import asynccontextmanager

# Directories
COURSES_DIR = os.getenv("COURSES_DIR", "./courses")
DATA_DIR = os.getenv("DATA_DIR", "./data")
DB_PATH = os.path.join(DATA_DIR, "lms.db")

VIDEO_EXTENSIONS = {".mp4", ".mkv", ".webm", ".avi", ".mov"}
COVER_NAMES = {"cover.jpg", "cover.png", "folder.jpg", "poster.jpg"}
STREAM_CHUNK_SIZE = 64 * 1024
MAX_SUBTITLE_UPLOAD_SIZE = 10 * 1024 * 1024
TEXT_SUBTITLE_CODECS = {"ass", "ssa", "subrip", "srt", "webvtt", "mov_text", "text"}
transcode_jobs = {}
transcode_status = {}
logger = logging.getLogger(__name__)

class ProgressUpdate(BaseModel):
    video_id: int
    watched_seconds: float
    duration: float

def natural_sort_key(s):
    """Convert string to a list of strings and integers for natural sorting."""
    return [int(text) if text.isdigit() else text.lower() for text in re.split(r'(\d+)', s)]

# Database Setup
def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def get_video_path(item_id):
    conn = get_db()
    item = conn.execute(
        "SELECT path FROM course_items WHERE id = ? AND item_type = 'video'",
        (item_id,),
    ).fetchone()
    conn.close()

    if not item:
        raise HTTPException(status_code=404)

    file_path = Path(item["path"])
    if not file_path.is_file():
        raise HTTPException(status_code=404)
    return file_path


def get_transcoded_path(file_path):
    stat = file_path.stat()
    cache_key = hashlib.sha256(
        f"{file_path.resolve()}:{stat.st_size}:{stat.st_mtime_ns}".encode()
    ).hexdigest()
    return Path(DATA_DIR) / "transcoded" / f"{cache_key}.mp4", cache_key


async def probe_video(file_path):
    process = await asyncio.create_subprocess_exec(
        "ffprobe",
        "-v", "error",
        "-show_entries",
        "format=duration:stream=index,codec_type,codec_name,pix_fmt:stream_tags=language,title:stream_disposition=default",
        "-of", "json",
        str(file_path),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, stderr = await process.communicate()
    if process.returncode:
        message = stderr.decode(errors="replace").strip()
        raise RuntimeError(message or "FFprobe could not inspect this video")

    metadata = json.loads(stdout)
    streams = metadata.get("streams", [])
    video_stream = next(
        (stream for stream in streams if stream.get("codec_type") == "video"),
        None,
    )
    if video_stream is None:
        raise RuntimeError("The file does not contain a video stream")

    audio_codecs = [
        stream["codec_name"] for stream in streams
        if stream.get("codec_type") == "audio"
    ]
    subtitles = [
        stream for stream in streams if stream.get("codec_type") == "subtitle"
    ]
    duration = float(metadata.get("format", {}).get("duration", 0) or 0)
    return video_stream["codec_name"], video_stream.get("pix_fmt"), audio_codecs, duration, subtitles


async def transcode_video(file_path, output_path, cache_key, video_codec, pixel_format, audio_codecs, duration):
    temporary_path = output_path.with_name(f"{cache_key}.{os.getpid()}.tmp.mp4")
    copy_video = video_codec == "h264" and pixel_format in {"yuv420p", "yuvj420p"}
    command = [
        "ffmpeg",
        "-nostdin",
        "-hide_banner",
        "-loglevel", "error",
        "-y",
        "-i", str(file_path),
        "-map", "0:v:0",
        "-map", "0:a:0?",
        "-sn",
        "-dn",
        "-c:v", "copy" if copy_video else "libx264",
    ]
    if not copy_video:
        command.extend([
            "-preset", "veryfast",
            "-crf", "23",
            "-pix_fmt", "yuv420p",
            "-threads", "2",
        ])

    command.extend([
        "-c:a", "copy" if audio_codecs and audio_codecs[0] == "aac" else "aac",
    ])
    if not audio_codecs or audio_codecs[0] != "aac":
        command.extend(["-ac", "2", "-b:a", "192k"])
    command.extend([
        "-map_metadata", "0",
        "-movflags", "+faststart",
        "-progress", "pipe:1",
        "-f", "mp4",
        str(temporary_path),
    ])

    process = None
    try:
        process = await asyncio.create_subprocess_exec(
            *command,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stderr_task = asyncio.create_task(process.stderr.read())
        async for line in process.stdout:
            key, separator, value = line.decode(errors="replace").strip().partition("=")
            if separator and key == "out_time_us" and duration > 0:
                status = transcode_status[cache_key]
                status["progress"] = min(99, int(int(value) / (duration * 1_000_000) * 100))

        return_code = await process.wait()
        stderr = (await stderr_task).decode(errors="replace").strip()
        if return_code:
            raise RuntimeError(stderr or f"FFmpeg exited with status {return_code}")

        os.replace(temporary_path, output_path)
    except asyncio.CancelledError:
        if process is not None and process.returncode is None:
            try:
                process.kill()
            except ProcessLookupError:
                pass
            await process.wait()
        temporary_path.unlink(missing_ok=True)
        transcode_status[cache_key] = {
            "state": "failed",
            "error": "Transcoding was cancelled",
        }
        raise
    except Exception as error:
        temporary_path.unlink(missing_ok=True)
        transcode_status[cache_key] = {"state": "failed", "error": str(error)}


def subtitle_label(stream, position):
    tags = stream.get("tags", {})
    title = tags.get("title")
    language = tags.get("language", "und")
    if title:
        return title, language
    if language != "und":
        return f"{language.upper()} subtitles", language
    return f"Subtitle {position}", language


async def extract_subtitle(file_path, output_path, stream_index):
    temporary_path = output_path.with_name(
        f"{output_path.stem}.{os.getpid()}.tmp.vtt"
    )
    command = [
        "ffmpeg",
        "-nostdin",
        "-hide_banner",
        "-loglevel", "error",
        "-y",
        "-i", str(file_path),
        "-map", f"0:{stream_index}",
        "-c:s", "webvtt",
        "-f", "webvtt",
        str(temporary_path),
    ]
    process = None
    try:
        process = await asyncio.create_subprocess_exec(
            *command,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        _, stderr = await process.communicate()
        if process.returncode:
            message = stderr.decode(errors="replace").strip()
            raise RuntimeError(message or f"FFmpeg exited with status {process.returncode}")
        os.replace(temporary_path, output_path)
    except asyncio.CancelledError:
        if process is not None and process.returncode is None:
            try:
                process.kill()
            except ProcessLookupError:
                pass
            await process.wait()
        temporary_path.unlink(missing_ok=True)
        raise
    except Exception:
        temporary_path.unlink(missing_ok=True)
        raise


async def prepare_media(file_path, output_path, manifest_path, cache_key,
                        video_codec, pixel_format, audio_codecs, duration,
                        subtitles, transcode_required, item_id):
    try:
        if transcode_required and not output_path.is_file():
            await transcode_video(
                file_path, output_path, cache_key, video_codec, pixel_format,
                audio_codecs, duration,
            )
            if transcode_status.get(cache_key, {}).get("state") == "failed":
                return

        tracks = []
        unavailable = []
        total_subtitles = len(subtitles)
        for position, stream in enumerate(subtitles, start=1):
            label, language = subtitle_label(stream, position)
            codec = stream.get("codec_name", "unknown")
            if codec not in TEXT_SUBTITLE_CODECS:
                unavailable.append({
                    "label": label,
                    "codec": codec,
                    "reason": "Image-based subtitle format; browser text tracks cannot display it.",
                })
                continue

            stream_index = stream["index"]
            subtitle_path = output_path.with_name(
                f"{output_path.stem}.{stream_index}.vtt"
            )
            if not subtitle_path.is_file():
                transcode_status[cache_key].update({
                    "stage": "subtitles",
                    "subtitle_progress": (
                        int((position - 1) / total_subtitles * 100)
                        if total_subtitles else 100
                    ),
                })
                try:
                    await extract_subtitle(file_path, subtitle_path, stream_index)
                except (OSError, RuntimeError) as error:
                    logger.warning(
                        "Could not convert subtitle stream %s from %s: %s",
                        stream_index,
                        file_path,
                        error,
                    )
                    unavailable.append({
                        "label": label,
                        "codec": codec,
                        "reason": "FFmpeg could not convert this subtitle track.",
                    })
                    continue

            tracks.append({
                "index": stream_index,
                "label": label,
                "language": language,
                "default": bool(stream.get("disposition", {}).get("default")),
            })
            transcode_status[cache_key].update({
                "stage": "subtitles",
                "subtitle_progress": (
                    int(position / total_subtitles * 100)
                    if total_subtitles else 100
                ),
            })

        manifest = {
            "transcode_required": transcode_required,
            "tracks": tracks,
            "unavailable_subtitles": unavailable,
        }
        temporary_manifest = manifest_path.with_name(
            f"{manifest_path.name}.{os.getpid()}.tmp"
        )
        temporary_manifest.write_text(json.dumps(manifest), encoding="utf-8")
        os.replace(temporary_manifest, manifest_path)
        transcode_status[cache_key] = {
            "state": "ready",
            "progress": 100,
            **manifest,
            **media_response(item_id, manifest),
        }
    except Exception as error:
        logger.exception("Failed to prepare browser media for %s", file_path)
        transcode_status[cache_key] = {"state": "failed", "error": str(error)}


def media_response(item_id, manifest):
    compatible = manifest["transcode_required"]
    conn = get_db()
    external_tracks = conn.execute(
        "SELECT id, label, language FROM external_subtitles "
        "WHERE item_id = ? ORDER BY rowid",
        (item_id,),
    ).fetchall()
    conn.close()
    tracks = [
        {
            **track,
            "src": f"/subtitles/{item_id}/{track['index']}",
        }
        for track in manifest["tracks"]
    ]
    tracks.extend(
        {
            "id": track["id"],
            "label": track["label"],
            "language": track["language"],
            "src": f"/custom-subtitles/{track['id']}",
            "default": False,
        }
        for track in external_tracks
    )
    return {
        "url": (
            f"/stream/{item_id}?compatible=true"
            if compatible else f"/stream/{item_id}"
        ),
        "tracks": tracks,
        "unavailable_subtitles": manifest["unavailable_subtitles"],
    }


def ready_media_response(item_id, manifest, cached_transcode):
    return {
        "state": "ready",
        **media_response(item_id, manifest),
        "cached_transcode": cached_transcode,
    }


def load_media_manifest(output_path):
    manifest_path = output_path.with_suffix(".json")
    if not manifest_path.is_file():
        return None
    try:
        return json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        logger.warning("Ignoring invalid media manifest %s", manifest_path)
        return None


def remove_transcoded_cache(output_path, cache_key):
    if not output_path.parent.exists():
        return
    for cached_file in output_path.parent.iterdir():
        if cached_file.is_file() and (
            cached_file.name == output_path.name
            or cached_file.name.startswith(f"{cache_key}.")
        ):
            cached_file.unlink()


def get_course_directory(course_path):
    courses_root = Path(COURSES_DIR).resolve()
    course_directory = Path(course_path)
    if course_directory.is_symlink():
        raise HTTPException(status_code=400, detail="Refusing to delete a linked course directory")

    resolved_directory = course_directory.resolve()
    if resolved_directory.parent != courses_root or resolved_directory == courses_root:
        raise HTTPException(status_code=400, detail="Course directory is outside the configured courses folder")
    return resolved_directory


def init_db():
    os.makedirs(DATA_DIR, exist_ok=True)
    conn = get_db()
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS courses (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT UNIQUE,
            path TEXT,
            cover_url TEXT
        );
        CREATE TABLE IF NOT EXISTS course_items (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            course_id INTEGER,
            parent_id INTEGER,
            name TEXT,
            path TEXT,
            item_type TEXT,
            filename TEXT,
            sort_order INTEGER,
            FOREIGN KEY (course_id) REFERENCES courses(id),
            FOREIGN KEY (parent_id) REFERENCES course_items(id)
        );
        CREATE TABLE IF NOT EXISTS progress (
            item_id INTEGER PRIMARY KEY,
            watched_seconds REAL DEFAULT 0,
            duration REAL DEFAULT 0,
            is_completed BOOLEAN DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS external_subtitles (
            id TEXT PRIMARY KEY,
            item_id INTEGER NOT NULL,
            label TEXT NOT NULL,
            language TEXT NOT NULL,
            path TEXT NOT NULL,
            FOREIGN KEY (item_id) REFERENCES course_items(id)
        );
    """)
    progress_columns = {
        row[1] for row in conn.execute("PRAGMA table_info(progress)").fetchall()
    }
    if "last_watched_at" not in progress_columns:
        conn.execute("ALTER TABLE progress ADD COLUMN last_watched_at REAL DEFAULT 0")
    conn.commit()
    conn.close()

def scan_courses():
    conn = get_db()
    cursor = conn.cursor()

    courses_path = Path(COURSES_DIR)
    if not courses_path.exists():
        courses_path.mkdir(parents=True, exist_ok=True)

    course_dirs = sorted(
        (path for path in courses_path.iterdir() if path.is_dir()),
        key=lambda path: natural_sort_key(path.name),
    )
    active_course_paths = {str(path) for path in course_dirs}

    for course in cursor.execute("SELECT id, path FROM courses").fetchall():
        if course["path"] not in active_course_paths:
            cursor.execute(
                "DELETE FROM progress WHERE item_id IN "
                "(SELECT id FROM course_items WHERE course_id = ?)",
                (course["id"],),
            )
            cursor.execute("DELETE FROM course_items WHERE course_id = ?", (course["id"],))
            cursor.execute("DELETE FROM courses WHERE id = ?", (course["id"],))

    existing_courses = {
        row["path"]: row["id"]
        for row in cursor.execute("SELECT id, path FROM courses").fetchall()
    }
    existing_items = {
        row["path"]: row
        for row in cursor.execute("SELECT id, path FROM course_items").fetchall()
    }
    seen_item_paths = set()

    for course_dir in course_dirs:
        cover_url = None
        for cover_name in sorted(COVER_NAMES):
            if (course_dir / cover_name).exists():
                cover_url = f"/covers/{course_dir.name}/{cover_name}"
                break

        course_path = str(course_dir)
        course_id = existing_courses.get(course_path)
        if course_id is None:
            cursor.execute(
                "INSERT INTO courses (name, path, cover_url) VALUES (?, ?, ?)",
                (course_dir.name, course_path, cover_url),
            )
            course_id = cursor.lastrowid
        else:
            cursor.execute(
                "UPDATE courses SET name = ?, cover_url = ? WHERE id = ?",
                (course_dir.name, cover_url, course_id),
            )

        def upsert_item(item, parent_id, item_type, filename=None):
            item_path = str(item)
            name = item.stem if item_type == "video" else item.name
            sort_order = 1 if item_type == "video" else 0
            existing = existing_items.get(item_path)

            if existing:
                item_id = existing["id"]
                cursor.execute(
                    "UPDATE course_items SET course_id = ?, parent_id = ?, name = ?, "
                    "item_type = ?, filename = ?, sort_order = ? WHERE id = ?",
                    (course_id, parent_id, name, item_type, filename, sort_order, item_id),
                )
            else:
                cursor.execute(
                    "INSERT INTO course_items "
                    "(course_id, parent_id, name, path, item_type, filename, sort_order) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (course_id, parent_id, name, item_path, item_type, filename, sort_order),
                )
                item_id = cursor.lastrowid

            seen_item_paths.add(item_path)
            return item_id

        def scan_directory(dir_path, parent_id=None):
            items = sorted(
                dir_path.iterdir(),
                key=lambda path: (not path.is_dir(), natural_sort_key(path.name)),
            )
            for item in items:
                if item.is_dir():
                    folder_id = upsert_item(item, parent_id, "folder")
                    scan_directory(item, folder_id)
                elif item.suffix.lower() in VIDEO_EXTENSIONS:
                    upsert_item(item, parent_id, "video", item.name)

        scan_directory(course_dir)

    stale_items = [
        row for path, row in existing_items.items()
        if path not in seen_item_paths
    ]
    for item in stale_items:
        cursor.execute("DELETE FROM progress WHERE item_id = ?", (item["id"],))
        cursor.execute("DELETE FROM course_items WHERE id = ?", (item["id"],))

    conn.commit()
    conn.close()

@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    scan_courses()
    yield

app = FastAPI(lifespan=lifespan)
app.mount("/static", StaticFiles(directory="static"), name="static")
templates = Jinja2Templates(directory="templates")

@app.get("/covers/{course_name}/{filename}")
async def serve_cover(course_name: str, filename: str):
    file_path = Path(COURSES_DIR) / course_name / filename
    if not file_path.exists():
        raise HTTPException(status_code=404)
    return FileResponse(file_path)


@app.post("/api/stream/{item_id}/prepare")
async def prepare_video_stream(item_id: int):
    file_path = get_video_path(item_id)
    output_path, cache_key = get_transcoded_path(file_path)
    manifest_path = output_path.with_suffix(".json")

    manifest = load_media_manifest(output_path)
    if manifest is not None:
        return ready_media_response(item_id, manifest, output_path.is_file())

    current_status = transcode_status.get(cache_key)
    if current_status and current_status["state"] in {"processing", "ready"}:
        if current_status["state"] == "ready":
            return ready_media_response(
                item_id,
                {
                    "transcode_required": current_status["transcode_required"],
                    "tracks": current_status["tracks"],
                    "unavailable_subtitles": current_status["unavailable_subtitles"],
                },
                output_path.is_file(),
            )
        return current_status

    if cache_key in transcode_jobs and not transcode_jobs[cache_key].done():
        return transcode_status[cache_key]

    try:
        video_codec, pixel_format, audio_codecs, duration, subtitles = await probe_video(file_path)
    except FileNotFoundError as error:
        raise HTTPException(
            status_code=503,
            detail="FFmpeg and FFprobe are required for browser-compatible video playback",
        ) from error
    except (RuntimeError, ValueError, json.JSONDecodeError) as error:
        raise HTTPException(status_code=422, detail=f"Unable to prepare video: {error}") from error

    transcode_required = not (
        file_path.suffix.lower() == ".mp4"
        and video_codec == "h264"
        and pixel_format in {"yuv420p", "yuvj420p"}
        and all(codec == "aac" for codec in audio_codecs)
    )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    transcode_status[cache_key] = {
        "state": "processing",
        "stage": "video" if transcode_required and not output_path.is_file() else "subtitles",
        "progress": 0,
        "item_id": item_id,
    }
    transcode_jobs[cache_key] = asyncio.create_task(
        prepare_media(
            file_path, output_path, manifest_path, cache_key,
            video_codec, pixel_format, audio_codecs, duration, subtitles,
            transcode_required, item_id,
        )
    )
    return transcode_status[cache_key]


@app.get("/api/stream/{item_id}/status")
async def get_video_stream_status(item_id: int):
    file_path = get_video_path(item_id)
    output_path, cache_key = get_transcoded_path(file_path)
    manifest = load_media_manifest(output_path)
    if manifest is not None:
        return ready_media_response(item_id, manifest, output_path.is_file())
    return transcode_status.get(cache_key, {"state": "idle"})


@app.delete("/api/videos/{item_id}/transcoded")
async def delete_transcoded_video(item_id: int):
    file_path = get_video_path(item_id)
    output_path, cache_key = get_transcoded_path(file_path)
    job = transcode_jobs.get(cache_key)
    if job is not None and not job.done():
        job.cancel()
        try:
            await job
        except asyncio.CancelledError:
            pass

    cache_dir = output_path.parent
    if cache_dir.exists():
        try:
            for cached_file in cache_dir.iterdir():
                if cached_file.is_file() and (
                    cached_file.name == output_path.name
                    or cached_file.name.startswith(f"{cache_key}.")
                ):
                    cached_file.unlink()
        except OSError as error:
            logger.exception("Could not delete transcoded cache for video %s", item_id)
            raise HTTPException(
                status_code=500,
                detail="Could not delete all generated video cache files",
            ) from error

    transcode_jobs.pop(cache_key, None)
    transcode_status.pop(cache_key, None)
    return {"deleted": True}


@app.get("/subtitles/{item_id}/{stream_index}")
async def serve_subtitle(item_id: int, stream_index: int):
    file_path = get_video_path(item_id)
    output_path, _ = get_transcoded_path(file_path)
    manifest = load_media_manifest(output_path)
    if manifest is None or not any(
        track["index"] == stream_index for track in manifest["tracks"]
    ):
        raise HTTPException(status_code=404)

    subtitle_path = output_path.with_name(
        f"{output_path.stem}.{stream_index}.vtt"
    )
    if not subtitle_path.is_file():
        raise HTTPException(status_code=404)
    return FileResponse(subtitle_path, media_type="text/vtt")


@app.post("/api/videos/{item_id}/subtitles")
async def import_subtitle(
    item_id: int,
    file: UploadFile = File(...),
    label: str | None = Form(None),
    language: str = Form("und"),
):
    get_video_path(item_id)
    original_name = Path(file.filename or "").name
    extension = Path(original_name).suffix.lower()
    if extension not in {".srt", ".vtt"}:
        raise HTTPException(status_code=415, detail="Choose an .srt or .vtt subtitle file")

    normalized_language = language.strip()
    if not re.fullmatch(r"(?:[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*|und)", normalized_language):
        raise HTTPException(status_code=422, detail="Enter a valid language code, such as en or en-US")

    track_label = (label or Path(original_name).stem).strip()
    if not track_label or len(track_label) > 100:
        raise HTTPException(status_code=422, detail="Subtitle label must be 1 to 100 characters")

    subtitle_dir = Path(DATA_DIR) / "subtitles" / str(item_id)
    subtitle_dir.mkdir(parents=True, exist_ok=True)
    track_id = uuid.uuid4().hex
    source_path = subtitle_dir / f"{track_id}{extension}"
    output_path = subtitle_dir / f"{track_id}.vtt"
    total_size = 0

    try:
        with source_path.open("wb") as source_file:
            while chunk := await file.read(STREAM_CHUNK_SIZE):
                total_size += len(chunk)
                if total_size > MAX_SUBTITLE_UPLOAD_SIZE:
                    raise HTTPException(
                        status_code=413,
                        detail="Subtitle files must be 10 MB or smaller",
                    )
                source_file.write(chunk)

        if total_size == 0:
            raise HTTPException(status_code=422, detail="The subtitle file is empty")

        if extension == ".vtt":
            with source_path.open("rb") as subtitle_file:
                header = subtitle_file.read(512).decode("utf-8-sig", errors="replace").lstrip()
            if not header.startswith("WEBVTT"):
                raise HTTPException(status_code=422, detail="The .vtt file does not have a valid WEBVTT header")
            os.replace(source_path, output_path)
        else:
            process = await asyncio.create_subprocess_exec(
                "ffmpeg",
                "-nostdin",
                "-hide_banner",
                "-loglevel", "error",
                "-y",
                "-i", str(source_path),
                "-map", "0:s:0",
                "-c:s", "webvtt",
                "-f", "webvtt",
                str(output_path),
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.PIPE,
            )
            _, stderr = await process.communicate()
            if process.returncode:
                message = stderr.decode(errors="replace").strip()
                raise HTTPException(
                    status_code=422,
                    detail=message or "FFmpeg could not convert this SRT file",
                )

        conn = get_db()
        conn.execute(
            "INSERT INTO external_subtitles (id, item_id, label, language, path) "
            "VALUES (?, ?, ?, ?, ?)",
            (track_id, item_id, track_label, normalized_language, str(output_path)),
        )
        conn.commit()
        conn.close()
    except Exception:
        source_path.unlink(missing_ok=True)
        output_path.unlink(missing_ok=True)
        raise
    finally:
        await file.close()

    return {
        "id": track_id,
        "label": track_label,
        "language": normalized_language,
        "src": f"/custom-subtitles/{track_id}",
        "default": False,
    }


@app.get("/custom-subtitles/{track_id}")
async def serve_custom_subtitle(track_id: str):
    conn = get_db()
    track = conn.execute(
        "SELECT path FROM external_subtitles WHERE id = ?",
        (track_id,),
    ).fetchone()
    conn.close()
    if not track:
        raise HTTPException(status_code=404)

    subtitle_path = Path(track["path"])
    if not subtitle_path.is_file():
        raise HTTPException(status_code=404)
    return FileResponse(subtitle_path, media_type="text/vtt")


@app.delete("/api/videos/{item_id}/subtitles/{track_id}")
async def delete_imported_subtitle(item_id: int, track_id: str):
    get_video_path(item_id)
    conn = get_db()
    track = conn.execute(
        "SELECT path FROM external_subtitles WHERE id = ? AND item_id = ?",
        (track_id, item_id),
    ).fetchone()
    if not track:
        conn.close()
        raise HTTPException(status_code=404, detail="Imported subtitle not found")

    subtitle_path = Path(track["path"])
    try:
        subtitle_path.unlink(missing_ok=True)
        conn.execute(
            "DELETE FROM external_subtitles WHERE id = ? AND item_id = ?",
            (track_id, item_id),
        )
        conn.commit()
    except OSError as error:
        conn.rollback()
        logger.exception("Could not delete imported subtitle %s", track_id)
        raise HTTPException(
            status_code=500,
            detail="Could not delete the subtitle file",
        ) from error
    finally:
        conn.close()

    return {"deleted": True}


@app.get("/stream/{item_id}")
async def stream_video(item_id: int, request: Request):
    file_path = get_video_path(item_id)
    if request.query_params.get("compatible") == "true":
        file_path, _ = get_transcoded_path(file_path)
        if not file_path.is_file():
            raise HTTPException(status_code=409, detail="Compatible video is not ready")

    file_size = os.path.getsize(file_path)
    range_header = request.headers.get("range")

    media_type = mimetypes.guess_type(file_path)[0] or "application/octet-stream"
    response_headers = {"Accept-Ranges": "bytes"}

    if range_header:
        range_unit, separator, range_spec = range_header.strip().partition("=")
        range_start, range_separator, range_end = range_spec.partition("-")
        if (
            not separator
            or range_unit.lower() != "bytes"
            or not range_separator
            or "," in range_spec
        ):
            raise HTTPException(
                status_code=416,
                headers={**response_headers, "Content-Range": f"bytes */{file_size}"},
            )

        try:
            if not range_start:
                suffix_length = int(range_end)
                if suffix_length <= 0:
                    raise ValueError
                start = max(file_size - suffix_length, 0)
                end = file_size - 1
            else:
                start = int(range_start)
                end = int(range_end) if range_end else file_size - 1
                end = min(end, file_size - 1)
        except ValueError:
            raise HTTPException(
                status_code=416,
                headers={**response_headers, "Content-Range": f"bytes */{file_size}"},
            ) from None

        if file_size == 0 or start >= file_size or end < start:
            raise HTTPException(
                status_code=416,
                headers={**response_headers, "Content-Range": f"bytes */{file_size}"},
            )

        content_length = end - start + 1

        def iter_file_range():
            with open(file_path, "rb") as video_file:
                video_file.seek(start)
                remaining = content_length
                while remaining:
                    chunk = video_file.read(min(STREAM_CHUNK_SIZE, remaining))
                    if not chunk:
                        break
                    remaining -= len(chunk)
                    yield chunk

        response_headers.update({
            "Content-Range": f"bytes {start}-{end}/{file_size}",
            "Content-Length": str(content_length),
            "Content-Type": media_type,
        })
        return StreamingResponse(
            iter_file_range(),
            status_code=206,
            headers=response_headers,
        )

    return FileResponse(
        file_path,
        media_type=media_type,
        headers=response_headers,
    )

@app.get("/file/{item_id}")
async def serve_file(item_id: int):
    conn = get_db()
    item = conn.execute("SELECT path, name FROM course_items WHERE id = ?", (item_id,)).fetchone()
    conn.close()
    
    if not item:
        raise HTTPException(status_code=404)
        
    file_path = item["path"]
    if not os.path.exists(file_path):
        raise HTTPException(status_code=404)

    return FileResponse(file_path, filename=item["name"])

@app.get("/", response_class=HTMLResponse)
async def dashboard(request: Request):
    conn = get_db()
    continue_watching = conn.execute("""
        SELECT id, name, item_type, course_id, course_name, cover_url, watched_seconds, duration, last_watched_at
        FROM (
            SELECT ci.id, ci.name, ci.item_type, c.id as course_id, c.name as course_name,
                   c.cover_url, p.watched_seconds, p.duration, p.last_watched_at,
                   ROW_NUMBER() OVER (
                       PARTITION BY c.id
                       ORDER BY COALESCE(NULLIF(p.last_watched_at, 0), p.watched_seconds) DESC,
                                ci.id DESC
                   ) as course_rank
            FROM progress p
            JOIN course_items ci ON p.item_id = ci.id
            JOIN courses c ON ci.course_id = c.id
            WHERE ci.item_type = 'video' AND p.watched_seconds > 0 AND p.is_completed = 0
        )
        WHERE course_rank = 1
        ORDER BY COALESCE(NULLIF(last_watched_at, 0), watched_seconds) DESC
    """).fetchall()
    
    courses = conn.execute("SELECT * FROM courses").fetchall()
    # Sort courses naturally
    courses = sorted(courses, key=lambda x: natural_sort_key(x["name"]))
    course_data = []
    for course in courses:
        total_videos = conn.execute(
            "SELECT COUNT(*) FROM course_items WHERE course_id = ? AND item_type = 'video'", 
            (course["id"],)
        ).fetchone()[0]
        
        completed_videos = conn.execute("""
            SELECT COUNT(*) FROM progress p 
            JOIN course_items ci ON p.item_id = ci.id 
            WHERE ci.course_id = ? AND ci.item_type = 'video' AND p.is_completed = 1
        """, (course["id"],)).fetchone()[0]
        
        course_data.append({
            **dict(course),
            "total": total_videos,
            "completed": completed_videos,
            "percentage": int((completed_videos / total_videos) * 100) if total_videos > 0 else 0
        })
    conn.close()
    return templates.TemplateResponse("index.html", {
        "request": request, 
        "continue_watching": continue_watching, 
        "courses": course_data
    })

@app.get("/course/{course_id}", response_class=HTMLResponse)
async def course_detail(request: Request, course_id: int):
    conn = get_db()
    course = conn.execute("SELECT * FROM courses WHERE id = ?", (course_id,)).fetchone()
    if not course:
        raise HTTPException(status_code=404)
    
    total_videos = conn.execute(
        "SELECT COUNT(*) FROM course_items WHERE course_id = ? AND item_type = 'video'", 
        (course_id,)
    ).fetchone()[0]
    
    completed_videos = conn.execute("""
        SELECT COUNT(*) FROM progress p 
        JOIN course_items ci ON p.item_id = ci.id 
        WHERE ci.course_id = ? AND ci.item_type = 'video' AND p.is_completed = 1
    """, (course_id,)).fetchone()[0]
    
    completion_percentage = int((completed_videos / total_videos) * 100) if total_videos > 0 else 0
        
    def get_items(parent_id=None):
        items = conn.execute("""
            SELECT ci.*, COALESCE(p.is_completed, 0) as is_completed, COALESCE(p.watched_seconds, 0) as watched_seconds
            FROM course_items ci
            LEFT JOIN progress p ON ci.id = p.item_id
            WHERE ci.course_id = ? AND ci.parent_id {}
            ORDER BY ci.sort_order ASC
        """.format("IS NULL" if parent_id is None else "= ?"), 
        (course_id,) if parent_id is None else (course_id, parent_id)
        ).fetchall()
        
        # Sort items naturally: folders first, then files
        sorted_items = sorted(items, key=lambda x: (x["sort_order"], natural_sort_key(x["name"])))
        
        result = []
        for item in sorted_items:
            item_dict = dict(item)
            if item_dict["item_type"] == "folder":
                item_dict["children"] = get_items(item_dict["id"])
            result.append(item_dict)
        return result
    
    items = get_items()
    conn.close()
    
    return templates.TemplateResponse("course.html", {
        "request": request, 
        "course": course, 
        "items": items,
        "total_videos": total_videos,
        "completed_videos": completed_videos,
        "completion_percentage": completion_percentage
    })


@app.delete("/api/courses/{course_id}")
async def delete_course(course_id: int):
    conn = get_db()
    course = conn.execute(
        "SELECT id, path FROM courses WHERE id = ?",
        (course_id,),
    ).fetchone()
    if not course:
        conn.close()
        raise HTTPException(status_code=404, detail="Course not found")

    items = conn.execute(
        "SELECT id, path, item_type FROM course_items WHERE course_id = ?",
        (course_id,),
    ).fetchall()
    item_ids = [item["id"] for item in items]
    videos = [item for item in items if item["item_type"] == "video"]
    imported_subtitles = []
    if item_ids:
        placeholders = ",".join("?" for _ in item_ids)
        imported_subtitles = conn.execute(
            f"SELECT path FROM external_subtitles WHERE item_id IN ({placeholders})",
            item_ids,
        ).fetchall()
    conn.close()

    course_directory = get_course_directory(course["path"])
    cache_entries = []
    for video in videos:
        video_path = Path(video["path"])
        if video_path.is_file():
            output_path, cache_key = get_transcoded_path(video_path)
            cache_entries.append((output_path, cache_key))

    for _, cache_key in cache_entries:
        job = transcode_jobs.get(cache_key)
        if job is not None and not job.done():
            job.cancel()
            try:
                await job
            except asyncio.CancelledError:
                pass

    try:
        for output_path, cache_key in cache_entries:
            remove_transcoded_cache(output_path, cache_key)

        for video in videos:
            subtitle_dir = (Path(DATA_DIR) / "subtitles" / str(video["id"])).resolve()
            for subtitle in imported_subtitles:
                subtitle_path = Path(subtitle["path"])
                if subtitle_path.resolve().parent == subtitle_dir:
                    subtitle_path.unlink(missing_ok=True)

        if course_directory.exists():
            shutil.rmtree(course_directory)
    except OSError as error:
        logger.exception("Could not completely delete course %s", course_id)
        raise HTTPException(
            status_code=500,
            detail="Could not completely delete the course files and generated media",
        ) from error

    conn = get_db()
    try:
        if item_ids:
            placeholders = ",".join("?" for _ in item_ids)
            conn.execute(
                f"DELETE FROM progress WHERE item_id IN ({placeholders})",
                item_ids,
            )
            conn.execute(
                f"DELETE FROM external_subtitles WHERE item_id IN ({placeholders})",
                item_ids,
            )
        conn.execute("DELETE FROM course_items WHERE course_id = ?", (course_id,))
        conn.execute("DELETE FROM courses WHERE id = ?", (course_id,))
        conn.commit()
    except sqlite3.Error as error:
        conn.rollback()
        logger.exception("Could not remove course %s from the database", course_id)
        raise HTTPException(
            status_code=500,
            detail="Course files were removed, but its database records could not be deleted",
        ) from error
    finally:
        conn.close()

    for _, cache_key in cache_entries:
        transcode_jobs.pop(cache_key, None)
        transcode_status.pop(cache_key, None)

    return {"deleted": True}


@app.get("/video/{item_id}", response_class=HTMLResponse)
async def video_player(request: Request, item_id: int):
    conn = get_db()
    video = conn.execute("""
        SELECT ci.*, c.name as course_name, c.id as course_id, 
        COALESCE(p.watched_seconds, 0) as watched_seconds,
        COALESCE(p.is_completed, 0) as is_completed
        FROM course_items ci
        JOIN courses c ON ci.course_id = c.id
        LEFT JOIN progress p ON ci.id = p.item_id
        WHERE ci.id = ? AND ci.item_type = 'video'
    """, (item_id,)).fetchone()
    
    if not video:
        raise HTTPException(status_code=404)
    
    # Fetch all course items (files and folders)
    def get_items(parent_id=None):
        items = conn.execute("""
            SELECT ci.*, COALESCE(p.is_completed, 0) as is_completed, COALESCE(p.watched_seconds, 0) as watched_seconds
            FROM course_items ci
            LEFT JOIN progress p ON ci.id = p.item_id
            WHERE ci.course_id = ? AND ci.parent_id {}
            ORDER BY ci.sort_order ASC
        """.format("IS NULL" if parent_id is None else "= ?"), 
        (video["course_id"],) if parent_id is None else (video["course_id"], parent_id)
        ).fetchall()
        
        # Sort items naturally: folders first, then files
        sorted_items = sorted(items, key=lambda x: (x["sort_order"], natural_sort_key(x["name"])))
        
        result = []
        for item in sorted_items:
            item_dict = dict(item)
            if item_dict["item_type"] == "folder":
                item_dict["children"] = get_items(item_dict["id"])
            result.append(item_dict)
        return result
    
    course_items = get_items()

    def flatten_videos(items):
        ids = []
        for item in items:
            if item["item_type"] == "video":
                ids.append(item["id"])
            elif item["item_type"] == "folder":
                ids.extend(flatten_videos(item["children"]))
        return ids

    video_ids = flatten_videos(course_items)
    current_idx = video_ids.index(item_id)
    next_video = video_ids[current_idx + 1] if current_idx < len(video_ids) - 1 else None
    prev_video = video_ids[current_idx - 1] if current_idx > 0 else None
    conn.close()
    
    return templates.TemplateResponse("video.html", {
        "request": request, 
        "video": video, 
        "next_video": next_video, 
        "prev_video": prev_video,
        "course_items": course_items
    })

@app.get("/search", response_class=HTMLResponse)
async def search(request: Request, q: str = Query("")):
    conn = get_db()
    query = f"%{q}%"
    courses = conn.execute("SELECT * FROM courses WHERE name LIKE ?", (query,)).fetchall()
    videos = conn.execute("""
        SELECT ci.*, c.name as course_name, c.id as course_id 
        FROM course_items ci
        JOIN courses c ON ci.course_id = c.id 
        WHERE (ci.name LIKE ? OR ci.filename LIKE ?) AND ci.item_type = 'video'
    """, (query, query)).fetchall()
    conn.close()
    return templates.TemplateResponse("search.html", {"request": request, "query": q, "courses": courses, "videos": videos})

@app.post("/rescan")
async def rescan_folder():
    scan_courses()
    return RedirectResponse(url="/", status_code=303)

@app.post("/api/progress")
async def update_progress(progress: ProgressUpdate):
    conn = get_db()
    is_completed = 1 if progress.watched_seconds >= (progress.duration * 0.95) else 0
    
    conn.execute("""
        INSERT INTO progress (item_id, watched_seconds, duration, is_completed, last_watched_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(item_id) DO UPDATE SET
        watched_seconds = MAX(excluded.watched_seconds, watched_seconds),
        duration = excluded.duration,
        is_completed = MAX(excluded.is_completed, is_completed),
        last_watched_at = excluded.last_watched_at
    """, (progress.video_id, progress.watched_seconds, progress.duration, is_completed, time.time()))
    conn.commit()
    conn.close()
    return {"status": "success"}

@app.post("/api/progress/{video_id}/reset")
async def reset_progress(video_id: int):
    conn = get_db()
    video = conn.execute(
        "SELECT id FROM course_items WHERE id = ? AND item_type = 'video'",
        (video_id,)
    ).fetchone()
    if not video:
        conn.close()
        raise HTTPException(status_code=404, detail="Video not found")

    conn.execute(
        """
        UPDATE progress
        SET is_completed = 0, watched_seconds = 0, last_watched_at = 0
        WHERE item_id = ?
        """,
        (video_id,)
    )
    conn.commit()
    conn.close()
    return {"status": "success"}