export function badgeClass(level) {
  return level === '高危' ? 'high' : level === '中危' ? 'mid' : 'low';
}

export function formatTime(time) {
  return new Date(time).toLocaleString('zh-CN', { hour12: false });
}
