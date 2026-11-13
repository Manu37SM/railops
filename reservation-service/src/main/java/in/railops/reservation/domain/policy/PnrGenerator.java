package in.railops.reservation.domain.policy;

import java.security.SecureRandom;

/**
 * Random 9-digit body + Luhn check digit. Random (not sequential) so PNRs can't be walked;
 * the check digit rejects most typos before a database lookup.
 */
public final class PnrGenerator {

    private final SecureRandom random;

    public PnrGenerator(SecureRandom random) {
        this.random = random;
    }

    public String next() {
        // First digit 1-9: keeps the PNR at a fixed 10 digits with no leading zero.
        long body = 100_000_000L + (long) (random.nextDouble() * 900_000_000L);
        String digits = Long.toString(body);
        return digits + luhnCheckDigit(digits);
    }

    public static boolean isValid(String pnr) {
        if (pnr == null || !pnr.matches("[1-9]\\d{9}")) {
            return false;
        }
        return luhnCheckDigit(pnr.substring(0, 9)) == pnr.charAt(9) - '0';
    }

    static int luhnCheckDigit(String digits) {
        int sum = 0;
        boolean dbl = true; // rightmost body digit is doubled when the check digit is appended
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return (10 - (sum % 10)) % 10;
    }
}
