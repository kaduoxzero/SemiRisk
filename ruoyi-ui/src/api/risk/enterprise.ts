import request from '@/utils/request';

export function listEnterprises(query: any) {
  return request({
    url: '/risk/enterprise/list',
    method: 'get',
    params: query
  });
}

export function getEnterpriseProfile(keyword?: string) {
  return request({
    url: '/risk/enterprise/profile',
    method: 'get',
    params: { keyword }
  });
}

export function addEnterprise(data: any) {
  return request({
    url: '/risk/enterprise',
    method: 'post',
    data
  });
}

export function updateEnterprise(data: any) {
  return request({
    url: '/risk/enterprise',
    method: 'put',
    data
  });
}

export function searchKnowledgeBase(queryStr: string) {
  return request({
    url: '/risk/enterprise/kb/search',
    method: 'get',
    params: { query: queryStr }
  });
}

export function listKnowledge(query: any) {
  return request({
    url: '/risk/knowledge/list',
    method: 'get',
    params: query
  });
}

export function addKnowledge(data: any) {
  return request({
    url: '/risk/knowledge',
    method: 'post',
    data
  });
}

export function uploadRunningReport(file: File) {
  const formData = new FormData();
  formData.append('file', file);
  return request({
    url: '/risk/enterprise/report/upload',
    method: 'post',
    data: formData
  });
}

export function listReports(query: any) {
  return request({
    url: '/risk/report/list',
    method: 'get',
    params: query
  });
}

export function getReport(reportId: string | number) {
  return request({
    url: `/risk/report/${reportId}`,
    method: 'get'
  });
}

export function listDataSources(query: any) {
  return request({
    url: '/risk/source/list',
    method: 'get',
    params: query
  });
}

export function addDataSource(data: any) {
  return request({
    url: '/risk/source',
    method: 'post',
    data
  });
}
