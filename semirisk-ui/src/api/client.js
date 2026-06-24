const API_BASE = import.meta.env.VITE_API_BASE_URL || '';
let csrfToken = '';

async function ensureCsrfToken() {
  if (csrfToken) return csrfToken;
  const response = await fetch(`${API_BASE}/api/auth/csrf`, { cache: 'no-store' });
  const body = await response.json();
  csrfToken = body.data?.token || body.token || '';
  return csrfToken;
}

export function authToken() {
  try {
    const session = JSON.parse(localStorage.getItem('semiriskUser') || 'null');
    return session?.token || '';
  } catch {
    return '';
  }
}

export async function request(path, options = {}) {
  const method = String(options.method || 'GET').toUpperCase();
  const unsafe = !['GET', 'HEAD', 'OPTIONS'].includes(method);
  const headers = options.body instanceof FormData
    ? { ...(options.headers || {}) }
    : { 'Content-Type': 'application/json', ...(options.headers || {}) };
  const token = authToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (unsafe) {
    headers['X-CSRF-Token'] = await ensureCsrfToken();
  }
  let response = await fetch(`${API_BASE}${path}`, { ...options, headers });
  let body = await readBody(response);
  if (unsafe && response.status === 403 && isCsrfFailure(body)) {
    csrfToken = '';
    headers['X-CSRF-Token'] = await ensureCsrfToken();
    response = await fetch(`${API_BASE}${path}`, { ...options, headers });
    body = await readBody(response);
  }
  if (response.status === 401) {
    localStorage.removeItem('semiriskUser');
    window.dispatchEvent(new CustomEvent('semirisk-auth-expired'));
  }
  if (!response.ok || body.success === false) {
    throw new Error(body.message || body || `HTTP ${response.status}`);
  }
  refreshLocalExpiry();
  return body.data === undefined ? body : body.data;
}

export async function downloadFile(path, filename) {
  const headers = {};
  const token = authToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  const response = await fetch(`${API_BASE}${path}`, { headers });
  if (response.status === 401) {
    localStorage.removeItem('semiriskUser');
    window.dispatchEvent(new CustomEvent('semirisk-auth-expired'));
  }
  if (!response.ok) {
    const body = await readBody(response);
    throw new Error(body?.message || body || `HTTP ${response.status}`);
  }
  const blob = await response.blob();
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(link.href);
}

async function readBody(response) {
  const contentType = response.headers.get('content-type') || '';
  return contentType.includes('application/json') ? await response.json() : await response.text();
}

function isCsrfFailure(body) {
  const message = typeof body === 'string' ? body : body?.message;
  return String(message || '').includes('CSRF Token');
}

function refreshLocalExpiry() {
  const token = authToken();
  if (!token) return;
  try {
    const session = JSON.parse(localStorage.getItem('semiriskUser') || 'null');
    if (!session?.token) return;
    // 使用服务器返回的 expiresAt，而不是本地推算
    if (session.expiresAt) {
      localStorage.setItem('semiriskUser', JSON.stringify(session));
    } else {
      // 兜底：30 分钟后过期
      session.expiresAt = new Date(Date.now() + 30 * 60 * 1000).toISOString();
      localStorage.setItem('semiriskUser', JSON.stringify(session));
    }
  } catch {
    localStorage.removeItem('semiriskUser');
  }
}
