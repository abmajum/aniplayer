document.addEventListener('DOMContentLoaded', () => {
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
        player.currentTime = startSeconds;
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

    document.addEventListener('keydown', (event) => {
        // Ignore typing in form fields or when modifier keys are held
        const activeElement = document.activeElement;
        const isFormField = activeElement && (
            activeElement.tagName === 'INPUT' ||
            activeElement.tagName === 'TEXTAREA' ||
            activeElement.tagName === 'SELECT' ||
            activeElement.isContentEditable
        );
        if (isFormField) return;

        if (event.ctrlKey || event.altKey || event.metaKey) return;

        const seekSeconds = 5;
        if (event.code === 'Space' || event.code === 'ArrowRight' || event.code === 'ArrowLeft') {
            // Prevent default actions (scrolling / button activation) and stop other handlers
            event.preventDefault();
            if (event.stopImmediatePropagation) event.stopImmediatePropagation();

            if (event.code === 'Space') {
                if (player.paused) player.play(); else player.pause();
                return;
            }

            if (event.code === 'ArrowRight') {
                player.currentTime = Math.min(player.duration || Infinity, player.currentTime + seekSeconds);
            } else if (event.code === 'ArrowLeft') {
                player.currentTime = Math.max(0, player.currentTime - seekSeconds);
            }
        }
    });

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