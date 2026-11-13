package in.railops.identity.raillens;

import java.util.List;
import java.util.UUID;

/** RailLens' side of the account move (Phase 4C): its admin export and link endpoints, called as the admin. */
public interface RailLensAccounts {

    record Pending(long raillensUserId, String email, String passwordHash, boolean admin) {
    }

    record Link(long raillensUserId, UUID railopsUserId) {
    }

    record LinkResult(int linked, int alreadyLinked, List<Long> conflicts) {
    }

    List<Pending> pending(long afterId, int limit, String adminBearer);

    LinkResult link(List<Link> links, String adminBearer);
}
