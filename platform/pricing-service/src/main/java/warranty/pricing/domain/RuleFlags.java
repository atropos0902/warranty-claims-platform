package warranty.pricing.domain;

/**
 * Where the policy manual and the old code disagree. Each flag switches one rule to the manual's
 * version. All of them stay off until the matching ADR is signed.
 */
public record RuleFlags(
        boolean capBeforeRounding,
        boolean holdMissingMeter,
        boolean handlingLimitPerClaim,
        boolean rejectFailureBeforePurchase) {

    public static final RuleFlags LEGACY = new RuleFlags(false, false, false, false);
}
