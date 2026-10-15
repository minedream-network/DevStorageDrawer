package com.teenkung.devstoragedrawer.text;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Locale-stable formatting for numbers shown to players and administrators. */
public final class DrawerTextFormat {
    private static final ThreadLocal<DecimalFormat> NUMBERS = ThreadLocal.withInitial(() ->
            new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US)));

    public static String value(final Object value) {
        return value instanceof Number number ? NUMBERS.get().format(number) : String.valueOf(value);
    }

    public static String number(final long value) {
        return NUMBERS.get().format(value);
    }

    private DrawerTextFormat() { }
}
