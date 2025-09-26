package com.ravi.stockscreener.util;

import java.util.List;

public enum IndicatorType {
    // --- Moving Averages (simple / variants)
    SMA("Simple Moving Average", "SMA"),
    EMA("Exponential Moving Average", "EMA"),
    WMA("Weighted Moving Average", "WMA"),
    LWMA("Linear Weighted Moving Average", "LWMA"),
    DEMA("Double Exponential Moving Average", "DEMA"),
    TEMA("Triple Exponential Moving Average", "TEMA"),
    TMA("Triangular Moving Average", "TMA"),
    TRIMA("Triangular (SMA of SMA)", "TRIMA"), // alias for TMA
    ATMA("Asymmetric Triangular Moving Average", "ATMA"),
    HMA("Hull Moving Average", "HMA"),
    KAMA("Kaufman Adaptive Moving Average", "KAMA"),
    ZLEMA("Zero Lag Exponential Moving Average", "ZLEMA"),
    SMMA("Smoothed Moving Average", "SMMA"),
    WILDERS("Wilder's Moving Average", "WILDERS"),
    MMA("Modified Moving Average", "MMA"),
    LSMA("Least Squares Moving Average", "LSMA"),
    SGMA("Savitzky-Golay Moving Average (approx)", "SGMA"),
    JMA("Jurik Moving Average (JMA)", "JMA"),
    McGINLEY("McGinley Dynamic Moving Average", "McGinley"),
    VIDYA("Variable Index Dynamic Average (VIDYA)", "VIDYA"),
    VMA("Variable Moving Average (VMA)", "VMA"),
    DMA("Displaced Moving Average", "DMA"),
    EDMA("Exponential Displaced MA", "EDMA"),

    // --- Price-volume / VWAP
    VWMA("Volume Weighted Moving Average", "VWMA"),
    VWAP("Volume Weighted Average Price", "VWAP"),
    VOLUME("Raw Volume", "VOLUME"),

    // --- Momentum & Oscillators
    RSI("Relative Strength Index", "RSI"),
    STOCH("Stochastic Oscillator", "STOCH"),
    STOCH_RSI("Stochastic RSI", "STOCH_RSI"),
    MACD("Moving Average Convergence Divergence", "MACD"),
    MACD_SIGNAL("MACD Signal Line", "MACD_SIGNAL"),
    MACD_HIST("MACD Histogram", "MACD_HIST"),
    PPO("Percentage Price Oscillator", "PPO"),
    ROC("Rate of Change", "ROC"),
    MOM("Momentum", "MOM"),
    CCI("Commodity Channel Index", "CCI"),
    TRIX("TRIX (Triple Exponential Oscillator)", "TRIX"),
    KST("Know Sure Thing (KST)", "KST"),
    ULTIMATE_OSC("Ultimate Oscillator", "UO"),
    WILLIAMS_R("Williams %R", "WILLR"),
    STOCH_FAST_K("Stochastic Fast %K", "STOCH_FAST_K"),
    STOCH_SLOW_D("Stochastic Slow %D", "STOCH_SLOW_D"),
    RSI_SMMA("RSI smoothed with SMMA/Wilder", "RSI_SMMA"),
    TSI("True Strength Index", "TSI"),
    CMO("Chande Momentum Oscillator", "CMO"),

    // --- Volatility / Bands
    ATR("Average True Range", "ATR"),
    BBANDS("Bollinger Bands (middle = SMA)", "BBANDS"),
    KELTNER("Keltner Channels (center = EMA)", "KELTNER"),
    DONCHIAN("Donchian Channels", "DONCHIAN"),
    HISTORICAL_VOLATILITY("Historical Volatility", "HIST_VOL"),
    CHAUKIN_VOLATILITY("Chaikin Volatility", "CHAUKIN_VOL"),

    // --- Trend & Directional
    ADX("Average Directional Index", "ADX"),
    DMI("Directional Movement Index", "DMI"),
    PSAR("Parabolic SAR (PSAR)", "PSAR"),
    SUPER_TREND("SuperTrend", "SUPER_TREND"),
    ICHIMOKU("Ichimoku Cloud", "ICHIMOKU"),
    AROON("Aroon", "AROON"),
    AROON_UP("Aroon Up", "AROON_UP"),
    AROON_DOWN("Aroon Down", "AROON_DOWN"),
    DEMA_TREND("DEMA Trend (DEMA of source)", "DEMA_TREND"),

    // --- Volume & Money Flow
    OBV("On-Balance Volume", "OBV"),
    ADL("Accumulation/Distribution Line", "ADL"),
    MFI("Money Flow Index", "MFI"),
    CMF("Chaikin Money Flow", "CMF"),
    FORCE_INDEX("Force Index", "FORCE"),
    EASE_OF_MOVEMENT("Ease of Movement (EOM)", "EOM"),
    PVO("Percentage Volume Oscillator", "PVO"),
    PVI("Positive Volume Index", "PVI"),
    NVI("Negative Volume Index", "NVI"),

    // --- Price action / pivots / transforms
    PIVOT_POINTS("Pivot Points", "PIVOT_POINTS"),
    FIBONACCI_RETRACEMENT("Fibonacci Retracement", "FIB_RETRACE"),
    CAMARILLA("Camarilla Pivots", "CAMARILLA"),
    WOODIE_PIVOTS("Woodie Pivots", "WOODIE"),
    RENKO("Renko", "RENKO"),
    HEIKIN_ASHI("Heikin Ashi", "HEIKIN_ASHI"),
    RANGE_BARS("Range Bars", "RANGE_BARS"),

