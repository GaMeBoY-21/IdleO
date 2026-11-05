package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AccountLinks;
import com.idleo.controlplane.PersonalDataMask;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Identity's signals for linked accounts (ADR 0052): the same browser from the same address, the same network within a
 * day of each other, and the same email once its dots and +tags are gone.
 */
@Component
class IdentityAccountLinks implements AccountLinks {

    private static final Duration LOOKING_BACK = Duration.ofDays(90);
    private static final int MOST = 50;

    /** How many of a person's latest sign-ins are compared: a busy account stays as quick to read as a quiet one. */
    private static final int RECENT_SIGN_INS = 50;

    private final JdbcClient jdbc;
    private final Clock clock;

    IdentityAccountLinks(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public List<AccountLink> linkedTo(UUID user) {
        var links = new ArrayList<AccountLink>();
        var since = Timestamp.from(Instant.now(clock).minus(LOOKING_BACK));
        links.addAll(jdbc.sql("""
                        select other.user_id, max(other.at) as at
                        from (select device, address from identity.security_events
                              where user_id = :user and device is not null and at > :since
                              order by at desc
                              limit :recent) mine
                        join identity.security_events other
                          on other.device = mine.device and other.address = mine.address
                         and other.user_id <> :user and other.at > :since
                        group by other.user_id
                        order by at desc
                        limit :most
                        """)
                .param("user", user)
                .param("since", since)
                .param("recent", RECENT_SIGN_INS)
                .param("most", MOST)
                .query((rs, n) -> new AccountLink(
                        rs.getObject("user_id", UUID.class),
                        "same_device",
                        Strength.STRONG,
                        "The same browser from the same address",
                        rs.getTimestamp("at").toInstant()))
                .list());
        // Only real networks: a machine's own loopback or a private range says nothing about who's behind it.
        links.addAll(jdbc.sql("""
                        select other.user_id, host(network(set_masklen(other.address, 24))) as network, max(other.at) as at
                        from (select address, at from identity.security_events
                              where user_id = :user and at > :since and family(address) = 4
                                and not (address << inet '127.0.0.0/8' or address << inet '10.0.0.0/8'
                                         or address << inet '172.16.0.0/12' or address << inet '192.168.0.0/16')
                              order by at desc
                              limit :recent) mine
                        join identity.security_events other
                          on family(other.address) = 4
                         and set_masklen(other.address, 24) = set_masklen(mine.address, 24)
                         and other.user_id <> :user
                         and other.at between mine.at - interval '24 hours' and mine.at + interval '24 hours'
                        group by other.user_id, network
                        order by at desc
                        limit :most
                        """)
                .param("user", user)
                .param("since", since)
                .param("recent", RECENT_SIGN_INS)
                .param("most", MOST)
                .query((rs, n) -> new AccountLink(
                        rs.getObject("user_id", UUID.class),
                        "same_network",
                        Strength.WEAK,
                        "The same network, " + PersonalDataMask.ip(rs.getString("network")) + ", within a day",
                        rs.getTimestamp("at").toInstant()))
                .list());
        links.addAll(jdbc.sql("""
                        select other.id
                        from identity.users mine
                        join identity.users other on other.email_canonical = mine.email_canonical and other.id <> mine.id
                        where mine.id = :user
                        limit :most
                        """)
                .param("user", user)
                .param("most", MOST)
                .query((rs, n) -> new AccountLink(
                        rs.getObject("id", UUID.class),
                        "same_email",
                        Strength.STRONG,
                        "The same email, once its dots and +tags are gone",
                        null))
                .list());
        return links;
    }
}
