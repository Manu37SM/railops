-- Station hierarchy for ABAC scope checks (station -> division -> zone).
-- Reference data replicated from catalog-service (via catalog events from Phase 9; seeded until then).
CREATE TABLE reservation.station_ref (
  code          VARCHAR(5) PRIMARY KEY,
  division_code VARCHAR(6) NOT NULL,
  zone_code     VARCHAR(4) NOT NULL
);
