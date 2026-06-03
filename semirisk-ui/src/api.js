const API_BASE = import.meta.env.VITE_API_BASE_URL || '';

export async function request(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' },
    credentials: 'include',
    ...options
  });
  const contentType = response.headers.get('content-type') || '';
  const body = contentType.includes('application/json') ? await response.json() : await response.text();
  if (!response.ok || body.success === false) {
    throw new Error(body.message || body || `HTTP ${response.status}`);
  }
  return body.data === undefined ? body : body.data;
}
