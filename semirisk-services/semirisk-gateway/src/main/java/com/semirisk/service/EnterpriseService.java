package com.semirisk.service;

import com.semirisk.service.SemiRiskStore.CrawlerSignal;
import com.semirisk.repository.PreparedRiskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class EnterpriseService {

    private final PreparedRiskRepository repository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();

    private static final String KNOWLEDGE_PUBLIC = SemiRiskStore.KNOWLEDGE_PUBLIC;

    private static final Map<String, Map<String, String>> PUBLIC_COMPANY_DB = new HashMap<>();
    static {
        PUBLIC_COMPANY_DB.put("tsmc", Map.of("成立时间","1987年","总部所在地","台湾新竹科学园区","行业分类","半导体代工","企业类型","上市公司（NYSE: TSM / TWSE: 2330）","营收（公开披露）","约 NT$2.16兆元（2023年）","员工人数","约 73,000人（2023年）","公司简介","全球最大的纯晶圆代工厂，主要为苹果、英伟达、AMD等制造芯片，制程技术覆盖2nm至成熟节点。"));
        PUBLIC_COMPANY_DB.put("台积电", PUBLIC_COMPANY_DB.get("tsmc"));
        PUBLIC_COMPANY_DB.put("samsung", Map.of("成立时间","1969年（半导体业务）","总部所在地","韩国京畿道水原市","行业分类","半导体/消费电子","企业类型","上市公司（KRX: 005930）","营收（公开披露）","约 KRW 258兆韩元（2023年）","员工人数","约 270,000人","公司简介","全球最大DRAM/NAND Flash制造商，同时提供代工服务，IDM模式运营。"));
        PUBLIC_COMPANY_DB.put("三星", PUBLIC_COMPANY_DB.get("samsung"));
        PUBLIC_COMPANY_DB.put("asml", Map.of("成立时间","1984年","总部所在地","荷兰埃因霍温","行业分类","半导体设备","企业类型","上市公司（NASDAQ: ASML）","营收（公开披露）","约 €27.6亿（2023年）","员工人数","约 42,000人","公司简介","全球唯一EUV光刻机制造商，DUV/EUV设备是先进制程不可或缺的核心设备。"));
        PUBLIC_COMPANY_DB.put("nvidia", Map.of("成立时间","1993年","总部所在地","美国加利福尼亚州圣克拉拉","行业分类","半导体/GPU/AI","企业类型","上市公司（NASDAQ: NVDA）","营收（公开披露）","约 $609亿美元（FY2024）","员工人数","约 36,000人","公司简介","全球领先的GPU和AI加速器制造商，H100/H200系列是当前AI训练的主流算力平台。"));
        PUBLIC_COMPANY_DB.put("英伟达", PUBLIC_COMPANY_DB.get("nvidia"));
        PUBLIC_COMPANY_DB.put("amd", Map.of("成立时间","1969年","总部所在地","美国加利福尼亚州圣克拉拉","行业分类","半导体/CPU/GPU","企业类型","上市公司（NASDAQ: AMD）","营收（公开披露）","约 $227亿美元（2023年）","员工人数","约 26,000人","公司简介","x86 CPU（EPYC服务器处理器）和Radeon GPU制造商，近年AI加速器MI系列快速增长。"));
        PUBLIC_COMPANY_DB.put("intel", Map.of("成立时间","1968年","总部所在地","美国加利福尼亚州圣克拉拉","行业分类","半导体/CPU","企业类型","上市公司（NASDAQ: INTC）","营收（公开披露）","约 $542亿美元（2023年）","员工人数","约 124,800人","公司简介","全球最大的x86 CPU制造商之一，IDM模式运营，正在亚利桑那建设IFS代工厂。"));
        PUBLIC_COMPANY_DB.put("英特尔", PUBLIC_COMPANY_DB.get("intel"));
        PUBLIC_COMPANY_DB.put("qualcomm", Map.of("成立时间","1985年","总部所在地","美国加利福尼亚州圣地亚哥","行业分类","半导体/无线通信","企业类型","上市公司（NASDAQ: QCOM）","营收（公开披露）","约 $358亿美元（FY2023）","员工人数","约 51,000人","公司简介","全球领先的移动处理器和基带芯片设计公司，Snapdragon系列广泛用于智能手机。"));
        PUBLIC_COMPANY_DB.put("高通", PUBLIC_COMPANY_DB.get("qualcomm"));
        PUBLIC_COMPANY_DB.put("sk hynix", Map.of("成立时间","1983年","总部所在地","韩国京畿道利川市","行业分类","半导体/存储","企业类型","上市公司（KRX: 000660）","营收（公开披露）","约 KRW 32.8兆韩元（2023年）","员工人数","约 37,000人","公司简介","全球第二大DRAM制造商，HBM高带宽存储器是目前AI训练芯片的核心配套组件。"));
        PUBLIC_COMPANY_DB.put("海力士", PUBLIC_COMPANY_DB.get("sk hynix"));
        PUBLIC_COMPANY_DB.put("micron", Map.of("成立时间","1978年","总部所在地","美国爱达荷州博伊西","行业分类","半导体/存储","企业类型","上市公司（NASDAQ: MU）","营收（公开披露）","约 $154亿美元（FY2023）","员工人数","约 48,000人","公司简介","全球主要DRAM和NAND Flash制造商，是美国本土唯一的存储芯片大厂。"));
        PUBLIC_COMPANY_DB.put("applied materials", Map.of("成立时间","1967年","总部所在地","美国加利福尼亚州圣克拉拉","行业分类","半导体设备","企业类型","上市公司（NASDAQ: AMAT）","营收（公开披露）","约 $266亿美元（FY2023）","员工人数","约 34,000人","公司简介","全球最大的半导体设备公司，覆盖CVD、PVD、CMP、离子注入等核心制程设备。"));
        PUBLIC_COMPANY_DB.put("broadcom", Map.of("成立时间","1991年（Avago前身）","总部所在地","美国加利福尼亚州圣何塞","行业分类","半导体/网络/AI","企业类型","上市公司（NASDAQ: AVGO）","营收（公开披露）","约 $359亿美元（FY2023）","员工人数","约 20,000人","公司简介","全球领先的网络芯片和定制AI ASIC设计公司，为谷歌等超大规模数据中心提供TPU等ASIC。"));
        PUBLIC_COMPANY_DB.put("博通", PUBLIC_COMPANY_DB.get("broadcom"));
        PUBLIC_COMPANY_DB.put("arm", Map.of("成立时间","1990年","总部所在地","英国剑桥","行业分类","半导体IP/指令集架构","企业类型","上市公司（NASDAQ: ARM）","营收（公开披露）","约 $27.3亿美元（FY2024）","员工人数","约 6,500人","公司简介","全球主导的CPU IP授权公司，超过99%的智能手机和大量服务器/AI芯片使用ARM架构。"));
        PUBLIC_COMPANY_DB.put("安谋", PUBLIC_COMPANY_DB.get("arm"));
        PUBLIC_COMPANY_DB.put("mediatek", Map.of("成立时间","1997年","总部所在地","台湾新竹","行业分类","半导体/SoC","企业类型","上市公司（TWSE: 2454）","营收（公开披露）","约 NT$4,414亿元（2023年）","员工人数","约 20,000人","公司简介","全球第三大无晶圆半导体公司，Dimensity系列SoC广泛应用于中高端安卓手机和IoT设备。"));
        PUBLIC_COMPANY_DB.put("联发科", PUBLIC_COMPANY_DB.get("mediatek"));
    }

    public EnterpriseService(PreparedRiskRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> buildBusinessWithWiki(String creditCode, boolean noSignal, String companyName) {
        Map<String, Object> business = new LinkedHashMap<>();
        Map<String, String> known = null;
        if (companyName != null && !companyName.isBlank()) {
            String lc = companyName.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Map<String, String>> entry : PUBLIC_COMPANY_DB.entrySet()) {
                if (lc.contains(entry.getKey()) || entry.getKey().contains(lc)) {
                    known = entry.getValue();
                    break;
                }
            }
        }
        if (known != null) {
            business.putAll(known);
            business.put("数据来源", "公司年报/官网/公开披露（已内置）");
        } else {
            Map<String, Object> wiki = companyName != null && !companyName.isBlank()
                    && !companyName.equals("请输入企业名称后搜索")
                    ? fetchWikipediaBusinessInfo(companyName) : Map.of();
            business.put("成立时间", wiki.getOrDefault("成立时间", "待接入权威源"));
            business.put("总部所在地", wiki.getOrDefault("总部所在地", "待接入权威源"));
            business.put("行业分类", wiki.getOrDefault("行业分类", "待接入权威源"));
            business.put("企业类型", wiki.getOrDefault("企业类型", "待接入权威源"));
            business.put("营收（公开披露）", wiki.getOrDefault("营收（公开披露）", "待接入权威源"));
            business.put("员工人数", wiki.getOrDefault("员工人数（公开披露）", "待接入权威源"));
            if (wiki.containsKey("description")) business.put("公司简介", wiki.get("description"));
            if (wiki.containsKey("wikiTitle")) business.put("维基百科词条", wiki.get("wikiTitle") + "（公开百科）");
        }
        business.put("统一信用代码", creditCode);
        business.put("法人代表", "待接入权威源（工商局/企查查/天眼查）");
        business.put("注册资本", "待接入权威源（工商局/企查查/天眼查）");
        business.put("司法/失信数据", "待接入权威源（法院/最高人民法院失信被执行人名单）");
        business.put("采集状态", noSignal ? "未命中公开源事件" : "已命中公开源事件");
        return business;
    }

    public List<Map<String, String>> internetSearches(String keyword) {
        String encoded = URLEncoder.encode(keyword == null ? "" : keyword, StandardCharsets.UTF_8);
        return List.of(
                Map.of("name", "企查查公开搜索", "url", "https://www.qcc.com/web/search?key=" + encoded),
                Map.of("name", "天眼查公开搜索", "url", "https://www.tianyancha.com/cloud-other-information/companyInfo.html?keyword=" + encoded),
                Map.of("name", "Bing 新闻", "url", "https://www.bing.com/news/search?q=" + encoded),
                Map.of("name", "路透社检索", "url", "https://www.reuters.com/search/news?blob=" + encoded),
                Map.of("name", "SEC EDGAR 公示", "url", "https://efts.sec.gov/LATEST/search-index?q=%22" + encoded + "%22&dateRange=custom&startdt=2023-01-01"),
                Map.of("name", "彭博行业资讯", "url", "https://www.bloomberg.com/search?query=" + encoded)
        );
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> internetSearchResults(String keyword, String apiKey) {
        if (keyword == null || keyword.isBlank()) return List.of();
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("https://api.bing.microsoft.com/v7.0/news/search?q=" + encoded + "&count=5"))
                    .timeout(java.time.Duration.ofSeconds(8))
                    .header("Ocp-Apim-Subscription-Key", apiKey)
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return fallbackWebSearch(keyword);
            }
            Map<String, Object> body = (Map<String, Object>) parseJson(resp.body());
            Map<String, Object> data = (Map<String, Object>) body.getOrDefault("data", body);
            if (data == null) data = body;
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("news");
            if (items == null) items = List.of();
            Instant now = Instant.now();
            for (Map<String, Object> item : items) {
                String name = String.valueOf(item.getOrDefault("name", ""));
                String url = String.valueOf(item.getOrDefault("url", ""));
                String desc = String.valueOf(item.getOrDefault("description", ""));
                String date = String.valueOf(item.getOrDefault("datePublished", ""));
                results.add(Map.of(
                        "title", truncate(name, 200),
                        "source", String.valueOf(item.getOrDefault("provider", Map.of("name", "Bing").toString())),
                        "url", url,
                        "snippet", truncate(desc, 300),
                        "date", date
                ));
                try {
                    String docId = "WEB-" + UUID.nameUUIDFromBytes((keyword + name).getBytes(StandardCharsets.UTF_8)).toString().substring(0, 8);
                    String content = "标题：" + name + "\n来源：" + item.getOrDefault("provider", Map.of("name", "Bing").toString()) + "\n摘要：" + desc + "\n链接：" + url + "\n发布时间：" + date;
                    repository.upsertKnowledgeDoc(docId, KNOWLEDGE_PUBLIC, truncate(name, 1000), content,
                            "Bing News 搜索", url, "企业信息", 0, null, now);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
            return fallbackWebSearch(keyword);
        }
        return results.isEmpty() ? fallbackWebSearch(keyword) : results;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fallbackWebSearch(String keyword) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("https://html.duckduckgo.com/html/?q=" + encoded))
                    .timeout(java.time.Duration.ofSeconds(6))
                    .header("User-Agent", "SemiRisk/1.0 (supply chain risk platform; research use)")
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return results;
            String body = resp.body();
            java.util.regex.Matcher titleMatcher = java.util.regex.Pattern.compile("<a rel=\"nofollow\" class=\"result__a\" href=\"([^\"]+)\"[^>]*>([^<]+)</a>").matcher(body);
            java.util.regex.Matcher snippetMatcher = java.util.regex.Pattern.compile("<a class=\"result__snippet\"[^>]*>([^<]+)</a>").matcher(body);
            while (titleMatcher.find()) {
                String url = titleMatcher.group(1);
                String title = titleMatcher.group(2).replaceAll("<[^>]+>", "").trim();
                String snippet = "";
                if (snippetMatcher.find()) {
                    snippet = snippetMatcher.group(1).replaceAll("<[^>]+>", "").trim();
                }
                results.add(Map.of(
                        "title", truncate(title, 200),
                        "source", "DuckDuckGo 搜索",
                        "url", url,
                        "snippet", truncate(snippet, 300),
                        "date", Instant.now().toString()
                ));
                if (results.size() >= 5) break;
            }
            Instant now = Instant.now();
            for (Map<String, Object> r : results) {
                try {
                    String docId = "WEB-" + UUID.nameUUIDFromBytes((keyword + r.get("title")).getBytes(StandardCharsets.UTF_8)).toString().substring(0, 8);
                    repository.upsertKnowledgeDoc(docId, KNOWLEDGE_PUBLIC,
                            String.valueOf(r.get("title")),
                            "搜索词：" + keyword + "\n来源：" + r.get("source") + "\n摘要：" + r.get("snippet") + "\n链接：" + r.get("url"),
                            "网络搜索", String.valueOf(r.get("url")), "企业信息", 0, null, now);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return results;
    }

    public Optional<Map<String, Object>> findEnterpriseRecord(String keyword) {
        try {
            return repository.findEnterpriseRecordByKeyword(keyword).stream().findFirst();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public Map<String, Object> repositoryFirstEnterprise() {
        try {
            return repository.findEnterpriseRecords(1).stream().findFirst().orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    public List<Map<String, Object>> enterpriseCatalog() {
        try {
            return repository.findEnterpriseRecords(50).stream()
                    .map(record -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", record.get("name"));
                        item.put("creditCode", stringValue(record.get("creditCode")).isBlank() ? "待接入权威源" : record.get("creditCode"));
                        item.put("industry", record.get("industry"));
                        item.put("riskScore", record.get("riskScore"));
                        item.put("creditLevel", record.get("creditLevel"));
                        item.put("location", record.get("location"));
                        return item;
                    })
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public List<Map<String, Object>> enterpriseRecordsForReport(int limit) {
        try {
            return repository.findEnterpriseRecords(limit);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public void persistSearchedEnterprise(String name, String industry, int score, List<CrawlerSignal> related, String riskLevel) {
        try {
            String id = "ENT-" + UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString().substring(0, 8);
            String eventsJson = writeJson(related.stream().map(s -> s.fetchedAt() + " " + s.title()).toList());
            String signalsJson = writeJson(related.stream().map(this::enterpriseSignal).toList());
            repository.upsertEnterpriseRecord(id, truncate(name, 250), "", truncate(industry, 120), "待核验",
                    score, riskLevel, "公开源事件聚合（用户搜索）", "待接入权威源", eventsJson, signalsJson, Instant.now());
        } catch (Exception ignored) {}
    }

    public Map<String, Object> enterpriseSignal(CrawlerSignal signal) {
        return Map.of(
                "id", signal.id(),
                "title", signal.title(),
                "source", signal.source(),
                "sourceUrl", signal.sourceUrl(),
                "riskScore", signal.riskScore(),
                "dimension", signal.dimension(),
                "fetchedAt", signal.fetchedAt().toString()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchWikipediaBusinessInfo(String companyName) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String encoded = URLEncoder.encode(companyName, StandardCharsets.UTF_8);
            HttpRequest searchReq = HttpRequest.newBuilder(
                    URI.create("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch="
                            + encoded + "&format=json&srlimit=1"))
                    .timeout(java.time.Duration.ofSeconds(6))
                    .header("User-Agent", "SemiRisk/1.0 (supply chain risk platform; research use)")
                    .GET().build();
            HttpResponse<String> searchResp = httpClient.send(searchReq, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> searchBody = (Map<String, Object>) parseJson(searchResp.body());
            List<Map<String, Object>> hits = (List<Map<String, Object>>) ((Map<?, ?>) searchBody.get("query")).get("search");
            if (hits == null || hits.isEmpty()) return result;
            String pageTitle = String.valueOf(hits.get(0).get("title"));

            String titleEncoded = URLEncoder.encode(pageTitle, StandardCharsets.UTF_8);
            HttpRequest infoReq = HttpRequest.newBuilder(
                    URI.create("https://en.wikipedia.org/w/api.php?action=query&titles=" + titleEncoded
                            + "&prop=extracts|categories&exintro=true&explaintext=true&format=json&cllimit=10"))
                    .timeout(java.time.Duration.ofSeconds(8))
                    .header("User-Agent", "SemiRisk/1.0 (supply chain risk platform; research use)")
                    .GET().build();
            HttpResponse<String> infoResp = httpClient.send(infoReq, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> infoBody = (Map<String, Object>) parseJson(infoResp.body());
            Map<?, ?> pages = (Map<?, ?>) ((Map<?, ?>) infoBody.get("query")).get("pages");
            if (pages == null || pages.isEmpty()) return result;
            Map<String, Object> page = (Map<String, Object>) pages.values().iterator().next();
            String extract = String.valueOf(page.getOrDefault("extract", ""));

            result.put("wikiTitle", pageTitle);
            result.put("wikiSource", "维基百科公开百科词条（英文）");
            extractWikiField(result, extract, "Founded", "成立时间");
            extractWikiField(result, extract, "Headquarters", "总部所在地");
            extractWikiField(result, extract, "Industry", "行业分类");
            extractWikiField(result, extract, "Type", "企业类型");
            extractWikiField(result, extract, "Revenue", "营收（公开披露）");
            extractWikiField(result, extract, "Employees", "员工人数（公开披露）");
            String[] paras = extract.split("\n\n");
            if (paras.length > 0 && !paras[0].isBlank()) {
                result.put("description", truncate(paras[0].replaceAll("\\s+", " ").trim(), 200));
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void extractWikiField(Map<String, Object> result, String text, String enKey, String zhKey) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)" + java.util.regex.Pattern.quote(enKey) + "[:\\s]+([^\\n]+)").matcher(text);
        if (m.find()) {
            result.put(zhKey, truncate(m.group(1).trim(), 80));
        }
    }

    private Object parseJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
