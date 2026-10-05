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


def test_rescanning_preserves_course_video_ids_and_progress(tmp_path, monkeypatch):
    courses_dir = tmp_path / "courses"
    data_dir = tmp_path / "data"
    course_dir = courses_dir / "Demo Course"
    nested_dir = course_dir / "nested"
    nested_dir.mkdir(parents=True)
    data_dir.mkdir()
    (course_dir / "video.mp4").write_bytes(b"video")
    (nested_dir / "clip.mov").write_bytes(b"movie")

    monkeypatch.setattr(main, "COURSES_DIR", str(courses_dir))
    monkeypatch.setattr(main, "DATA_DIR", str(data_dir))
    monkeypatch.setattr(main, "DB_PATH", str(data_dir / "lms.db"))

    main.init_db()
    main.scan_courses()
    conn = sqlite3.connect(main.DB_PATH)
    course_id = conn.execute("SELECT id FROM courses WHERE name = ?", ("Demo Course",)).fetchone()[0]
    item_ids = dict(conn.execute("SELECT path, id FROM course_items"))
    video_id = item_ids[str(course_dir / "video.mp4")]
    conn.execute(
        "INSERT INTO progress (item_id, watched_seconds, duration, is_completed, last_watched_at) "
        "VALUES (?, ?, ?, ?, ?)",
        (video_id, 42, 120, 0, 1234),
    )
    conn.commit()
    conn.close()

    main.scan_courses()

    conn = sqlite3.connect(main.DB_PATH)
    rescanned_course_id = conn.execute(
        "SELECT id FROM courses WHERE name = ?", ("Demo Course",)
    ).fetchone()[0]
    rescanned_item_ids = dict(conn.execute("SELECT path, id FROM course_items"))
    progress = conn.execute(
        "SELECT item_id, watched_seconds, duration, is_completed, last_watched_at FROM progress"
    ).fetchone()
    conn.close()

    assert rescanned_course_id == course_id
    assert rescanned_item_ids == item_ids
    assert progress == (video_id, 42, 120, 0, 1234)

    (course_dir / "video.mp4").unlink()
    main.scan_courses()

    conn = sqlite3.connect(main.DB_PATH)
    assert conn.execute(
        "SELECT 1 FROM course_items WHERE id = ?", (video_id,)
    ).fetchone() is None
    assert conn.execute(
        "SELECT 1 FROM progress WHERE item_id = ?", (video_id,)
    ).fetchone() is None
    conn.close()
