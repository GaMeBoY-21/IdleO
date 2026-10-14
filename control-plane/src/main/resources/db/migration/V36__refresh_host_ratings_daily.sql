-- What a host's owner sees of its ratings (ADR 0028), worked out once a day rather than on each rating, so a change
-- in the average or the count right after a session can't tell a host how that one renter rated it. The count is
-- kept rounded down to a multiple of 5 ("20+ ratings"), and a host appears only from 5 ratings.
CREATE TABLE trust.host_ratings (
    host_id        uuid         PRIMARY KEY,
    average_stars  numeric(2,1) NOT NULL CHECK (average_stars BETWEEN 1 AND 5),
    count_at_least integer      NOT NULL CHECK (count_at_least >= 5 AND count_at_least % 5 = 0),
    refreshed_at   timestamptz  NOT NULL
);
