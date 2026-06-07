import os
import statistics
from collections import Counter, defaultdict
from datetime import datetime
from typing import Any

import requests
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field


DEFAULT_MODEL = "deepseekv4-pro"
MODEL_ALIASES = {
    "deepseekv4-pro": "deepseek-v4-pro",
    "deepseekv4-flash": "deepseek-v4-flash",
}
MAX_EVENTS = int(os.getenv("MAX_EVENTS", "120"))
REQUEST_TIMEOUT = int(os.getenv("DEEPSEEK_TIMEOUT_SECONDS", "90"))


class AnalyzeRequest(BaseModel):
    templateType: str = Field(default="供应链风险研判报告")
    dateRange: str = Field(default="全部真实数据")
    events: list[dict[str, Any]] = Field(default_factory=list)


app = FastAPI(title="SemiRisk AI Analysis Service", version="1.1.0")


def text(value: Any, default: str = "-") -> str:
    if value is None:
        return default
    value = str(value).strip()
    return value or default


def number(value: Any) -> float:
    try:
        return float(value or 0)
    except (TypeError, ValueError):
        return 0.0


def normalize_events(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    normalized = []
    for event in events[:MAX_EVENTS]:
        normalized.append({
            "eventTitle": text(event.get("eventTitle")),
            "enterpriseName": text(event.get("enterpriseName")),
            "category": text(event.get("category"), "未分类"),
            "riskLevel": text(event.get("riskLevel"), "UNCLASSIFIED").upper(),
            "status": text(event.get("status"), "UNKNOWN").upper(),
            "riskScore": number(event.get("riskScore")),
            "sourceName": text(event.get("sourceName")),
            "description": text(event.get("description"), ""),
            "occurredAt": text(event.get("occurredAt"), ""),
        })
    return normalized


def risk_summary(events: list[dict[str, Any]]) -> dict[str, Any]:
    levels = Counter(event["riskLevel"] for event in events)
    categories = Counter(event["category"] for event in events)
    statuses = Counter(event["status"] for event in events)
    scores = [event["riskScore"] for event in events if event["riskScore"] > 0]
    high_events = sorted(
        [event for event in events if event["riskLevel"] in {"CRITICAL", "HIGH", "WARNING"} or event["riskScore"] >= 70],
        key=lambda item: item["riskScore"],
        reverse=True,
    )
    by_enterprise: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for event in events:
        by_enterprise[event["enterpriseName"]].append(event)
    enterprise_risk = sorted(
        (
            {
                "enterpriseName": enterprise,
                "count": len(rows),
                "avgScore": round(statistics.mean([row["riskScore"] for row in rows]), 2),
                "maxScore": round(max(row["riskScore"] for row in rows), 2),
            }
            for enterprise, rows in by_enterprise.items()
            if enterprise != "-"
        ),
        key=lambda item: (item["maxScore"], item["count"]),
        reverse=True,
    )
    return {
        "total": len(events),
        "levels": levels,
        "categories": categories,
        "statuses": statuses,
        "avgScore": round(statistics.mean(scores), 2) if scores else 0,
        "maxScore": round(max(scores), 2) if scores else 0,
        "highEvents": high_events[:10],
        "enterpriseRisk": enterprise_risk[:8],
    }


def compact_counter(counter: Counter[str], empty: str) -> str:
    if not counter:
        return empty
    return "、".join(f"{key} {value} 起" for key, value in counter.most_common())


def provider_model_name(model: str) -> str:
    return MODEL_ALIASES.get(model, model)


def build_prompt(req: AnalyzeRequest) -> str:
    events = normalize_events(req.events)
    summary = risk_summary(events)
    lines = [
        "你是供应链风险管理专家。请只基于用户提供的数据库记录分析，不得编造外部事实。",
        "",
        f"报告模板: {req.templateType}",
        f"分析区间: {req.dateRange}",
        f"事件总数: {summary['total']}",
        f"平均风险分: {summary['avgScore']}",
        f"最高风险分: {summary['maxScore']}",
        f"等级分布: {compact_counter(summary['levels'], '暂无等级数据')}",
        f"类型分布: {compact_counter(summary['categories'], '暂无类型数据')}",
        f"状态分布: {compact_counter(summary['statuses'], '暂无状态数据')}",
        "",
        "高风险事件样本:",
    ]
    for event in summary["highEvents"]:
        lines.append(
            "- "
            f"{event['eventTitle']} | 企业: {event['enterpriseName']} | "
            f"分类: {event['category']} | 等级: {event['riskLevel']} | "
            f"状态: {event['status']} | 风险分: {event['riskScore']} | "
            f"来源: {event['sourceName']} | 描述: {event['description'][:240]}"
        )
    if not summary["highEvents"]:
        lines.append("- 当前样本未识别到高风险事件。")

    lines.extend([
        "",
        "重点企业聚合:",
    ])
    for item in summary["enterpriseRisk"]:
        lines.append(
            f"- {item['enterpriseName']} | 事件数: {item['count']} | "
            f"平均风险分: {item['avgScore']} | 最高风险分: {item['maxScore']}"
        )
    if not summary["enterpriseRisk"]:
        lines.append("- 暂无企业聚合数据。")

    lines.extend([
        "",
        "输出要求:",
        "1. 用 Markdown 输出。",
        "2. 包含：总体判断、高风险链路、重点企业影响、处置建议、数据不足说明。",
        "3. 建议要可执行，明确优先级和闭环动作。",
        "4. 不要输出与输入数据无关的新闻、市场传闻或虚构结论。",
    ])
    return "\n".join(lines)


def build_offline_report(req: AnalyzeRequest) -> str:
    events = normalize_events(req.events)
    summary = risk_summary(events)
    generated_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    lines = [
        f"# {req.templateType}",
        "",
        f"- 生成时间: {generated_at}",
        f"- 分析区间: {req.dateRange}",
        f"- 样本来源: risk_event 真实业务记录，共 {summary['total']} 起事件",
        f"- 等级分布: {compact_counter(summary['levels'], '暂无等级数据')}",
        f"- 类型分布: {compact_counter(summary['categories'], '暂无类型数据')}",
        f"- 状态分布: {compact_counter(summary['statuses'], '暂无状态数据')}",
        f"- 当前风险指数: {summary['avgScore']}",
        "",
        "## 总体判断",
    ]
    if summary["total"] == 0:
        lines.append("当前没有可分析的风险事件记录，建议先补充企业、事件、来源、发生时间和处置状态等基础数据。")
    elif summary["maxScore"] >= 80:
        lines.append("当前样本中存在高强度风险暴露，应优先处理最高分事件，并同步评估相关企业的履约、交付和合规影响。")
    elif summary["avgScore"] >= 50:
        lines.append("当前整体风险处于中等偏高水平，建议按企业和风险类型建立周度跟踪机制。")
    else:
        lines.append("当前整体风险指数相对可控，但仍需关注未闭环事件和数据缺失项。")

    lines.extend(["", "## 高风险链路"])
    if summary["highEvents"]:
        for event in summary["highEvents"]:
            lines.append(
                f"- **{event['enterpriseName']}**: {event['eventTitle']}，"
                f"等级 {event['riskLevel']}，风险分 {event['riskScore']}，状态 {event['status']}。"
            )
    else:
        lines.append("- 当前样本未识别到 CRITICAL/HIGH/WARNING 或风险分 >= 70 的事件。")

    lines.extend(["", "## 重点企业影响"])
    if summary["enterpriseRisk"]:
        for item in summary["enterpriseRisk"][:5]:
            lines.append(
                f"- **{item['enterpriseName']}**: 关联事件 {item['count']} 起，"
                f"最高风险分 {item['maxScore']}，平均风险分 {item['avgScore']}。"
            )
    else:
        lines.append("- 暂无可聚合的企业风险数据。")

    lines.extend([
        "",
        "## 处置建议",
        "1. P0: 对 CRITICAL 或风险分 >= 80 的事件建立 24 小时内响应闭环，明确责任人、截止时间和替代方案。",
        "2. P1: 对 WARNING/HIGH 事件执行周度复核，持续跟踪风险分、来源可信度和处置状态变化。",
        "3. P2: 对缺少经纬度、来源名称、发生时间、影响分或概率分的数据进行补录，提升 GIS 和趋势分析准确性。",
        "4. P2: 将重复出现的企业和风险类型沉淀为知识库条目，用于后续报告模板和处置预案复用。",
        "",
        "## 数据不足说明",
        "本报告只使用请求中的数据库事件记录生成；未配置外部大模型密钥时，报告由本地规则引擎生成，不补充外部事实。",
    ])
    return "\n".join(lines)


@app.get("/health")
def health() -> dict[str, Any]:
    model = os.getenv("DEEPSEEK_MODEL", DEFAULT_MODEL)
    return {
        "status": "UP",
        "model": model,
        "providerModel": provider_model_name(model),
        "providerConfigured": bool(os.getenv("DEEPSEEK_API_KEY")),
        "maxEvents": MAX_EVENTS,
    }


@app.post("/analyze")
def analyze(req: AnalyzeRequest) -> dict[str, Any]:
    events = normalize_events(req.events)
    if not os.getenv("DEEPSEEK_API_KEY"):
        return {
            "content": build_offline_report(req),
            "provider": "offline-rules",
            "eventCount": len(events),
        }

    base_url = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com/chat/completions")
    model = os.getenv("DEEPSEEK_MODEL", DEFAULT_MODEL)
    provider_model = provider_model_name(model)
    payload = {
        "model": provider_model,
        "temperature": float(os.getenv("DEEPSEEK_TEMPERATURE", "0.2")),
        "messages": [
            {
                "role": "system",
                "content": "你是供应链风险管理专家，只能依据用户提供的真实业务数据进行分析。",
            },
            {"role": "user", "content": build_prompt(req)},
        ],
    }
    try:
        response = requests.post(
            base_url,
            json=payload,
            headers={"Authorization": f"Bearer {os.environ['DEEPSEEK_API_KEY']}"},
            timeout=REQUEST_TIMEOUT,
        )
        response.raise_for_status()
        data = response.json()
    except requests.Timeout as exc:
        raise HTTPException(status_code=504, detail="AI provider request timed out") from exc
    except requests.RequestException as exc:
        raise HTTPException(status_code=502, detail=f"AI provider request failed: {exc}") from exc
    except ValueError as exc:
        raise HTTPException(status_code=502, detail="AI provider returned invalid JSON") from exc

    content = data.get("choices", [{}])[0].get("message", {}).get("content")
    if not content:
        raise HTTPException(status_code=502, detail="AI provider response did not include content")
    return {
        "content": content,
        "provider": "deepseek",
        "model": model,
        "providerModel": provider_model,
        "eventCount": len(events),
    }
