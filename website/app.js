const statusLabel = document.querySelector('#server-status');
const statusDot = document.querySelector('#status-dot');

async function updateServerStatus() {
    try {
        const onGitHubPages = location.hostname.endsWith('github.io');
        const healthUrl = onGitHubPages
            ? 'https://somechatapp.ddns.net/health'
            : '/health';
        const response = await fetch(healthUrl, { cache: 'no-store', mode: onGitHubPages ? 'no-cors' : 'cors' });
        if (!response.ok && response.type !== 'opaque') throw new Error('Server unavailable');
        statusLabel.textContent = 'Server online';
        statusDot.className = 'status-dot online';
    } catch {
        statusLabel.textContent = 'Server offline';
        statusDot.className = 'status-dot offline';
    }
}

updateServerStatus();
setInterval(updateServerStatus, 30000);
