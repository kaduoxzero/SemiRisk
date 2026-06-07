import request from '@/utils/request';

export interface RiskEventQuery {
  pageNum?: number;
  pageSize?: number;
  eventTitle?: string;
  enterpriseName?: string;
  riskLevel?: string;
  status?: string;
  category?: string;
}

export function listRiskEvents(query: RiskEventQuery) {
  return request({
    url: '/risk/event/list',
    method: 'get',
    params: query
  });
}

export function getRiskEvent(eventId: string | number) {
  return request({
    url: `/risk/event/${eventId}`,
    method: 'get'
  });
}

export function addRiskEvent(data: any) {
  return request({
    url: '/risk/event',
    method: 'post',
    data
  });
}

export function updateRiskEvent(data: any) {
  return request({
    url: '/risk/event',
    method: 'put',
    data
  });
}

export function getRiskKpis() {
  return request({
    url: '/risk/event/kpis',
    method: 'get'
  });
}

export function getRiskTrend() {
  return request({
    url: '/risk/event/trend',
    method: 'get'
  });
}

export function handleRiskEvent(id: string | number, status: string, disposalSuggestion?: string) {
  return request({
    url: `/risk/event/handle/${id}`,
    method: 'put',
    data: { status, disposalSuggestion }
  });
}

export function generateAiReport(data: { templateId?: string | number; templateType?: string; dateRange: string; format: string }) {
  return request({
    url: '/risk/event/report/generate',
    method: 'post',
    data
  });
}

export function getGisNodes() {
  return request({
    url: '/risk/event/gis/nodes',
    method: 'get'
  });
}
