import { Client } from '@stomp/stompjs';
import { API_BASE, api, getAccessToken, setAccessToken } from './api.js';

export const realtimeUrl = `${API_BASE.replace(/^http/, 'ws').replace(/\/$/, '')}/ws`;

export function createStaffRealtime(onConnect, onEvent, onState = () => {}) {
  const token = getAccessToken();
  const client = new Client({
    brokerURL: realtimeUrl,
    connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
    beforeConnect: async () => {
      try { const renewed = await api('/api/auth/refresh', { method: 'POST' }); setAccessToken(renewed.accessToken); client.connectHeaders = { Authorization: `Bearer ${renewed.accessToken}` }; }
      catch { const latest = getAccessToken(); client.connectHeaders = latest ? { Authorization: `Bearer ${latest}` } : {}; }
    },
    reconnectDelay: 2500,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: frame => { onState('connected'); onConnect(client, frame); },
    onWebSocketClose: () => onState('reconnecting'),
    onWebSocketError: () => onState('offline'),
    onStompError: frame => onState(frame.headers?.message || 'reconnecting'),
  });
  client.activate();
  return client;
}

export function createCustomerRealtime(publicCompanyId, conversationId, token, onConnect, onState = () => {}) {
  const client = new Client({
    brokerURL: realtimeUrl,
    connectHeaders: {
      'X-Company-Public-Id': publicCompanyId,
      'X-Conversation-Id': conversationId,
      'X-Conversation-Token': token,
    },
    reconnectDelay: 2500,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: frame => { onState('connected'); onConnect(client, frame); },
    onWebSocketClose: () => onState('reconnecting'),
    onWebSocketError: () => onState('offline'),
    onStompError: () => onState('reconnecting'),
  });
  client.activate();
  return client;
}

export function clientMessageId() {
  return globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
