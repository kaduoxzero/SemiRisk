import request from '@/utils/request';

// 1. 获取风险事件列表（支持分页及条件过滤，对应预警中心）
export function listRiskEvents(query: any) {
  return request({
    url: '/risk/event/list',
    method: 'get',
    params: query
  });
}

// 2. 获取首页 KPI 聚合数据（总数、新增、闭环率等）
export function getRiskKpis() {
  return request({
    url: '/risk/event/kpis',
    method: 'get'
  });
}

// 3. 获取近 30 天趋势图数据
export function getRiskTrend() {
  return request({
    url: '/risk/event/trend',
    method: 'get'
  });
}

// 4. 忽略/处置特定告警事件
export function handleRiskEvent(id: string | number, status: string) {
  return request({
    url: `/risk/event/handle/${id}`,
    method: 'put',
    data: { status }
  });
}

// 5. 触发 AI 决策报告生成任务
export function generateAiReport(data: { templateId: number; dateRange: string; format: string }) {
  return request({
    url: '/risk/event/report/generate',
    method: 'post',
    data: data
  });
}

// 6. 获取 GIS 星图节点数据
export function getGisNodes() {
  return request({
    url: '/risk/event/gis/nodes',
    method: 'get'
  });
}
