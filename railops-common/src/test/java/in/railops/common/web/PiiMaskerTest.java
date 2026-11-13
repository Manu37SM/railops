package in.railops.common.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Log masking. A miss here is a phone number in a log aggregator with broad read access. */
class PiiMaskerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "call 9820012345 now|call 9820****** now",
            "+91 9820012345|9820******",
            "+91-9820012345|9820******",
            "919820012345|9820******",
            "sana.qureshi@example.com|s***@example.com",
            "pnr 2400000018 ok|pnr 2400000018 ok",             // PNRs start 1-5 here: not a mobile, must stay readable
            "ref 59820012345678|ref 59820012345678",           // digits inside a longer number are not a phone
            "1234567890|1234567890"})                          // Indian mobiles start 6-9
    void masks_phones_and_emails_but_not_other_numbers(String in, String out) {
        assertThat(PiiMasker.mask(in)).isEqualTo(out);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"9820012345, 9930054321|9820******, 9930******"})
    void masks_every_occurrence(String in, String out) {
        assertThat(PiiMasker.mask(in)).isEqualTo(out);
    }
}
