package in.railops.reservation.domain;

import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.model.BookingStatus;
import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.SegmentRange;
import in.railops.reservation.domain.model.TravelClass;
import in.railops.reservation.domain.policy.BerthPreferencePolicy;
import in.railops.reservation.domain.policy.PnrGenerator;
import in.railops.reservation.domain.policy.QuotaEligibilityPolicy;
import in.railops.reservation.security.ContactProtector;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainPolicyTest {

    @Test
    void segmentRangesAreHalfOpen() {
        assertThat(new SegmentRange(0, 3).overlaps(new SegmentRange(3, 5))).isFalse(); // alight where other boards
        assertThat(new SegmentRange(0, 3).overlaps(new SegmentRange(2, 5))).isTrue();
        assertThatThrownBy(() -> new SegmentRange(4, 4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generatedPnrsAreValidAndTyposAreCaught() {
        PnrGenerator gen = new PnrGenerator(new SecureRandom());
        IntStream.range(0, 1000).mapToObj(i -> gen.next()).forEach(p -> {
            assertThat(p).hasSize(10);
            assertThat(PnrGenerator.isValid(p)).isTrue();
        });
        String pnr = gen.next();
        char last = pnr.charAt(9);
        String typo = pnr.substring(0, 9) + (char) ('0' + ((last - '0' + 1) % 10));
        assertThat(PnrGenerator.isValid(typo)).isFalse();
        assertThat(PnrGenerator.isValid("12345")).isFalse();
    }

    @Test
    void seniorsAndWomen45PlusChooseFirstAndPreferLowerBerths() {
        var policy = new BerthPreferencePolicy();
        var young = new PassengerSpec(1, "A", 25, 'M', "UB", true);
        var senior = new PassengerSpec(2, "B", 67, 'M', null, true);
        var woman = new PassengerSpec(3, "C", 46, 'F', "SL", true);

        assertThat(List.of(young, senior, woman).stream().sorted(policy.pickOrder()).map(PassengerSpec::seqNo))
                .containsExactly(2, 3, 1);
        assertThat(policy.preferredTypes(woman, TravelClass.THIRD_AC)).containsExactly("LB", "SL");
        assertThat(policy.preferredTypes(young, TravelClass.CHAIR_CAR)).isEmpty(); // UB meaningless when seated
    }

    @Test
    void quotaEligibility() {
        var policy = new QuotaEligibilityPolicy(60, 58, 11);
        policy.check(Quota.LD, List.of(new PassengerSpec(1, "Mother", 34, 'F', null, true),
                new PassengerSpec(2, "Son", 9, 'M', null, true)));
        assertThatThrownBy(() -> policy.check(Quota.LD, List.of(new PassengerSpec(1, "Man", 30, 'M', null, true))))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> policy.check(Quota.SS, List.of(new PassengerSpec(1, "X", 59, 'M', null, true))))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void bookingTransitions() {
        assertThat(BookingStatus.EXPIRED.canMoveTo(BookingStatus.CONFIRMED)).isTrue(); // late webhook path
        assertThat(BookingStatus.CANCELLED.canMoveTo(BookingStatus.CONFIRMED)).isFalse();
        assertThatThrownBy(() -> BookingStatus.CONFIRMED.requireTransition(BookingStatus.PENDING_PAYMENT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void contactEncryptionRoundTripsAndDetectsTampering() {
        var cipher = new in.railops.common.crypto.FieldCipher(2, "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
                .addKey(1, "HxwdGhkYFxYVFBMSERAPDg0MCwoJCAcGBQQDAgEAAQI=");
        var cp = new ContactProtector(cipher, "HxwdGhkYFxYVFBMSERAPDg0MCwoJCAcGBQQDAgEAAQI=");
        byte[] a = cp.encryptPhone("9820012345");
        byte[] b = cp.encryptPhone("9820012345");
        assertThat(a).isNotEqualTo(b); // random IV per value
        assertThat(a[0]).isEqualTo((byte) 2); // current key id
        assertThat(cp.decryptPhone(a)).isEqualTo("9820012345");
        assertThat(cp.phoneLookupToken("+91 98200 12345")).isEqualTo(cp.phoneLookupToken("9820012345"));
        // AAD binds ciphertext to its column: a phone blob can't be decrypted as an email.
        assertThatThrownBy(() -> cp.decryptEmail(a)).isInstanceOf(IllegalStateException.class);
        a[a.length - 1] ^= 1;
        assertThatThrownBy(() -> cp.decryptPhone(a)).isInstanceOf(IllegalStateException.class);
    }
}
