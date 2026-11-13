package in.railops.common.security;

import in.railops.common.security.ScopeGrant.ScopeType;
import in.railops.common.security.ScopeGrant.StationPlace;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ABAC scope grants: malformed input must DENY, never widen. */
class ScopeGrantTest {

    static final StationPlace CSMT = new StationPlace("CSMT", "BB", "CR");
    static final StationPlace BRC = new StationPlace("BRC", "BRC", "WR");

    @Test
    void round_trips() {
        ScopeGrant g = new ScopeGrant("CONTROLLER", ScopeType.DIVISION, "BB");
        assertThat(ScopeGrant.parse(g.encode())).contains(g);
    }

    @Test
    void each_level_covers_only_its_own_places() {
        assertThat(ScopeGrant.parse("STATION_MASTER:STATION:CSMT").orElseThrow().covers(CSMT)).isTrue();
        assertThat(ScopeGrant.parse("STATION_MASTER:STATION:CSMT").orElseThrow().covers(BRC)).isFalse();
        assertThat(ScopeGrant.parse("CONTROLLER:DIVISION:BB").orElseThrow().covers(CSMT)).isTrue();
        assertThat(ScopeGrant.parse("CONTROLLER:ZONE:WR").orElseThrow().covers(CSMT)).isFalse();
        assertThat(ScopeGrant.parse("ADMIN:GLOBAL:*").orElseThrow().covers(BRC)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "CONTROLLER", "CONTROLLER:DIVISION", "CONTROLLER:DIVISION:", ":DIVISION:BB",
            "CONTROLLER:division:BB",          // case matters: no lenient enum parsing
            "CONTROLLER:GLOBAL:BB",            // GLOBAL with a value is ambiguous -> deny, don't treat as global
            "CONTROLLER:GLOBAL:",              // empty
            "CONTROLLER:PLANET:*",             // unknown level
            "CONTROLLER:DIVISION:BB:extra"})   // smuggled 4th part
    void malformed_grants_are_dropped(String encoded) {
        assertThat(ScopeGrant.parse(encoded)).isEmpty();
    }

    @Test
    void null_is_dropped() {
        assertThat(ScopeGrant.parse(null)).isEmpty();
    }
}
