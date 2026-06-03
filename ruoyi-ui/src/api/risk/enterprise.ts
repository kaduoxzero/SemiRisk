import request from '@/utils/request';

// 1. 获取指定企业全景画像数据（含五维雷达图、生态链拓扑数据）
export function getEnterpriseProfile(keyword: string) {
  return request({
    url: '/risk/enterprise/profile',
    method: 'get',
    params: { keyword }
  });
}

// 2. 向量知识库混合检索 (RAG 检索)
export function searchKnowledgeBase(queryStr: string) {
  return request({
    url: '/risk/enterprise/kb/search',
    method: 'get',
    params: { query: queryStr }
  });
}

// 3. 运行报表上传并触发清洗（使用 FormData 格式）
export function uploadRunningReport(file: File) {
  const formData = new FormData();
  formData.append('file', file);
  return request({
    url: '/risk/enterprise/report/upload',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' }
  });
}
