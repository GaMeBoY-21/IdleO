package com.idleo.controlplane.identity;

import java.util.Map;

/**
 * The admin console's dashboard (ADR 0027): each module counts its own figures and the identity module shows them
 * together. Identity declares this port and the modules that own the figures implement it, so the dependency points
 * at identity, never from it; nothing has to depend on metering. Every figure comes from exactly one module, which
 * gives it as null when it can't be counted now (hosts online while presence can't be read): unknown, never zero.
 */
public interface DashboardFigures {

    enum Figure {
        /** Hosts with a live agent stream now. */
        HOSTS_ONLINE,
        /** Sessions running now, including those stopping. */
        SESSIONS_RUNNING,
        /** Sessions booked since midnight in India. */
        SESSIONS_TODAY,
        /** Sessions whose settlement is due and hasn't run: a window past due, or ended and not yet closed. */
        SETTLEMENT_BACKLOG,
        /** What the latest wallet reconciliation found wrong; zero before the first run. */
        RECONCILIATION_MISMATCHES,
        /** Minutes in the review queue that nobody has charged or waived yet. */
        REVIEW_QUEUE
    }

    /** This module's figures, now; null for one that can't be counted now. */
    Map<Figure, Long> figures();
}
