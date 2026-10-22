import asyncio
import sqlite3

import main


def configure_database(tmp_path, monkeypatch):
    courses_dir = tmp_path / "courses"
    data_dir = tmp_path / "data"
    courses_dir.mkdir()
    data_dir.mkdir()
    monkeypatch.setattr(main, "COURSES_DIR", str(courses_dir))
    monkeypatch.setattr(main, "DATA_DIR", str(data_dir))
    monkeypatch.setattr(main, "DB_PATH", str(data_dir / "lms.db"))
    main.init_db()

    conn = sqlite3.connect(main.DB_PATH)
    conn.execute(
        "INSERT INTO courses (id, name, path) VALUES (1, 'Demo Course', ?)",
        (str(courses_dir / "Demo Course"),),
    )
    conn.execute(
        "INSERT INTO course_items (id, course_id, name, path, item_type) "
        "VALUES (1, 1, 'First video', '/first.mp4', 'video'), "
        "(2, 1, 'Second video', '/second.mp4', 'video')"
    )
    conn.commit()
    conn.close()


def test_marking_video_finished_preserves_playback_progress(tmp_path, monkeypatch):
    configure_database(tmp_path, monkeypatch)
    conn = sqlite3.connect(main.DB_PATH)
    conn.execute(
        "INSERT INTO progress "
        "(item_id, watched_seconds, duration, is_completed, last_watched_at) "
        "VALUES (2, 42, 120, 0, 100)"
    )
    conn.commit()
    conn.close()

    result = asyncio.run(main.finish_progress(1))
    assert result == {"status": "success"}

    result = asyncio.run(main.finish_progress(2))
    assert result == {"status": "success"}

    conn = sqlite3.connect(main.DB_PATH)
    progress = conn.execute(
        "SELECT item_id, watched_seconds, duration, is_completed, last_watched_at "
        "FROM progress ORDER BY item_id"
    ).fetchall()
    conn.close()

    assert progress[0][:4] == (1, 0, 0, 1)
    assert progress[1][:4] == (2, 42, 120, 1)
    assert progress[0][4] == 0
    assert progress[1][4] == 100


def test_course_shows_full_list_and_most_recently_played_video(tmp_path, monkeypatch):
    configure_database(tmp_path, monkeypatch)
    conn = sqlite3.connect(main.DB_PATH)
    conn.executemany(
        "INSERT INTO progress "
        "(item_id, watched_seconds, duration, is_completed, last_watched_at) "
        "VALUES (?, ?, ?, 0, ?)",
        [(1, 15, 100, 100), (2, 30, 120, 200)],
    )
    conn.commit()
    conn.close()

    response = asyncio.run(main.course_detail(None, 1))

    assert response.template.name == "course.html"
    assert response.context["last_played_video"]["id"] == 2
    assert [item["id"] for item in response.context["items"]] == [1, 2]
    assert b"Continue where you left off" in response.body
    assert b'href="/video/1"' in response.body
    assert b'href="/video/2"' in response.body
    assert b"Last played" in response.body
