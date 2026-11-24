package com.familyx.stocks.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bot")
public record BotProperties(Torn torn, Tornsy tornsy, Discord discord, Trading trading, Rotation rotation) {

    public record Torn(String apiKey) {
    }

    public record Tornsy(boolean backfillEnabled, int backfillMonths) {
    }

    /** @param ownerId Discord user id allowed to export data; when blank, server administrators can. */
    public record Discord(String token, String controlChannelId, String alertChannelId, String alertRoleId,
                          String ownerId) {
    }

    public record Trading(double positionSize, double minPosition) {
    }

    /**
     * @param lookbackHours average the ranking compares against (672 = 4 weeks)
     * @param everyHours    hours between rebalances
     * @param hold          number of stocks held
     * @param keep          a holding is only sold once it ranks worse than this
     * @param anchorHourUtc hour of day (UTC = TCT) the rebalance schedule is anchored to
     */
    public record Rotation(int lookbackHours, int everyHours, int hold, int keep, int anchorHourUtc) {
        public com.familyx.stocks.engine.Rotation.Params params() {
            return new com.familyx.stocks.engine.Rotation.Params(lookbackHours, everyHours, hold, keep);
        }
    }
}
