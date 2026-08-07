import os
import shutil
import sqlite3
import tempfile
from pathlib import Path

import main


def test_scan_courses_only_indexes_directories_and_video_files(tmp_path, monkeypatch):
    courses_dir = tmp_path / "courses"
    data_dir = tmp_path / "data"
    courses_dir.mkdir()
    data_dir.mkdir()

    course_dir = courses_dir / "Demo Course"
    course_dir.mkdir()
    (course_dir / "video.mp4").write_bytes(b"video")
    (course_dir / "notes.txt").write_bytes(b"notes")
    (course_dir / "slides.pdf").write_bytes(b"pdf")
    (course_dir / "subtitles.srt").write_bytes(b"srt")
    nested_dir = course_dir / "nested"
    nested_dir.mkdir()
    (nested_dir / "clip.mov").write_bytes(b"movie")

    monkeypatch.setattr(main, "COURSES_DIR", str(courses_dir))
    monkeypatch.setattr(main, "DATA_DIR", str(data_dir))
    monkeypatch.setattr(main, "DB_PATH", str(data_dir / "lms.db"))

    main.init_db()
    main.scan_courses()

    conn = sqlite3.connect(main.DB_PATH)
    items = conn.execute("SELECT name, item_type FROM course_items ORDER BY name").fetchall()
    conn.close()

    assert ("video", "video") in items
    assert ("clip", "video") in items
    assert ("nested", "folder") in items
    assert ("Demo Course", "folder") not in items
    assert ("notes", "text") not in items
    assert ("slides", "pdf") not in items
    assert ("subtitles", "file") not in items
    assert all(item_type in {"folder", "video"} for _, item_type in items)
