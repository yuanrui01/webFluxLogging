package org.hypnos.webflux.utils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class TimeUtil {

    private TimeUtil() {}

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");


    public static String getFormatStrFromMillis(long milliseconds) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(milliseconds), ZoneId.systemDefault());
        return dateTime.format(FORMATTER);
    }
}
