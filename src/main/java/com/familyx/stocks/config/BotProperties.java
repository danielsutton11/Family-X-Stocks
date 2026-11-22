package com.familyx.stocks.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bot")
public record BotProperties(Torn torn, Tornsy tornsy, Discord discord, Trading trading) {

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
}
