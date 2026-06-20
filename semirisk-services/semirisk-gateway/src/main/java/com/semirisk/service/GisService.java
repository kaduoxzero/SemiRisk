package com.semirisk.service;

import com.semirisk.model.CrawlerSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class GisService {

    private static final Logger log = LoggerFactory.getLogger(GisService.class);

    private static final Object[][] PLACE_GEO = {
            {new String[]{"shanghai", "上海", "外高桥", "洋山"}, "上海", 121.49, 31.23},
            {new String[]{"shenzhen", "深圳", "盐田"}, "深圳", 114.06, 22.54},
            {new String[]{"beijing", "北京", "中关村"}, "北京", 116.40, 39.90},
            {new String[]{"wuhan", "武汉"}, "武汉", 114.30, 30.59},
            {new String[]{"suzhou", "苏州"}, "苏州", 120.58, 31.30},
            {new String[]{"guangzhou", "广州", "guangdong", "广东", "nansha"}, "广州", 113.27, 23.13},
            {new String[]{"chengdu", "成都"}, "成都", 104.07, 30.67},
            {new String[]{"nanjing", "南京"}, "南京", 118.80, 32.06},
            {new String[]{"hong kong", "香港"}, "香港", 114.16, 22.32},
            {new String[]{"macau", "澳门"}, "澳门", 113.55, 22.20},
            {new String[]{"taiwan", "台湾", "taipei", "hsinchu", "新竹", "tsmc", "台积电", "mediatek", "联发科"}, "中国台湾", 120.97, 24.80},
            {new String[]{"singapore", "新加坡", "sembcorp"}, "新加坡", 103.82, 1.35},
            {new String[]{"malaysia", "马来西亚", "penang", "槟城"}, "马来西亚", 101.70, 3.14},
            {new String[]{"vietnam", "越南", "hanoi", "ho chi minh"}, "越南", 105.84, 21.03},
            {new String[]{"indonesia", "印尼", "jakarta"}, "印尼", 106.85, -6.21},
            {new String[]{"india", "印度", "bangalore", "hyderabad", "chennai"}, "印度班加罗尔", 77.59, 12.97},
            {new String[]{"korea", "韩国", "samsung", "三星", "hynix", "海力士", "seoul", "首尔"}, "韩国首尔", 126.98, 37.57},
            {new String[]{"japan", "日本", "tokyo", "东京", "osaka", "大阪", "nagoya", "名古屋", "renesas", "瑞萨", "murata", "村田"}, "日本东京", 139.69, 35.69},
            {new String[]{"netherlands", "荷兰", "asml", "rotterdam", "鹿特丹", "eindhoven"}, "荷兰", 4.48, 51.92},
            {new String[]{"germany", "德国", "hamburg", "munich", "frankfurt", "infineon", "英飞凌", "bosch", "博世"}, "德国", 9.99, 53.55},
            {new String[]{"france", "法国", "paris"}, "法国", 2.35, 48.86},
            {new String[]{"uk", "英国", "london", "arm", "安谋"}, "英国", -0.13, 51.51},
            {new String[]{"ireland", "爱尔兰", "dublin", "intel fab"}, "爱尔兰", -6.27, 53.33},
            {new String[]{"mexico", "墨西哥", "tijuana", "juarez"}, "墨西哥", -99.13, 19.43},
            {new String[]{"brazil", "巴西", "sao paulo", "圣保罗"}, "巴西", -46.63, -23.55},
            {new String[]{"california", "加州", "los angeles", "long beach", "洛杉矶", "silicon valley", "硅谷", "san jose", "santa clara", "nvidia", "英伟达", "amd", "qualcomm", "高通", "broadcom", "博通", "apple", "苹果"}, "美国加州", -121.88, 37.34},
            {new String[]{"arizona", "亚利桑那", "phoenix", "intel fab", "tsmc usa"}, "美国亚利桑那", -112.07, 33.45},
            {new String[]{"texas", "德州", "houston", "dallas", "austin", "samsung austin"}, "美国德州", -97.74, 30.27},
            {new String[]{"new york", "纽约", "wall street"}, "美国纽约", -74.01, 40.71},
            {new String[]{"washington", "white house", "u.s.", " us ", "united states", "america", "美国", "tariff", "关税", "export control", "出口管制", "federal register", "联邦公报"}, "美国华盛顿", -77.04, 38.90},
            {new String[]{"russia", "俄罗斯", "moscow"}, "俄罗斯", 37.62, 55.75},
            {new String[]{"israel", "以色列", "tower semi"}, "以色列", 34.85, 31.05},
            {new String[]{"china", "中国", "中国大陆"}, "中国", 116.40, 39.90},
            {new String[]{"europe", "欧盟", "eu ", "欧洲", "wto", "世贸组织"}, "欧盟", 4.35, 50.85},
            {new String[]{"middle east", "中东", "uae", "阿联酋", "saudi", "沙特"}, "中东", 55.30, 25.26},
            {new String[]{"africa", "非洲", "congo", "刚果", "cobalt", "钴"}, "非洲", 15.30, -3.30}
    };

    private static final Object[][] SOURCE_GEO = {
            {new String[]{"中国新闻网", "chinanews", "xinhua", "新华"}, "中国北京", 116.40, 39.90},
            {new String[]{"freightwaves", "supplychaindive", "trucking", "manufacturing dive", "supply chain dive"}, "美国", -77.04, 38.90},
            {new String[]{"eetimes", "ee times", "semiconductor", "semiengineering", "chips"}, "美国硅谷", -121.96, 37.35},
            {new String[]{"federalregister", "federal register", "bis.doc"}, "美国华盛顿", -77.04, 38.90},
            {new String[]{"wto.org", "wto "}, "欧盟", 4.35, 50.85},
            {new String[]{"reuters", "bloomberg", "ft.com", "financial times"}, "英国", -0.13, 51.51},
            {new String[]{"商务部", "海关", "政策", "法规", "工信部"}, "中国北京", 116.40, 39.90},
            {new String[]{"nikkei", "日经"}, "日本东京", 139.69, 35.69},
            {new String[]{"korea", "koreaherald", "koreajoong"}, "韩国首尔", 126.98, 37.57}
    };

    public Map<String, Object> buildGisData(List<CrawlerSignal> signals, String layers, String calculatedAt) {
        List<Map<String, Object>> points = gisPoints(signals);
        return Map.of(
                "layers", layers == null ? "heatmap,suppliers,ports,routes" : layers,
                "regions", regionsFromSignals(signals),
                "points", points,
                "routes", gisRoutes(points),
                "updatedAt", calculatedAt,
                "dataSource", "公开网站 RSS 条目映射到来源区域，仅用于风险空间视图"
        );
    }

    public List<Map<String, Object>> gisPoints(List<CrawlerSignal> signals) {
        Map<String, Integer> regionCount = new LinkedHashMap<>();
        List<Map<String, Object>> points = new ArrayList<>();
        for (CrawlerSignal signal : signals) {
            GeoPlace place = geocodeSignal(signal);
            int idx = regionCount.merge(place.name(), 0, (a, b) -> a + 1);
            double angle = idx * 2.399963;
            double radius = idx == 0 ? 0 : 0.35 + (idx % 8) * 0.28;
            double lon = place.lon() + radius * Math.cos(angle);
            double lat = place.lat() + radius * Math.sin(angle) * 0.6;
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", signal.id());
            point.put("name", place.name() + " · " + signal.source());
            point.put("region", place.name());
            point.put("lon", Math.round(lon * 1000.0) / 1000.0);
            point.put("lat", Math.round(lat * 1000.0) / 1000.0);
            point.put("riskIndex", signal.riskScore());
            point.put("analysis", signal.title());
            point.put("source", signal.source());
            point.put("sourceUrl", signal.sourceUrl());
            points.add(point);
        }
        return points;
    }

    public List<Map<String, Object>> gisRoutes(List<Map<String, Object>> points) {
        if (points.size() < 2) {
            return List.of();
        }
        List<Map<String, Object>> routes = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            Map<String, Object> from = points.get(i);
            Map<String, Object> to = points.get((i + 1) % points.size());
            int risk = Math.max(asInt(from.get("riskIndex")), asInt(to.get("riskIndex")));
            routes.add(Map.of(
                    "id", "GR-" + i,
                    "name", from.get("name") + " -> " + to.get("name"),
                    "fromLon", from.get("lon"),
                    "fromLat", from.get("lat"),
                    "toLon", to.get("lon"),
                    "toLat", to.get("lat"),
                    "riskIndex", risk,
                    "sourceUrl", from.getOrDefault("sourceUrl", "")
            ));
        }
        return routes;
    }

    private List<Map<String, Object>> regionsFromSignals(List<CrawlerSignal> signals) {
        if (signals.isEmpty()) {
            return List.of(Map.of("name", "公开源", "status", "暂无成功采集记录", "score", 0));
        }
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (CrawlerSignal signal : signals) {
            scores.merge(signal.dimension(), signal.riskScore(), Math::max);
        }
        return scores.entrySet().stream()
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "status", "公开源维度信号", "score", entry.getValue()))
                .toList();
    }

    private GeoPlace geocodeSignal(CrawlerSignal signal) {
        String haystack = (signal.title() + " " + signal.source()).toLowerCase(Locale.ROOT);
        for (Object[] entry : PLACE_GEO) {
            for (String keyword : (String[]) entry[0]) {
                if (haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return new GeoPlace((String) entry[1], (double) entry[2], (double) entry[3]);
                }
            }
        }
        String source = signal.source() == null ? "" : signal.source().toLowerCase(Locale.ROOT);
        for (Object[] entry : SOURCE_GEO) {
            for (String keyword : (String[]) entry[0]) {
                if (source.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return new GeoPlace((String) entry[1], (double) entry[2], (double) entry[3]);
                }
            }
        }
        return new GeoPlace("全球公开源(待核验地区)", 103.82, 1.35);
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            log.debug("Failed to parse int from '{}': {}", value, ex.getMessage());
            return 0;
        }
    }

    private record GeoPlace(String name, double lon, double lat) {}
}
