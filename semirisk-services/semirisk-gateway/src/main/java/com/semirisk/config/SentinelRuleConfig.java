package com.semirisk.config;

import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 流控 + 熔断规则集中配置。
 * <p>
 * 通过代码方式注入默认规则，避免依赖 Sentinel Dashboard 启动；
 * 当 Dashboard 上线后，所有规则可以在线动态调整覆盖此处默认值。
 */
@Configuration
public class SentinelRuleConfig {

    /** Enable @SentinelResource AOP */
    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }

    @PostConstruct
    public void initRules() {
        loadFlowRules();
        loadDegradeRules();
    }

    /** 流控规则：QPS 限流，保护核心接口与下游 LLM/数据库。 */
    private void loadFlowRules() {
        List<FlowRule> rules = new ArrayList<>();
        rules.add(flow("auth.login", 20));            // 登录：单机 20 QPS
        rules.add(flow("dashboard.overview", 50));    // Dashboard：50 QPS
        rules.add(flow("knowledge.ask", 5));          // 知识库 AI 问答：5 QPS（保护 LLM 配额）
        rules.add(flow("reports.create", 3));         // 报告生成：3 QPS（避免大模型并发过载）
        FlowRuleManager.loadRules(rules);
    }

    /** 熔断规则：异常比率/慢调用比率达到阈值后开启熔断。 */
    private void loadDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();
        // 知识库：3 秒响应过慢 60% 则熔断 10 秒
        DegradeRule slowRule = new DegradeRule("knowledge.ask")
                .setGrade(CircuitBreakerStrategy.SLOW_REQUEST_RATIO.getType())
                .setCount(3000)
                .setSlowRatioThreshold(0.6)
                .setMinRequestAmount(5)
                .setStatIntervalMs(10000)
                .setTimeWindow(10);
        rules.add(slowRule);
        // 报告：异常比率 50% 触发熔断 30 秒
        DegradeRule errorRule = new DegradeRule("reports.create")
                .setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType())
                .setCount(0.5)
                .setMinRequestAmount(5)
                .setStatIntervalMs(10000)
                .setTimeWindow(30);
        rules.add(errorRule);
        DegradeRuleManager.loadRules(rules);
    }

    private FlowRule flow(String resource, int qps) {
        FlowRule rule = new FlowRule(resource);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(qps);
        return rule;
    }
}
