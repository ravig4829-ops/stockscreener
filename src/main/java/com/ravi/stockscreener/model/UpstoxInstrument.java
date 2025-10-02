package com.ravi.stockscreener.model;

import com.fasterxml.jackson.annotation.JsonProperty;


public record UpstoxInstrument(
        @JsonProperty("instrument_key") String instrumentKey,
        @JsonProperty("weekly") Boolean weekly,
        @JsonProperty("mtf_enabled") Boolean mtfEnabled,
        @JsonProperty("segment") String segment,
        @JsonProperty("name") String name,
        @JsonProperty("exchange") String exchange,
        @JsonProperty("expiry") Long expiry,
        @JsonProperty("instrument_type") String instrumentType,
        @JsonProperty("asset_symbol") String assetSymbol,
        @JsonProperty("underlying_symbol") String underlyingSymbol,

        @JsonProperty("lot_size") Integer lotSize,
        @JsonProperty("freeze_quantity") Double freezeQuantity,
        @JsonProperty("exchange_token") String exchangeToken,
        @JsonProperty("minimum_lot") Integer minimumLot,
        @JsonProperty("tick_size") Double tickSize,
        @JsonProperty("asset_type") String assetType,
        @JsonProperty("underlying_type") String underlyingType,
        @JsonProperty("trading_symbol") String tradingSymbol,
        @JsonProperty("strike_price") Double strikePrice,
        @JsonProperty("qty_multiplier") Double qtyMultiplier) {

}
