package com.ravi.stockscreener.util;

import org.springframework.data.util.Pair;

import java.time.*;
import java.time.format.DateTimeFormatter;

public class Utils {

    private static final String MINUTES = "minutes";
    private static final String HOURS = "hours";
    private static final String DAYS = "days";
    private static final String WEEKS = "weeks";
    private static final String MONTHS = "months";

    public static long computeBucketStart(long epochMillis, String timeframe) {
//        String unit = timeframeToUnit(timeframe);              // returns "minutes", "hours", "days"
//        int interval = Integer.parseInt(timeframeToInterval(timeframe)); // numeric part
//        Instant instant = Instant.ofEpochMilli(epochMillis);
//        ZonedDateTime zdt = ZonedDateTime.ofInstant(instant, HistoricalDataService.TZ_IST);
//        switch (unit) {
//            case MINUTES -> {
//                if (interval <= 0)
//                    throw new IllegalArgumentException("Invalid interval: " + interval + " for timeframe " + timeframe);
//                int minute = zdt.getMinute();
//                int bucketMinute = (minute / interval) * interval;
//                return zdt.withMinute(bucketMinute).withSecond(0).withNano(0).toEpochSecond();
//            }
//            case HOURS -> {
//                if (interval <= 0)
//                    throw new IllegalArgumentException("Invalid interval: " + interval + " for timeframe " + timeframe);
//                int hour = zdt.getHour();
//                int bucketHour = (hour / interval) * interval;
//                return zdt.withHour(bucketHour).withMinute(0).withSecond(0).withNano(0).toEpochSecond();
//            }
//            case DAYS -> {
//                // day buckets start at midnight local zone
//                return zdt.truncatedTo(ChronoUnit.DAYS).toEpochSecond();
//            }
//            default -> {
//                // fallback: treat as 1-minute bucket (shouldn't happen if timeframeToUnit is correct)
//                int minute = zdt.getMinute();
//                return zdt.withMinute(minute).withSecond(0).withNano(0).toEpochSecond();
//            }}


        Duration d = timeframeToDuration(timeframe);
        long periodMs = d.toMillis();
        return (epochMillis / periodMs) * periodMs;
    }

    public static Duration timeframeToDuration(String timeframe) {
        String unit = timeframeToUnit(timeframe);
        String interval = timeframeToInterval(timeframe);
        int amt = Integer.parseInt(interval);
        return switch (unit) {
            case MINUTES -> Duration.ofMinutes(amt);
            case HOURS -> Duration.ofHours(amt);
            case DAYS -> Duration.ofDays(amt);
            default -> throw new IllegalArgumentException("Unknown unit: " + unit);
        };
    }


    public static String timeframeToUnit(String tf) {
        // Example: "1m","5m","15m" -> "minutes"
        if (tf.endsWith(MINUTES)) return "minutes";
        if (tf.endsWith(HOURS)) return "hours";
        if (tf.endsWith(DAYS)) return "days";
        return MINUTES;
    }

    public static String timeframeToInterval(String tf) {
        // "1m" -> "1", "5m" -> "5", "1d" -> "1"
        return tf.replaceAll("[^0-9]", "");
    }

    public static Pair<String, String> computeDateRange(String timeframe) {
        String unit = timeframeToUnit(timeframe);
        String interval = timeframeToInterval(timeframe);
        int intervals = Integer.parseInt(interval);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate from;
        switch (unit.toLowerCase()) {
            case "minutes" -> {
                // 1–15m: max 1 month; >15m up to 300m: max 1 quarter
                if (intervals <= 15) {
                    from = today.minusMonths(1);
                } else {
                    from = today.minusMonths(3);
                }
            }
            case "hours" -> // hours: max 1 quarter
                    from = today.minusMonths(3);
            case "days" -> // days: max 1 decade
                    from = today.minusYears(1);
            case "weeks" -> // weeks: no limit—but available from Jan 1, 2000
                    from = LocalDate.of(2015, 1, 1);
            case "months" -> // months: no limit—but available from Jan 1, 2000
                    from = LocalDate.of(2015, 1, 1);
            default -> // fallback to 1 month
                    from = today.minusMonths(1);
        }
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        return Pair.of(from.format(fmt), today.format(fmt));
    }


}
