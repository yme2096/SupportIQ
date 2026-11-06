export const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
let accessToken = null;
let refreshInFlight = null;

export function setAccessToken(token) { accessToken = token || null; }
export function getAccessToken() { return accessToken; }

export async function api(path, options = {}) {
  const { publicRequest = false, ...fetchOptions } = options;
  const isAuthPublic = ['/api/auth/login', '/api/auth/register-company', '/api/auth/refresh', '/api/auth/logout', '/api/auth/forgot-password', '/api/auth/reset-password'].includes(path)
    || path.startsWith('/api/public/') || path.startsWith('/api/invitations/validate') || path === '/api/invitations/accept';
  const headers = new Headers(fetchOptions.headers || {});
  if (accessToken && !publicRequest && !isAuthPublic) headers.set('Authorization', `Bearer ${accessToken}`);
  if ((path.includes('/chat/') && path.endsWith('/messages')) || path.endsWith('/api/v1/chat/message')) {
    let conversationId = path.match(/\/chat\/([^/]+)\/messages/)?.[1];
    if (!conversationId && options.body) { try { conversationId = JSON.parse(options.body).conversationId; } catch { /* multipart or non-JSON body */ } }
    const chatToken = conversationId ? sessionStorage.getItem(`supportiq-chat-token:${conversationId}`) : null;
    if (chatToken) headers.set('X-Conversation-Token', chatToken);
  }
  let response = await fetch(API_BASE + path, { ...fetchOptions, headers, credentials: 'include' });
  if (response.status === 401 && accessToken && !publicRequest && !isAuthPublic && !options._retried) {
    try {
      if (!refreshInFlight) refreshInFlight = fetch(API_BASE + '/api/auth/refresh', { method: 'POST', credentials: 'include' }).then(async r => {
        if (!r.ok) throw new Error('Session expired. Sign in again.');
        return r.json();
      }).finally(() => { refreshInFlight = null; });
      const refreshed = await refreshInFlight;
      setAccessToken(refreshed.accessToken);
      headers.set('Authorization', `Bearer ${refreshed.accessToken}`);
      response = await fetch(API_BASE + path, { ...fetchOptions, headers, credentials: 'include' });
    } catch {
      setAccessToken(null);
      if (typeof window !== 'undefined') window.dispatchEvent(new Event('supportiq:session-expired'));
    }
  }
  const data = response.status === 204 ? {} : await response.json().catch(() => ({}));
  if (!response.ok) throw Object.assign(new Error(data.error || 'Request failed.'), { status: response.status });
  return data;
}
