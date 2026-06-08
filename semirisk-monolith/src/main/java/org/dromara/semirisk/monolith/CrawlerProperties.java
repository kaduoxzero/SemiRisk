package org.dromara.semirisk.monolith;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "semirisk.crawler")
public class CrawlerProperties {
    private boolean startupEnabled = true;
    private long fixedDelayMs = 1_800_000;
    private int requestTimeoutSeconds = 20;
    private String cisaKevUrl;
    private String usgsEarthquakeUrl;

    public boolean isStartupEnabled() {
        return startupEnabled;
    }

    public void setStartupEnabled(boolean startupEnabled) {
        this.startupEnabled = startupEnabled;
    }

    public long getFixedDelayMs() {
        return fixedDelayMs;
    }

    public void setFixedDelayMs(long fixedDelayMs) {
        this.fixedDelayMs = fixedDelayMs;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public String getCisaKevUrl() {
        return cisaKevUrl;
    }

    public void setCisaKevUrl(String cisaKevUrl) {
        this.cisaKevUrl = cisaKevUrl;
    }

    public String getUsgsEarthquakeUrl() {
        return usgsEarthquakeUrl;
    }

    public void setUsgsEarthquakeUrl(String usgsEarthquakeUrl) {
        this.usgsEarthquakeUrl = usgsEarthquakeUrl;
    }
}
