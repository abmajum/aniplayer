document.addEventListener('DOMContentLoaded', () => {
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