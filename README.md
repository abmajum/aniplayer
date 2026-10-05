# aniplayer

Aniplayer is a self-hosted course video library. It scans a local courses directory, builds a browsable course tree, streams supported video files, and stores watch progress in SQLite.

## How It Works

1. On startup, the application creates the SQLite database and scans `COURSES_DIR`.
2. Every direct subdirectory of `COURSES_DIR` is treated as a course. The directory name becomes the course name.
3. Subdirectories inside a course are shown as folders. Supported video files are shown as lessons.
4. The dashboard shows course completion and a collapsible Continue Watching section. Continue Watching shows the latest unfinished lesson for each course. Opening a lesson resumes it from the last saved position.
5. Progress is saved while a video plays, when it is paused, and when it ends. A video is marked complete after at least 95% has been watched.
6. The `Rescan` action refreshes the course list and file tree. Progress is restored by matching the original file paths.
7. The header Pomodoro timer provides 25-minute focus and 5-minute break sessions, with a sound alarm when either session ends. Timer state is saved in the browser and continues across page navigation. Browsers may require a click or keypress after opening the page before allowing timer audio.

The application is intended for local or trusted-network use. It does not currently provide user accounts or access control.

## Course Requirements

Place each course in its own directory under `courses/` (or the directory configured by `COURSES_DIR`). Course names and lesson names are taken from the filesystem, so descriptive directory and file names are recommended.

Supported video extensions are:

- `.mp4`
- `.mkv`
- `.webm`
- `.avi`
- `.mov`

Other files are ignored by the scanner. Empty directories are still indexed and displayed as folders.

### Cover Images

An optional cover image can be placed in the course's top-level directory. The scanner checks these names in priority order:

1. `cover.jpg`
2. `cover.png`
3. `folder.jpg`
4. `poster.jpg`

Only the first matching filename is used. A course without a cover image uses the built-in placeholder background.

### Example Course Structure

```text
courses/
└── Python Fundamentals/
	├── cover.png
	├── 01 - Introduction/
	│   ├── 001 Welcome.mp4
	│   └── 002 Setup.webm
	├── 02 - Variables/
	│   ├── 001 Variables.mkv
	│   └── notes.txt          # ignored
	└── bonus.mov              # supported lesson at course root
```

Natural sorting is used, so names such as `Lesson 2` appear before `Lesson 10`. Folders appear before video files at each level.

## Running With Docker

Docker and Docker Compose are required.

```bash
./start.sh
```

The application is then available at <http://localhost:8000>.

`start.sh` creates the course and data directories, builds the image, and starts the service in the background. Compose mounts the following directories into the container:

- `COURSES_PATH` (default: `./courses`) -> `/app/courses`
- `DATA_PATH` (default: `./data`) -> `/app/data`

To use directories outside the repository:

```bash
COURSES_DIR=/path/to/courses DATA_DIR=/path/to/data ./start.sh
```

To stop the service:

```bash
docker compose down
```

### Running with single command docker

```bash
mkdir -p ./courses ./data

docker run -d \
  --name aniplayer \
  --restart unless-stopped \
  -p 8000:8000 \
  -v "$PWD/courses:/app/courses" \
  -v "$PWD/data:/app/data" \
  -e COURSES_DIR=/app/courses \
  -e DATA_DIR=/app/data \
  ghcr.io/abmajum/aniplayer:latest
```


### Published Image

Pushes to the default branch and version tags (`v*`) build and publish the image to GitHub Container Registry:

```bash
docker pull ghcr.io/abmajum/aniplayer:latest
```

The workflow also publishes branch, short commit SHA, and version tags (for example, `1.2.3` and `1.2`). The first published package may need to be made public in the repository's **Packages** settings before it can be pulled without authentication.

## Running Locally

Python 3.11 or newer is recommended.

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload
```

Open <http://localhost:8000>. By default, local execution reads from `./courses` and stores the database in `./data/lms.db`. These locations can be changed with environment variables:

```bash
COURSES_DIR=/path/to/courses DATA_DIR=/path/to/data uvicorn main:app --reload
```

## Repository Layout

```text
aniplayer/
├── main.py                 # FastAPI app, scanner, database, streaming, and API routes
├── requirements.txt        # Python dependencies
├── Dockerfile              # Container image definition
├── docker-compose.yml      # Service and volume configuration
├── start.sh                # Docker startup helper
├── courses/                # Course directories and media files
├── data/                   # Persistent SQLite database (lms.db)
├── static/
│   ├── app.js              # Video progress and completion-reset behavior
│   └── style.css           # Application styles
├── templates/              # Jinja2 HTML templates
└── tests/                  # Automated tests
```

## Useful Routes

- `GET /` - dashboard
- `GET /course/{course_id}` - course contents and completion summary
- `GET /video/{item_id}` - video player
- `GET /search?q=...` - search courses and videos
- `POST /rescan` - rescan the courses directory
- `POST /api/progress` - save video progress
- `POST /api/progress/{video_id}/reset` - clear completion for a video while preserving its watch position

The SQLite database is created automatically at `DATA_DIR/lms.db`; no migration command is required for a fresh installation. Existing databases are upgraded automatically with the watch-activity timestamp used by Continue Watching.


## TODO: Bugs
if we build and restart the application from scratch the courses id changes. Earlier it was 35 now it is 38