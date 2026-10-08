document.addEventListener('DOMContentLoaded', () => {
    const pomodoroClock = document.getElementById('pomodoro-clock');
    if (pomodoroClock) {
        const durations = { focus: 25 * 60 * 1000, break: 5 * 60 * 1000 };
        const storageKey = 'aniplayer-pomodoro';
        const modeButtons = document.querySelectorAll('[data-pomodoro-mode]');
        const startButton = document.getElementById('pomodoro-start');
        const resetButton = document.getElementById('pomodoro-reset');
        const status = document.getElementById('pomodoro-status');
        let storedState;

        try {
            storedState = JSON.parse(localStorage.getItem(storageKey) || 'null');
        } catch (error) {
            console.error('Failed to read Pomodoro timer state', error);
        }

        const validMode = storedState && Object.hasOwn(durations, storedState.mode);
        const mode = validMode ? storedState.mode : 'focus';
        let state = {
            mode,
            remainingMs: durations[mode],
            running: false,
            endsAt: null
        };

        if (validMode && Number.isFinite(storedState.remainingMs) && storedState.remainingMs >= 0) {
            state.remainingMs = Math.min(storedState.remainingMs, durations[mode]);
            state.running = storedState.running === true && Number.isFinite(storedState.endsAt);
            state.endsAt = state.running ? storedState.endsAt : null;
            if (state.running) state.remainingMs = Math.max(0, state.endsAt - Date.now());
        }

        let completionMessage = '';
        let ticker;
        let audioContext;

        function enableAudio() {
            if (!window.AudioContext) {
                console.error('Pomodoro sound is unavailable because this browser does not support Web Audio');
                return Promise.resolve(false);
            }

            try {
                audioContext ||= new window.AudioContext();
                return audioContext.state === 'suspended'
                    ? audioContext.resume().then(() => true).catch(error => {
                        console.error('Failed to enable Pomodoro sound', error);
                        return false;
                    })
                    : Promise.resolve(true);
            } catch (error) {
                console.error('Failed to initialize Pomodoro sound', error);
                return Promise.resolve(false);
            }
        }

        async function playChime(finishedMode) {
            if (!await enableAudio()) {
                status.textContent = 'Session complete — allow browser audio to hear the alarm.';
                return;
            }

            const notes = finishedMode === 'focus' ? [660, 880, 1047] : [880, 660];
            const startAt = audioContext.currentTime;
            notes.forEach((frequency, index) => {
                const noteStart = startAt + index * 0.24;
                const oscillator = audioContext.createOscillator();
                const gain = audioContext.createGain();
                oscillator.type = 'sine';
                oscillator.frequency.setValueAtTime(frequency, noteStart);
                gain.gain.setValueAtTime(0.0001, noteStart);
                gain.gain.exponentialRampToValueAtTime(0.16, noteStart + 0.025);
                gain.gain.exponentialRampToValueAtTime(0.0001, noteStart + 0.2);
                oscillator.connect(gain);
                gain.connect(audioContext.destination);
                oscillator.start(noteStart);
                oscillator.stop(noteStart + 0.21);
            });
        }

        function saveState() {
            try {
                localStorage.setItem(storageKey, JSON.stringify(state));
            } catch (error) {
                console.error('Failed to save Pomodoro timer state', error);
            }
        }

        function render() {
            const seconds = Math.ceil(state.remainingMs / 1000);
            const minutes = Math.floor(seconds / 60);
            const remainder = seconds % 60;
            const time = `${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`;
            const modeName = state.mode === 'focus' ? 'Focus' : 'Break';
            pomodoroClock.textContent = time;
            pomodoroClock.setAttribute('aria-label', `${minutes} minutes and ${remainder} seconds remaining`);
            modeButtons.forEach(button => {
                const selected = button.dataset.pomodoroMode === state.mode;
                button.classList.toggle('active', selected);
                button.setAttribute('aria-pressed', String(selected));
            });
            startButton.textContent = state.running ? 'Pause' : `Start ${state.mode}`;
            status.textContent = completionMessage || (state.running ? `${modeName} session in progress` : `${modeName} session ready`);
        }

        function stopTicker() {
            if (ticker) window.clearInterval(ticker);
            ticker = null;
        }

        function tick() {
            state.remainingMs = Math.max(0, state.endsAt - Date.now());
            if (state.remainingMs === 0) {
                const finishedMode = state.mode;
                state.mode = finishedMode === 'focus' ? 'break' : 'focus';
                state.remainingMs = durations[state.mode];
                state.running = false;
                state.endsAt = null;
                completionMessage = finishedMode === 'focus'
                    ? 'Focus complete — time for a break!'
                    : 'Break complete — ready to focus?';
                stopTicker();
                playChime(finishedMode);
            }
            if (!state.running) saveState();
            render();
        }

        function startTicker() {
            stopTicker();
            ticker = window.setInterval(tick, 250);
        }

        modeButtons.forEach(button => {
            button.addEventListener('click', () => {
                state.mode = button.dataset.pomodoroMode;
                state.remainingMs = durations[state.mode];
                state.running = false;
                state.endsAt = null;
                completionMessage = '';
                stopTicker();
                saveState();
                render();
            });
        });

        startButton.addEventListener('click', () => {
            completionMessage = '';
            enableAudio();
            if (state.running) {
                state.remainingMs = Math.max(0, state.endsAt - Date.now());
                state.running = false;
                state.endsAt = null;
                stopTicker();
            } else {
                if (state.remainingMs === 0) state.remainingMs = durations[state.mode];
                state.running = true;
                state.endsAt = Date.now() + state.remainingMs;
                startTicker();
            }
            saveState();
            render();
        });

        resetButton.addEventListener('click', () => {
            state.remainingMs = durations[state.mode];
            state.running = false;
            state.endsAt = null;
            completionMessage = '';
            stopTicker();
            saveState();
            render();
        });

        if (state.running) {
            startTicker();
            document.addEventListener('pointerdown', enableAudio, { once: true });
            document.addEventListener('keydown', enableAudio, { once: true });
        }
        saveState();
        render();
    }

    document.querySelectorAll('.reset-completion').forEach(button => {
        button.addEventListener('click', async (event) => {
            event.preventDefault();
            event.stopPropagation();
            button.disabled = true;

            try {
                const response = await fetch(`/api/progress/${button.dataset.videoId}/reset`, {
                    method: 'POST'
                });
                if (!response.ok) throw new Error(`Reset failed (${response.status})`);
                window.location.reload();
            } catch (error) {
                button.disabled = false;
                console.error('Failed to reset completion', error);
            }
        });
    });

    const player = document.getElementById('player');
    if (!player) return;

    let lastUpdate = 0;
    const updateInterval = 5000; // Update every 5 seconds

    if (typeof startSeconds !== 'undefined' && startSeconds > 0) {
        player.addEventListener('loadedmetadata', () => {
            player.currentTime = Math.min(startSeconds, player.duration || startSeconds);
        }, { once: true });
    }

    player.addEventListener('timeupdate', () => {
        const now = Date.now();
        if (now - lastUpdate > updateInterval) {
            saveProgress(player.currentTime, player.duration);
            lastUpdate = now;
        }
    });

    player.addEventListener('pause', () => {
        saveProgress(player.currentTime, player.duration);
    });

    player.addEventListener('ended', () => {
        saveProgress(player.duration, player.duration);
    });

    let keyboardSeekTarget = null;
    let keyboardSeekTimeout;
    document.addEventListener('keydown', (event) => {
        const activeElement = document.activeElement;
        const eventIncludesPlayer = event.composedPath().includes(player);
        const focusIsOnPageOrPlayer = activeElement === document.body ||
            activeElement === document.documentElement ||
            activeElement === player ||
            eventIncludesPlayer;
        if (!focusIsOnPageOrPlayer) return;
        if (event.ctrlKey || event.altKey || event.metaKey) return;

        const seekSeconds = 5;
        if (event.code === 'Space' || event.code === 'ArrowRight' || event.code === 'ArrowLeft') {
            event.preventDefault();
            event.stopImmediatePropagation();

            if (event.code === 'Space') {
                if (player.paused) player.play(); else player.pause();
                return;
            }

            const duration = Number.isFinite(player.duration) && player.duration > 0
                ? player.duration
                : Infinity;
            const currentTime = keyboardSeekTarget ?? player.currentTime;
            const seekDirection = event.code === 'ArrowRight' ? 1 : -1;
            keyboardSeekTarget = Math.max(
                0,
                Math.min(duration, currentTime + seekDirection * seekSeconds)
            );
            player.currentTime = keyboardSeekTarget;
            window.clearTimeout(keyboardSeekTimeout);
            keyboardSeekTimeout = window.setTimeout(() => {
                keyboardSeekTarget = null;
            }, 300);
        }
    }, true);

    function saveProgress(watched, duration) {
        if (!duration || isNaN(duration)) return;
        
        fetch('/api/progress', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                video_id: videoId,
                watched_seconds: watched,
                duration: duration
            })
        }).catch(err => console.error('Failed to save progress', err));
    }
});