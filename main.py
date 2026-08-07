import os
import sqlite3
import mimetypes
import re
from pathlib import Path
from fastapi import FastAPI, Request, HTTPException, Query
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
    """)
    conn.commit()
    conn.close()

def scan_courses():
    conn = get_db()
    cursor = conn.cursor()

    # Save progress keyed by file path before wiping course data
    saved_progress = {}
    rows = cursor.execute("""
        SELECT ci.path, p.watched_seconds, p.duration, p.is_completed
        FROM progress p
        JOIN course_items ci ON p.item_id = ci.id
    """).fetchall()
    for row in rows:
        saved_progress[row["path"]] = {
            "watched_seconds": row["watched_seconds"],
            "duration": row["duration"],
            "is_completed": row["is_completed"],
        }

    cursor.execute("DELETE FROM progress")
    cursor.execute("DELETE FROM course_items")
    cursor.execute("DELETE FROM courses")
    conn.commit()

    courses_path = Path(COURSES_DIR)
    if not courses_path.exists():
        courses_path.mkdir(parents=True, exist_ok=True)

    # Sort courses using natural sort
    for course_dir in sorted(courses_path.iterdir(), key=lambda x: natural_sort_key(x.name)):
        if course_dir.is_dir():
            cover_url = None
            for cover_name in sorted(COVER_NAMES):
                cover_path = course_dir / cover_name
                if cover_path.exists():
                    cover_url = f"/covers/{course_dir.name}/{cover_name}"
                    break
            
            cursor.execute(
                "INSERT INTO courses (name, path, cover_url) VALUES (?, ?, ?)",
                (course_dir.name, str(course_dir), cover_url)
            )
            course_id = cursor.lastrowid

            def scan_directory(dir_path, parent_id=None, base_path=None):
                if base_path is None:
                    base_path = dir_path
                
                # Natural sort: folders first, then files
                items = sorted(dir_path.iterdir(), key=lambda x: (not x.is_dir(), natural_sort_key(x.name)))
                
                for item in items:
                    if item.is_dir():
                        cursor.execute(
                            "INSERT INTO course_items (course_id, parent_id, name, path, item_type, sort_order) VALUES (?, ?, ?, ?, ?, ?)",
                            (course_id, parent_id, item.name, str(item), "folder", 0)
                        )
                        folder_id = cursor.lastrowid
                        scan_directory(item, folder_id, base_path)
                    else:
                        suffix = item.suffix.lower()
                        if suffix in VIDEO_EXTENSIONS:
                            item_type = "video"
                        else:
                            # Only index folders and supported video files.
                            continue
                        
                        cursor.execute(
                            "INSERT INTO course_items (course_id, parent_id, name, path, item_type, filename, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)",
                            (course_id, parent_id, item.stem, str(item), item_type, item.name, 1)
                        )

            scan_directory(course_dir)

    conn.commit()

    # Restore saved progress by matching file paths
    if saved_progress:
        items = cursor.execute("SELECT id, path FROM course_items").fetchall()
        for item in items:
            prog = saved_progress.get(item["path"])
            if prog:
                cursor.execute("""
                    INSERT INTO progress (item_id, watched_seconds, duration, is_completed)
                    VALUES (?, ?, ?, ?)
                """, (item["id"], prog["watched_seconds"], prog["duration"], prog["is_completed"]))
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

@app.get("/stream/{item_id}")
async def stream_video(item_id: int, request: Request):
    conn = get_db()
    item = conn.execute("SELECT path FROM course_items WHERE id = ?", (item_id,)).fetchone()
    conn.close()
    
    if not item:
        raise HTTPException(status_code=404)
        
    file_path = item["path"]
    if not os.path.exists(file_path):
        raise HTTPException(status_code=404)

    file_size = os.path.getsize(file_path)
    range_header = request.headers.get("range")
    
    if range_header:
        byte_range = range_header.replace("bytes=", "").split("-")
        start = int(byte_range[0]) if byte_range[0] else 0
        end = int(byte_range[1]) if byte_range[1] else file_size - 1
        length = end - start + 1
        
        with open(file_path, "rb") as f:
            f.seek(start)
            data = f.read(length)
            
        headers = {
            "Content-Range": f"bytes {start}-{end}/{file_size}",
            "Accept-Ranges": "bytes",
            "Content-Length": str(length),
            "Content-Type": mimetypes.guess_type(file_path)[0] or "video/mp4",
        }
        return StreamingResponse(iter([data]), status_code=206, headers=headers)

    return FileResponse(file_path, media_type=mimetypes.guess_type(file_path)[0] or "video/mp4")

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
        SELECT ci.id, ci.name, ci.item_type, c.id as course_id, c.name as course_name, c.cover_url, p.watched_seconds, p.duration
        FROM progress p
        JOIN course_items ci ON p.item_id = ci.id
        JOIN courses c ON ci.course_id = c.id
        WHERE ci.item_type = 'video' AND p.watched_seconds > 0 AND p.is_completed = 0
        ORDER BY p.watched_seconds DESC LIMIT 6
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

@app.get("/video/{item_id}", response_class=HTMLResponse)
async def video_player(request: Request, item_id: int):
    conn = get_db()
    video = conn.execute("""
        SELECT ci.*, c.name as course_name, c.id as course_id, 
        COALESCE(p.watched_seconds, 0) as watched_seconds
        FROM course_items ci
        JOIN courses c ON ci.course_id = c.id
        LEFT JOIN progress p ON ci.id = p.item_id
        WHERE ci.id = ? AND ci.item_type = 'video'
    """, (item_id,)).fetchone()
    
    if not video:
        raise HTTPException(status_code=404)
    
    all_course_videos = conn.execute(
        "SELECT id, name FROM course_items WHERE course_id = ? AND item_type = 'video'", 
        (video["course_id"],)
    ).fetchall()
    # Sort videos naturally by name
    sorted_videos = sorted(all_course_videos, key=lambda x: natural_sort_key(x["name"]))
    video_ids = [v["id"] for v in sorted_videos]
    current_idx = video_ids.index(item_id)
    
    next_video = video_ids[current_idx + 1] if current_idx < len(video_ids) - 1 else None
    prev_video = video_ids[current_idx - 1] if current_idx > 0 else None
    
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
        INSERT INTO progress (item_id, watched_seconds, duration, is_completed)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(item_id) DO UPDATE SET
        watched_seconds = MAX(excluded.watched_seconds, watched_seconds),
        duration = excluded.duration,
        is_completed = MAX(excluded.is_completed, is_completed)
    """, (progress.video_id, progress.watched_seconds, progress.duration, is_completed))
    conn.commit()
    conn.close()
    return {"status": "success"}