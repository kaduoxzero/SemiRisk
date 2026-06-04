const API_BASE = import.meta.env.VITE_API_BASE_URL || '';
let csrfToken = '';

async function ensureCsrfToken() {
  if (csrfToken) return csrfToken;
  const response = await fetch(`${API_BASE}/api/auth/csrf`, { cache: 'no-store' });
  const body = await response.json();
  csrfToken = body.data?.token || body.token || '';
  return csrfToken;
}

function authToken() {
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
    session.expiresAt = new Date(Date.now() + 30 * 60 * 1000).toISOString();
    localStorage.setItem('semiriskUser', JSON.stringify(session));
  } catch {
    localStorage.removeItem('semiriskUser');
  }
}
