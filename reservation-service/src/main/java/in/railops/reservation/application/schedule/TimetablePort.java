package in.railops.reservation.application.schedule;

import in.railops.reservation.domain.policy.RunPlanner.Timetable;

import java.util.Optional;

/** Where published timetables come from (RailLens). Empty when the train doesn't exist there. */
public interface TimetablePort {

    Optional<Timetable> fetch(String trainNo);
}
