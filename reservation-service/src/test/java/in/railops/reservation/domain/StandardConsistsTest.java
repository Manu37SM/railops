package in.railops.reservation.domain;

import in.railops.reservation.domain.model.TravelClass;
import in.railops.reservation.domain.policy.RunPlanner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 6: every class can be opened, and every standard coach set fits a train. */
class StandardConsistsTest {

    @Test
    void every_class_has_a_coach_kind() {
        for (TravelClass c : TravelClass.values()) {
            assertThat(RunPlanner.COACH_KINDS).as(c.code).containsKey(c);
        }
        assertThat(RunPlanner.COACH_KINDS.values().stream().map(RunPlanner.CoachKind::labelPrefix).distinct().count())
                .as("coach label prefixes are unique").isEqualTo(RunPlanner.COACH_KINDS.size());
    }

    @Test
    void standard_sets_fit_the_coach_limit_and_list_each_class_once() {
        RunPlanner.STANDARD_CONSISTS.forEach((name, set) -> {
            assertThat(set.stream().mapToInt(RunPlanner.CoachSpec::count).sum()).as(name).isBetween(1, 24);
            assertThat(set.stream().map(RunPlanner.CoachSpec::travelClass).distinct().count()).as(name).isEqualTo(set.size());
        });
    }
}
