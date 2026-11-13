package in.railops.reservation.security;

import java.util.UUID;

/**
 * Who is calling. Phase 6 provides the JWT-backed implementation. Until then only the "local" profile
 * has an implementation, so a non-local deployment without Phase 6 fails at startup instead of
 * silently trusting a header.
 */
public interface CurrentUserProvider {

    UUID requireUserId();
}
