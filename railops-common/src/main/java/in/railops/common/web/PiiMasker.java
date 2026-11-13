package in.railops.common.web;

import java.util.regex.Pattern;

/** Masks phone numbers and email addresses before a value reaches a log line or audit detail. */
public final class PiiMasker {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(\\+?91[- ]?)?([6-9]\\d{3})\\d{6}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+)");

    private PiiMasker() {
    }

    public static String mask(String input) {
        if (input == null) {
            return null;
        }
        String out = PHONE.matcher(input).replaceAll("$2******");
        return EMAIL.matcher(out).replaceAll("$1***@$2");
    }
}
