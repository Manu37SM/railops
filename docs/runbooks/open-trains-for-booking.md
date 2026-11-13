# Open a train for booking (timetable import)

**Who:** an admin with a nationwide grant (`ADMIN:GLOBAL:*`), signed in with MFA.
**When:** to put a train on sale for new dates, e.g. the next month.

RailOps doesn't keep its own timetables. It reads them from **RailLens**, which must be reachable (`RAILOPS_RAILLENS_BASE_URL` on the reservation service). You only choose the dates and the coaches.

## In the web app

1. Log in, then open **Run operations**. The form **Open a train for booking** is at the bottom (it only shows for nationwide admins).
2. Enter the 5-digit train number, the first and last date (at most 62 days at a time), and how many coaches of each class (2A, 3A, SL). Leave Tatkal at 10% unless you have a reason.
3. Press **Open for booking**. The first try can take up to a minute if RailLens was asleep.
4. Read the result table:
   - **Opened:** the run exists now. Its number is in the *Run* column.
   - **Already open:** that date already had a run. It was **not** changed: it may have bookings.
   - **Skipped:** the date is in the past or more than 120 days ahead.

It's safe to press the button twice. Nothing is duplicated.

## What gets created, per date

| Thing | Rule |
|---|---|
| Stops and times | From the RailLens timetable, in Indian time. Dates roll over at midnight automatically. |
| Coaches | B1, B2… (3A), A1… (2A), S1… (SL), in the order 2A, 3A, SL as entered. |
| Seats | From the coach layouts: side-lower berths are RAC (2 people each), all other berths confirmed. Tatkal gets its % of the confirmed seats, general the rest. |
| Waitlist limit | Half the seats of each quota (at least 1). |
| Booking opens | 60 days before departure. Tatkal at 10:00 the day before. Charts 4 h and 30 min before departure. |

## When it refuses

| Message | Meaning / fix |
|---|---|
| *not in the RailLens timetable* (404) | Wrong number, or RailLens doesn't have the train. Check it on the RailLens website. |
| A list of problems (422), e.g. *missing arrival time*, *implies 900 km/h* | The RailLens timetable for this train is incomplete or wrong. Fix it in RailLens (admin), then try again. |
| *RailLens could not be reached* (503) | RailLens is down or asleep. Wait a minute and retry. |
| 403 | You're not a nationwide admin, or your session has no MFA. |

## Who can then see the run

Station masters and controllers see a run when one of its stations is in their scope. That needs the station in `reservation.station_ref` (with its division and zone). RailLens doesn't supply divisions, so for a station that isn't listed there yet, **only nationwide staff** see the run. That is deliberate (fails safe). Add `station_ref` rows for the stations your staff work at.

## Changing a run that already exists

Not done by import, on purpose: bookings may exist. Cancel the run (Run operations, which refunds everyone) and import the date again, or change it by hand with a reviewed SQL change.

## Coach sets (Phase 6)

Every class can now be opened: 1A, 2A, 3A, 3E, SL, CC, EC, 2S. On the page, **Standard coach set** fills in typical
counts (Rajdhani, Duronto, Mail / Express, Shatabdi, Vande Bharat, Jan Shatabdi, Garib Rath); change any number before
opening. API clients can send `"consist": "SHATABDI"` instead of `coaches`. Real trains vary; these are RailOps' own
layouts, not Indian Railways' records.

