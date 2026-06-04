import { request } from './client';

export const authApi = {
  login: payload => request('/api/auth/login', { method: 'POST', body: JSON.stringify(payload) }),
  register: payload => request('/api/auth/register', { method: 'POST', body: JSON.stringify(payload) }),
  logout: () => request('/api/auth/logout', { method: 'POST' }),
  me: () => request('/api/auth/me'),
  requestPasswordReset: email => request('/api/auth/password-reset/request', {
    method: 'POST',
    body: JSON.stringify({ email })
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
  profile: keyword => request(`/api/enterprises/profile?keyword=${encodeURIComponent(keyword)}`)
};

export const knowledgeApi = {
  search: query => request(`/api/knowledge/search?query=${encodeURIComponent(query)}`)
};

export const systemApi = {
  overview: () => request('/api/system/overview'),
  saveAiConfig: payload => request('/api/system/models/config', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
};
