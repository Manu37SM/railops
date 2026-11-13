package in.railops.reservation.domain.policy;

import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.domain.model.Quota;

import java.util.List;

/**
 * Eligibility rules per quota. Thresholds are constructor parameters, not constants: these are
 * railway policy and change by circular, so they belong in configuration.
 */
public final class QuotaEligibilityPolicy {

    private final int seniorMaleMinAge;
    private final int seniorFemaleMinAge;
    private final int ladiesQuotaMaxChildAge;

    public QuotaEligibilityPolicy(int seniorMaleMinAge, int seniorFemaleMinAge, int ladiesQuotaMaxChildAge) {
        this.seniorMaleMinAge = seniorMaleMinAge;
        this.seniorFemaleMinAge = seniorFemaleMinAge;
        this.ladiesQuotaMaxChildAge = ladiesQuotaMaxChildAge;
    }

    public void check(Quota quota, List<PassengerSpec> passengers) {
        switch (quota) {
            case LD -> passengers.forEach(p -> {
                if (p.gender() != 'F' && p.age() > ladiesQuotaMaxChildAge) {
                    throw ineligible("ladies quota: passenger " + p.seqNo() + " is not eligible");
                }
            });
            case SS -> passengers.stream().filter(PassengerSpec::berthRequired).forEach(p -> {
                int min = p.gender() == 'F' ? seniorFemaleMinAge : seniorMaleMinAge;
                if (p.age() < min) {
                    throw ineligible("senior citizen quota: passenger " + p.seqNo() + " is under " + min);
                }
            });
            case TQ, PT -> {
                if (passengers.stream().anyMatch(p -> !p.berthRequired())) {
                    throw ineligible("tatkal bookings cannot include no-berth children");
                }
            }
            case DF, FT, HP, PQ, RL, GN -> {
                // DF/FT/HP need document checks at the counter; PQ/RL are station-pair pools
                // enforced by the journey validation, not by passenger attributes.
            }
        }
    }

    private static DomainException ineligible(String msg) {
        return new DomainException(ErrorCode.RES_QUOTA_INELIGIBLE, msg);
    }
}
