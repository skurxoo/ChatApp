const statusLabel = document.querySelector('#server-status');
const statusDot = document.querySelector('#status-dot');

async function updateServerStatus() {
    try {
        const response = await fetch('/files', { cache: 'no-store' });
        if (!response.ok) throw new Error('Server unavailable');
        statusLabel.textContent = 'Server online';
        statusDot.className = 'status-dot online';
    } catch {
        statusLabel.textContent = 'Server offline';
        statusDot.className = 'status-dot offline';
    }
}

updateServerStatus();
setInterval(updateServerStatus, 30000);
