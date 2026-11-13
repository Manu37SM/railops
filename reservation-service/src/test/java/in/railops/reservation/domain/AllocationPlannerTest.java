package in.railops.reservation.domain;

import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.domain.model.PassengerStatus;
import in.railops.reservation.domain.policy.AllocationPlanner;
import in.railops.reservation.domain.policy.AllocationPlanner.Plan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllocationPlannerTest {

    private final AllocationPlanner planner = new AllocationPlanner();

    private static PassengerSpec adult(int seq) {
        return new PassengerSpec(seq, "P" + seq, 30, 'M', null, true);
    }

    @Test
    void fillsCnfThenRacThenWlInSeqOrder() {
        Plan plan = planner.plan(List.of(adult(3), adult(1), adult(2), adult(4)), 1, 2, false);

        assertThat(plan.passengers()).extracting(p -> p.spec().seqNo() + ":" + p.status())
                .containsExactly("1:CNF", "2:RAC", "3:RAC", "4:WL");
        assertThat(plan.cnfCount()).isEqualTo(1);
        assertThat(plan.racSlots()).isEqualTo(2);
        assertThat(plan.wlCount()).isEqualTo(1);
        assertThat(plan.hasShortage()).isTrue();
    }

    @Test
    void noBerthChildConsumesNothing() {
        var child = new PassengerSpec(2, "Kid", 3, 'F', null, false);
        Plan plan = planner.plan(List.of(adult(1), child), 1, 0, true);

        assertThat(plan.passengers()).extracting(AllocationPlanner.Planned::status)
                .containsExactly(PassengerStatus.CNF, PassengerStatus.NOSB);
        assertThat(plan.hasShortage()).isFalse();
    }

    @Test
    void onlyIfConfirmedRejectsPartialConfirmation() {
        assertThatThrownBy(() -> planner.plan(List.of(adult(1), adult(2)), 1, 5, true))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.RES_ONLY_IF_CNF_UNMET);
    }

    @Test
    void bookingOfOnlyNoBerthChildrenIsInvalid() {
        var child = new PassengerSpec(1, "Kid", 2, 'M', null, false);
        assertThatThrownBy(() -> planner.plan(List.of(child), 5, 5, false))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void noBerthOnlyAllowedUnderFive() {
        assertThatThrownBy(() -> new PassengerSpec(1, "Teen", 12, 'M', null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
