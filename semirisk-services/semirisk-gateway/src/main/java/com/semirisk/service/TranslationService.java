package com.semirisk.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Pattern;

@Service
public class TranslationService {

    public Map<String, String> titleTranslation(String title) {
        String original = title == null ? "" : title.trim();
        if (original.isBlank()) {
            return Map.of("zh", "公开源标题为空", "en", "The public-source title is empty");
        }
        if (containsChinese(original)) {
            return Map.of("zh", original, "en", translateChineseTitle(original));
        }
        return Map.of("zh", translateEnglishTitle(original), "en", original);
    }

    public String translateEnglishTitle(String title) {
        String translated = title;
        String[][] terms = {
                {"trump admin", "特朗普政府"},
                {"u.s.", "美国"},
                {"us", "美国"},
                {"china", "中国"},
                {"eu", "欧盟"},
                {"mexico", "墨西哥"},
                {"brazil", "巴西"},
                {"taiwan", "台湾"},
                {"appeals", "提出上诉"},
                {"raises stakes", "提高风险权重"},
                {"aspects of", "部分内容"},
                {"refund order", "退款令"},
                {"labor probes", "劳工调查"},
                {"forced labor", "强迫劳动"},
                {"rare earth", "稀土"},
                {"industrial base", "产业基础"},
                {"data center", "数据中心"},
                {"data centers", "数据中心"},
                {"market surges", "市场增长"},
                {"must turn", "必须将"},
                {"industrial reality", "产业现实"},
                {"supply chain", "供应链"},
                {"semiconductor", "半导体"},
                {"semiconductors", "半导体"},
                {"manufacturing", "制造"},
                {"manufacturer", "制造商"},
                {"manufacturers", "制造商"},
                {"logistics", "物流"},
                {"freight", "货运"},
                {"trucking", "公路运输"},
                {"port", "港口"},
                {"ports", "港口"},
                {"delay", "延迟"},
                {"delays", "延迟"},
                {"strike", "罢工"},
                {"strikes", "罢工"},
                {"shortage", "短缺"},
                {"shortages", "短缺"},
                {"disruption", "中断"},
                {"disruptions", "中断"},
                {"tariff", "关税"},
                {"tariffs", "关税"},
                {"export", "出口"},
                {"exports", "出口"},
                {"imports", "进口"},
                {"restriction", "限制"},
                {"restrictions", "限制"},
                {"risk", "风险"},
                {"risks", "风险"},
                {"warning", "预警"},
                {"recall", "召回"},
                {"chip", "芯片"},
                {"chips", "芯片"},
                {"automotive", "汽车"},
                {"electronics", "电子"},
                {"supplier", "供应商"},
                {"suppliers", "供应商"},
                {"steel", "钢铁"},
                {"aluminum", "铝"},
                {"copper", "铜"},
                {"factory", "工厂"},
                {"invest", "投资"},
                {"global", "全球"},
                {"europe", "欧洲"},
                {"ambition", "目标"},
                {"order", "命令"},
                {"probe", "调查"},
                {"probes", "调查"}
        };
        for (String[] term : terms) {
            translated = translated.replaceAll("(?i)" + Pattern.quote(term[0]), term[1]);
        }
        return translated.equals(title) ? "原文为英文，暂无关键词命中：" + title : translated;
    }

    public String translateChineseTitle(String title) {
        String translated = title
                .replace("公开源返回内容未解析到 RSS/Atom 条目", "Public source returned no parseable RSS/Atom entries")
                .replace("近三天未发现 RSS/Atom 条目", "No RSS/Atom entries were found in the last three days")
                .replace("公开源采集失败", "Public source collection failed")
                .replace("采集失败", "Collection failed")
                .replace("供应链", "supply chain")
                .replace("半导体", "semiconductor")
                .replace("物流", "logistics")
                .replace("制造", "manufacturing")
                .replace("中断", "disruption")
                .replace("拥堵", "congestion")
                .replace("短缺", "shortage")
                .replace("关税", "tariff")
                .replace("出口", "export")
                .replace("风险", "risk");
        return translated.equals(title) ? "Chinese source title: " + title : translated;
    }

    public boolean containsChinese(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '一' && c <= '鿿') {
                return true;
            }
        }
        return false;
    }

    public String levelName(String level) {
        return switch (level) {
            case "高危" -> "High";
            case "中危" -> "Medium";
            case "低危" -> "Low";
            default -> "Pending";
        };
    }

    public String statusName(String status) {
        return switch (status) {
            case "未处理" -> "Open";
            case "处理中" -> "In Progress";
            case "已忽略" -> "Ignored";
            default -> status;
        };
    }

    public String dimensionName(String dimension) {
        return switch (dimension) {
            case "供应链" -> "Supply Chain";
            case "物流" -> "Logistics";
            case "半导体" -> "Semiconductor";
            case "制造" -> "Manufacturing";
            default -> dimension;
        };
    }
}
