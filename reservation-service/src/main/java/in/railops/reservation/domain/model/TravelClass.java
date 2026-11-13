package in.railops.reservation.domain.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TravelClass {
    FIRST_AC("1A", false), SECOND_AC("2A", false), THIRD_AC("3A", false), THIRD_AC_ECONOMY("3E", false),
    SLEEPER("SL", false), CHAIR_CAR("CC", true), EXECUTIVE_CHAIR("EC", true), SECOND_SITTING("2S", true);

    public final String code;
    public final boolean seated;

    TravelClass(String code, boolean seated) {
        this.code = code;
        this.seated = seated;
    }

    @JsonValue
    public String code() {
        return code;
    }

    @JsonCreator
    public static TravelClass fromCode(String code) {
        for (TravelClass c : values()) {
            if (c.code.equals(code)) {
                return c;
            }
        }
        throw new IllegalArgumentException("unknown class " + code);
    }
}
