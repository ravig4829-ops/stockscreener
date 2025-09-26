package com.ravi.stockscreener.util;

import com.ravi.stockscreener.model.Operand;
import com.ravi.stockscreener.model.Param;
import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;

import org.ta4j.core.indicators.*;

import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.aroon.AroonDownIndicator;
import org.ta4j.core.indicators.averages.*;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.donchian.DonchianChannelMiddleIndicator;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.numeric.NumericIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.volume.AccumulationDistributionIndicator;
import org.ta4j.core.indicators.volume.ChaikinMoneyFlowIndicator;
import org.ta4j.core.indicators.volume.MoneyFlowIndexIndicator;
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

import java.util.List;

import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.OpenPriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;


import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class IndicatorFactory {

    // Lightweight cache: series identity + operand string -> built indicator
    private static final Map<String, Indicator<Num>> INDICATOR_CACHE = new ConcurrentHashMap<>();

    public static Indicator<Num> buildIndicator(BarSeries series, Operand operand) {
        log.debug("buildIndicator {} / series id {}", operand, series == null ? "null" : series.hashCode());
        String cacheKey = Objects.requireNonNull(series).hashCode() + ":" + (operand == null ? "null" : operand.toString());
        Indicator<Num> cached = INDICATOR_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Indicator<Num> out = switch (operand) {
            case InputsOperand inputsOperand -> buildInputIndicator(series, inputsOperand.getValue());
            case StockAttributeOperand stockAttributeOperand ->
                    buildPriceIndicator(series, stockAttributeOperand.stockAttribute);
            case IndicatorOperand indicatorOperand -> buildTechnicalIndicator(series, indicatorOperand);
            case null, default ->
                    throw new IllegalArgumentException("Unsupported operand type: " + (operand == null ? "null" : operand.getClass()));
        };

        if (out != null) INDICATOR_CACHE.put(cacheKey, out);
        return out;
    }

    private static Indicator<Num> buildInputIndicator(BarSeries series, Number input) {
        return new ConstantIndicator<>(series, DecimalNum.valueOf(input));
    }

    private static Indicator<Num> buildPriceIndicator(BarSeries series, StockAttributeOperand.StockAttribute attribute) {
        return switch (attribute) {
            case OPEN -> new OpenPriceIndicator(series);
            case HIGH -> new HighPriceIndicator(series);
            case LOW -> new LowPriceIndicator(series);
            case CLOSE -> new ClosePriceIndicator(series);
            case VOLUME -> new VolumeIndicator(series);
            default -> throw new IllegalArgumentException("Unknown price attribute: " + attribute);
        };
    }

    private static Indicator<Num> buildTechnicalIndicator(BarSeries series, IndicatorOperand operand) {
        List<Param> params = operand.getParams() == null ? List.of() : operand.getParams();
        Indicator<Num> src = resolveSourceIndicator(series, operand.getSource());

        // some reusable local indicators
        Indicator<Num> ema1, ema2, ema3, wma1, wma2;
        switch (operand.getIndicatorType()) {

            // --- Simple moving averages / variants
            case SMA -> {
                int period = getParamInt(params, "period", 14);
                return new SMAIndicator(src, period);
            }
            case EMA -> {
                int period = getParamInt(params, "period", 14);
                return new EMAIndicator(src, period);
            }
            case WMA -> {
                int period = getParamInt(params, "period", 14);
                return new WMAIndicator(src, period);
            }
            case DEMA, DEMA_TREND -> {
                int period = getParamInt(params, "period", 14);
                ema1 = new EMAIndicator(src, period);
                ema2 = new EMAIndicator(ema1, period);
                return NumericIndicator.of(ema1).multipliedBy(2).minus(ema2);
            }
            case TEMA -> {
                int period = getParamInt(params, "period", 14);
                ema1 = new EMAIndicator(src, period);
                ema2 = new EMAIndicator(ema1, period);
                ema3 = new EMAIndicator(ema2, period);
                return NumericIndicator.of(ema1).multipliedBy(3)
                        .minus(NumericIndicator.of(ema2).multipliedBy(3))
                        .plus(ema3);
            }
            case TRIMA, TMA -> {
                int period = getParamInt(params, "period", 14);
                Indicator<Num> first = new SMAIndicator(src, period);
                return new SMAIndicator(first, period);
            }
            case ATMA -> {
                int period = getParamInt(params, "period", 14);
                return new ATMAIndicator(src, period);
            }
            case HMA -> {
                int period = getParamInt(params, "period", 16);
                int half = Math.max(1, period / 2);
                int root = Math.max(1, (int) Math.round(Math.sqrt(period)));
                wma1 = new WMAIndicator(src, half);
                wma2 = new WMAIndicator(src, period);
                Indicator<Num> diff = NumericIndicator.of(wma1).multipliedBy(2).minus(wma2);
                return new WMAIndicator(diff, root);
            }
            case KAMA -> {
                // If you want precise KAMA, replace this placeholder with a real KAMAIndicator implementation.
                int period = getParamInt(params, "period", 10);
                return new EMAIndicator(src, period);
            }
            case ZLEMA -> {
                int period = getParamInt(params, "period", 14);
                return new ZLEMAIndicator(src, period);
            }
            case LSMA -> {
                int period = getParamInt(params, "period", 14);
                return new LSMAIndicator(src, period);
            }

            case VIDYA -> {
                int period = getParamInt(params, "period", 14);
                int er = getParamInt(params, "er_period", Math.max(1, period / 2));
                return new VIDYAIndicator(src, period, er);
            }
            case SMMA, WILDERS -> {
                int period = getParamInt(params, "period", 14);
                return new WildersMAIndicator(src, period);
            }

            // --- Volume / VWAP
            case VOLUME -> {
                return new VolumeIndicator(series);
            }
            case VWMA -> {
                int period = getParamInt(params, "period", 14);
                // If your ta4j version has a VWMAIndicator use it; otherwise fallback to custom VWMA
                return new VWMAIndicator(src, period);
            }
            case VWAP -> {
                throw new UnsupportedOperationException("VWAP (session-based) must be implemented with session boundaries; not provided here.");
            }

            // --- Momentum & Oscillators
            case RSI -> {
                int period = getParamInt(params, "period", 14);
                return new RSIIndicator(src, period);
            }
            case STOCH, STOCH_SLOW_D -> {
                int kPeriod = getParamInt(params, "k", 14);
                StochasticOscillatorKIndicator kInd = new StochasticOscillatorKIndicator(series, kPeriod);
                return new StochasticOscillatorDIndicator(kInd);
            }
            case STOCH_RSI -> {
                int stochPeriod = getParamInt(params, "stoch_period", 14);
                StochasticOscillatorKIndicator k = new StochasticOscillatorKIndicator(series, stochPeriod);
                return new StochasticOscillatorDIndicator(k);
            }
            case MACD -> {
                int fast = getParamInt(params, "fast", 12);
                int slow = getParamInt(params, "slow", 26);
                return new MACDIndicator(src, fast, slow);
            }
            case MACD_SIGNAL -> {
                int fast = getParamInt(params, "fast", 12);
                int slow = getParamInt(params, "slow", 26);
                int signal = getParamInt(params, "signal", 9);
                MACDIndicator macd = new MACDIndicator(src, fast, slow);
                return new EMAIndicator(macd, signal);
            }
            case MACD_HIST -> {
                int fast = getParamInt(params, "fast", 12);
                int slow = getParamInt(params, "slow", 26);
                int signal = getParamInt(params, "signal", 9);
                MACDIndicator macd = new MACDIndicator(src, fast, slow);
                EMAIndicator sig = new EMAIndicator(macd, signal);
                return NumericIndicator.of(macd).minus(sig);
            }
            case PPO -> {
                int fast = getParamInt(params, "fast", 12);
                int slow = getParamInt(params, "slow", 26);
                MACDIndicator macd = new MACDIndicator(src, fast, slow);
                EMAIndicator emaSlow = new EMAIndicator(src, slow);
                return NumericIndicator.of(macd).dividedBy(emaSlow).multipliedBy(100);
            }
            case ROC -> {
                int period = getParamInt(params, "period", 12);
                return new ROCIndicator(src, period);
            }

            case CCI -> {
                int period = getParamInt(params, "period", 20);
                return new CCIIndicator(series, period);
            }
            case TRIX -> {
                int period = getParamInt(params, "period", 15);
                EMAIndicator e1 = new EMAIndicator(src, period);
                EMAIndicator e2 = new EMAIndicator(e1, period);
                EMAIndicator e3 = new EMAIndicator(e2, period);
                return new ROCIndicator(e3, 1);
            }
            case KST ->
                    throw new UnsupportedOperationException("KST not implemented. Requires multi-roc weighted combination.");
            case ULTIMATE_OSC -> throw new UnsupportedOperationException("Ultimate Oscillator not implemented here.");

            case WILLIAMS_R -> {
                int period = getParamInt(params, "period", 14);
                return new WilliamsRIndicator(series, period);
            }
            case STOCH_FAST_K -> {
                int period = getParamInt(params, "period", 14);
                return new StochasticOscillatorKIndicator(series, period);
            }


            // --- Volatility/Bands
            case ATR -> {
                int period = getParamInt(params, "period", 14);
                return new ATRIndicator(series, period);
            }
            case BBANDS -> {
                int period = getParamInt(params, "period", 20);
                SMAIndicator middle = new SMAIndicator(src, period);
                // if you need full band object, implement wrapper returning all 3 bands
                return new BollingerBandsMiddleIndicator(middle);
            }
            case KELTNER -> {
                int period = getParamInt(params, "period", 20);
                return new EMAIndicator(src, period);
            }
            case DONCHIAN -> {
                int period = getParamInt(params, "period", 20);
                return new DonchianChannelMiddleIndicator(series, period);
            }

            // --- Trend & Directional
            case ADX, DMI -> {
                int period = getParamInt(params, "period", 14);
                return new ADXIndicator(series, period);
            }
            case PSAR -> throw new UnsupportedOperationException("PSAR not implemented here.");
            case SUPER_TREND -> throw new UnsupportedOperationException("SuperTrend not implemented here.");
            case ICHIMOKU -> throw new UnsupportedOperationException("Ichimoku Cloud not implemented here.");
            case AROON, AROON_UP, AROON_DOWN -> {
                int period = getParamInt(params, "period", 25);
                return new AroonDownIndicator(series, period);
            }

            // --- Volume money flow
            case OBV -> {
                return new OnBalanceVolumeIndicator(series);
            }
            case ADL -> {
                return new AccumulationDistributionIndicator(series);
            }
            case MFI -> {
                int period = getParamInt(params, "period", 14);
                return new MoneyFlowIndexIndicator(series, period);
            }
            case CMF -> {
                int period = getParamInt(params, "period", 20);
                return new ChaikinMoneyFlowIndicator(series, period);
            }

            // --- price action / pivot family
            case PIVOT_POINTS, FIBONACCI_RETRACEMENT, CAMARILLA, WOODIE_PIVOTS ->
                    throw new UnsupportedOperationException("Pivot families not implemented here.");

            // --- statistical
            case Z_SCORE -> {
                int period = getParamInt(params, "period", 14);
                StandardDeviationIndicator sd = new StandardDeviationIndicator(src, period);
                SMAIndicator sma = new SMAIndicator(src, period);
                return NumericIndicator.of(src).minus(sma).dividedBy(sd);
            }
            case STDDEV -> {
                int period = getParamInt(params, "period", 14);
                return new StandardDeviationIndicator(src, period);
            }

            // pattern placeholders
            case DOJI, HAMMER, HANGING_MAN, ENGULFING_BULL, ENGULFING_BEAR, MORNING_STAR, EVENING_STAR ->
                    throw new UnsupportedOperationException("Candlestick pattern detection should be implemented as boolean signals.");

            default -> throw new IllegalArgumentException("Unhandled indicator: " + operand.getIndicatorType());
        }
    }

    // -------------------------
    // Param helpers
    // -------------------------
    private static int getParamInt(List<Param> params, String name, int defaultValue) {
        for (Param p : params) {
            if (p.name().equalsIgnoreCase(name)) {
                try {
                    return Integer.parseInt(p.value());
                } catch (NumberFormatException ignored) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    private static double getParamDouble(List<Param> params, String name, double defaultValue) {
        for (Param p : params) {
            if (p.name().equalsIgnoreCase(name)) {
                try {
                    return Double.parseDouble(p.value());
                } catch (NumberFormatException ignored) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    private boolean getParamBoolean(List<Param> params, String name, boolean defaultValue) {
        for (Param p : params) {
            if (p.name().equalsIgnoreCase(name)) {
                return Boolean.parseBoolean(p.value());
            }
        }
        return defaultValue;
    }


    private static Indicator<Num> resolveSourceIndicator(BarSeries series, List<Source> sources) {
        if (series == null) throw new IllegalArgumentException("series must not be null");
        if (sources == null || sources.isEmpty()) {
            return new ClosePriceIndicator(series);
        }
        for (Source s : sources) {
            if (s == Source.CLOSE) return new ClosePriceIndicator(series);
        }
        Source first = sources.get(0);
        return switch (first) {
            case OPEN -> new OpenPriceIndicator(series);
            case HIGH -> new HighPriceIndicator(series);
            case LOW -> new LowPriceIndicator(series);
            case CLOSE -> new ClosePriceIndicator(series);
            case VOLUME -> new VolumeIndicator(series);
        };
    }

}