    // --- Statistical / regression / correlation
    Z_SCORE("Z-Score", "Z_SCORE"),
    BETA("Beta vs Benchmark", "BETA"),
    CORRELATION("Correlation", "CORRELATION"),
    LINEAR_REGRESSION_SLOPE("Linear Regression Slope", "LR_SLOPE"),
    LINEAR_REGRESSION_INTERCEPT("Linear Regression Intercept", "LR_INTERCEPT"),
    STDDEV("Standard Deviation", "STDDEV"),
    VARIANCE("Variance", "VAR"),

    // --- Breadth / market indicators
    ADVANCE_DECLINE("Advance / Decline", "AD"),
    MCCLELLAN_OSC("McClellan Oscillator", "MCCLELLAN"),
    TRIN("Arms Index (TRIN)", "TRIN"),
    PUT_CALL_RATIO("Put/Call Ratio", "PUT_CALL"),

    // --- Candlestick pattern names (signal-only)
    DOJI("Doji pattern", "DOJI"),
    HAMMER("Hammer pattern", "HAMMER"),
    HANGING_MAN("Hanging Man pattern", "HANGING_MAN"),
    ENGULFING_BULL("Bullish Engulfing", "ENGULFING_BULL"),
    ENGULFING_BEAR("Bearish Engulfing", "ENGULFING_BEAR"),
    MORNING_STAR("Morning Star", "MORNING_STAR"),
    EVENING_STAR("Evening Star", "EVENING_STAR"),

    // --- Transforms / filters
    HILBERT_TRANSFORM("Hilbert Transform", "HILBERT"),
    FISHER_TRANSFORM("Fisher Transform", "FISHER"),
    HIGHS_LOWS_DELTA("High-Low Delta", "HL_DELTA"),

    // --- Misc / placeholders
    RANGE_ROC("Range ROC", "RANGE_ROC"),
    MASS_INDEX("Mass Index", "MASS"),
    VOLUME_WEIGHTED_AVG("Volume Weighted Average (alias)", "VWA");

    private final String displayName;
    private final String symbol;

    IndicatorType(String displayName, String symbol) {
        this.displayName = displayName;
        this.symbol = symbol;
    }

    public String displayName() {
        return displayName;
    }

    public String symbol() {
        return symbol;
    }

    /**
     * Whether this indicator needs a numeric source series (close/price). Many statistical
     * or derived indicators need source; some pivot/price-action or pure-volume items do not.
     */
    public boolean needsSource() {
        return switch (this) {
            // pure signals or pivot families that operate on OHLC groups may be considered "no single source"
            case PIVOT_POINTS, FIBONACCI_RETRACEMENT, CAMARILLA, WOODIE_PIVOTS,
                 RENKO, RANGE_BARS, HEIKIN_ASHI -> false;
            // volume-only constructs do not require a price source (they use VOLUME)
            case VOLUME, OBV, ADL, PVI, NVI, PVO -> false;
            default -> true;
        };
    }

    /**
     * Default parameter names for common indicators; used by UI and factory to prepopulate.
     */
    public List<String> defaultParamNames() {
        return switch (this) {
            case SMA, EMA, WMA, LWMA, DEMA, TEMA, TRIMA, HMA, KAMA, ZLEMA, SMMA, WILDERS,
                 LSMA, SGMA, JMA, McGINLEY, MMA, VMA, ATMA, DMA, EDMA -> List.of("period");
            case VWMA, VWAP -> List.of("period", "session");
            case MACD, MACD_SIGNAL, MACD_HIST -> List.of("fast", "slow", "signal");
            case BBANDS -> List.of("period", "deviation");
            case STOCH -> List.of("k", "d", "k_period", "d_period");
            case STOCH_RSI -> List.of("rsi_period", "stoch_period", "smooth_k", "smooth_d");
            case PSAR -> List.of("step", "maxStep");
            case ICHIMOKU -> List.of("conversion", "base", "span_b", "lagging");
            case KELTNER -> List.of("period", "multiplier");
            case BETA -> List.of("benchmark", "period");
            case VIDYA -> List.of("period", "er_period");
            default -> List.of();
        };
    }

    /**
     * Whether the indicator uses volume as input.
     */
    public boolean usesVolume() {
        return switch (this) {
            case VWMA, VWAP, OBV, CMF, MFI, ADL, PVO, PVI, NVI -> true;
            default -> false;
        };
    }

    /**
     * Whether the indicator needs the high/low values explicitly (not just close).
     */
    public boolean usesHighLow() {
        return switch (this) {
            case ATR, PSAR, STOCH, STOCH_RSI, ICHIMOKU, KELTNER, DONCHIAN, PIVOT_POINTS,
                 WILLIAMS_R, HEIKIN_ASHI, CAMARILLA -> true;
            default -> false;
        };
    }

    /**
     * Rough classification: trend-following indicators.
     */
    public boolean isTrendIndicator() {
        return switch (this) {
            case SMA, EMA, HMA, KAMA, McGINLEY, ADX, DMI, PSAR, SUPER_TREND, ICHIMOKU, AROON,
                 VWMA, VWAP, TEMA, DEMA, TRIMA, ATMA, ZLEMA, VMA -> true;
            default -> false;
        };
    }

    /**
     * Rough classification: momentum / oscillator indicators.
     */
    public boolean isMomentumIndicator() {
        return switch (this) {
            case RSI, STOCH, STOCH_RSI, MACD, PPO, ROC, MOM, CCI, TRIX, KST, TSI, CMO, WILLIAMS_R -> true;
            default -> false;
        };
    }

    /**
     * Rough classification: volatility indicators.
     */
    public boolean isVolatilityIndicator() {
        return switch (this) {
            case ATR, BBANDS, KELTNER, DONCHIAN, HISTORICAL_VOLATILITY, CHAUKIN_VOLATILITY, MASS_INDEX -> true;
            default -> false;
        };
    }

    @Override
    public String toString() {
        return displayName;
    }
}
