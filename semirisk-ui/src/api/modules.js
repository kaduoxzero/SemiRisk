import { request } from './client';

export const authApi = {
  login: payload => request('/api/auth/login', { method: 'POST', body: JSON.stringify(payload) }),
  register: payload => request('/api/auth/register', { method: 'POST', body: JSON.stringify(payload) }),
  sendVerificationCode: email => request('/api/auth/send-verification-code', {
    method: 'POST',
    body: JSON.stringify({ email })
  }),
  logout: () => request('/api/auth/logout', { method: 'POST' }),
  me: () => request('/api/auth/me'),
  requestPasswordReset: email => request('/api/auth/password-reset/request', {
    method: 'POST',
    body: JSON.stringify({ email })
  }),
  confirmPasswordReset: payload => request('/api/auth/password-reset/confirm', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
};

export const dashboardApi = {
  overview: () => request('/api/dashboard/overview'),
  recalculateRisk: () => request('/api/risk-score/recalculate', { method: 'POST' })
};

export const uploadApi = {
  list: () => request('/api/data/uploads'),
  parse: id => request(`/api/data/uploads/${id}/parse`, { method: 'POST' }),
  upload: form => request('/api/data/uploads', { method: 'POST', body: form }),
  templateUrl: '/api/data/templates/supplier',
  logsUrl: '/api/data/uploads/logs'
};

export const riskApi = {
  analysis: windowName => request(`/api/risk/analysis?window=${windowName}`),
  detail: id => request(`/api/risk/events/${id}`),
  assign: (id, owner) => request(`/api/risk/events/${id}/assign`, {
    method: 'POST',
    body: JSON.stringify({ owner })
  })
};

export const reportApi = {
  templates: () => request('/api/reports/templates'),
  createJob: payload => request('/api/reports/jobs', { method: 'POST', body: JSON.stringify(payload) }),
  job: id => request(`/api/reports/jobs/${id}`)
};

export const alertApi = {
  list: params => request(`/api/alerts?${params}`),
  ignore: id => request(`/api/alerts/${id}/ignore`, { method: 'PUT' }),
  batchProcess: ids => request('/api/alerts/batch-process', {
    method: 'POST',
    body: JSON.stringify({ ids })
  })
};

export const gisApi = {
  map: layers => request(`/api/gis/map?layers=${layers.join(',')}`)
};

export const enterpriseApi = {
  list: () => request('/api/enterprises'),
  profile: keyword => request(`/api/enterprises/profile?keyword=${encodeURIComponent(keyword)}`)
};

export const knowledgeApi = {
  search: query => request(`/api/knowledge/search?query=${encodeURIComponent(query)}`),
  ask: question => request('/api/knowledge/ask', {
    method: 'POST',
    body: JSON.stringify({ question })
  })
};

export const systemApi = {
  overview: () => request('/api/system/overview'),
  getAiConfig: () => request('/api/system/models/config'),
  saveAiConfig: payload => request('/api/system/models/config', {
    method: 'POST',
    body: JSON.stringify(payload)
  }),
  pingModel: payload => request('/api/system/models/ping', {
    method: 'POST',
    body: JSON.stringify(payload)
  }),
  reconnect: name => request(`/api/system/datasources/${encodeURIComponent(name)}/reconnect`, { method: 'POST' }),
  triggerAgent: name => request(`/api/system/agents/${encodeURIComponent(name)}/trigger`, { method: 'POST' })
};

export const aiApi = {
  latestReport: () => request('/api/ai/reports/latest'),
  refreshReport: () => request('/api/ai/reports/refresh', { method: 'POST' })
};
