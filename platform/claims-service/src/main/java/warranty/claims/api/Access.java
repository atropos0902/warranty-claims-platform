package warranty.claims.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import warranty.commons.ApiException;

/** Dealers see their own group. Adjusters, finance, operations and services with the read scope see all of them. */
final class Access {
    private static final String GROUP = "ROLE_GROUP_";

    private Access() {}

    /** The one group a caller is limited to, or null for a caller who may see every group. */
    static String limit(Authentication who) {
        boolean wide = who.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADJUSTER") || a.equals("ROLE_FINANCE") || a.equals("ROLE_OPS")
                        || a.equals("SCOPE_claims/read"));
        if (wide) {
            return null;
        }
        return who.getAuthorities().stream().map(GrantedAuthority::getAuthority).filter(a -> a.startsWith(GROUP))
                .map(a -> a.substring(GROUP.length())).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NO_GROUP", "This account has no dealer group"));
    }

    /** Someone else's claim looks the same as a missing one. */
    static void check(Authentication who, String groupCode) {
        String limit = limit(who);
        if (limit != null && !limit.equals(groupCode)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such claim or dealer");
        }
    }
}
