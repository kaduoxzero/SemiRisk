package com.semirisk.data;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CrawlerServiceScoringTests {

    @Test
    void ordinaryPublicNewsDoesNotBecomeDefaultRiskSignal() {
        int score = CrawlerService.ruleRiskScore(
                "WTOUpdates",
                "policy",
                "WTO to open its doors to the public on 5 July");

        assertThat(score).isZero();
    }

    @Test
    void exportControlAndSanctionNewsReceivesARealRiskScore() {
        int score = CrawlerService.ruleRiskScore(
                "FederalRegisterExportControl",
                "policy",
                "Publication of Venezuela Sanctions Regulations Web General Licenses and export control restrictions");

        assertThat(score).isGreaterThanOrEqualTo(75);
    }
}
