package com.semirisk.model;

/**
 * 知识库文档处理状态枚举。
 *
 * <p>文档处理全链路状态流转：
 * UPLOADED（已上传待处理）→ PROCESSING（AI评估与解析中）→ SUCCESS（处理成功且已索引）
 * 或 → FAILED（处理/解析失败）。</p>
 */
public enum KnowledgeDocStatus {

    /** 已上传，等待处理。 */
    UPLOADED("已上传"),
    /** AI 评估与解析进行中。 */
    PROCESSING("处理中"),
    /** 处理成功且已索引至 ES。 */
    SUCCESS("成功"),
    /** 处理/解析失败。 */
    FAILED("失败");

    private final String label;

    KnowledgeDocStatus(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String label() {
        return label;
    }
}
