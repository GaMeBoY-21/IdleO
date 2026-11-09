package com.idleo.controlplane.notifications.internal;

/**
 * What a person is told: a short title, a body of a sentence or two in plain words, and where it leads, a path on one
 * of the web app's sites (ADR 0058): the renter app's, or the host site's for a host's own laptops, earnings and payouts.
 */
record Message(String title, String body, String link, Site site) {

    /** The site a message's link is a path on. */
    enum Site {
        APP("app"),
        HOST("host");

        private final String code;

        Site(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }

        static Site of(String code) {
            return code.equals(HOST.code) ? HOST : APP;
        }
    }

    /** A message whose link is a path on the renter app. */
    Message(String title, String body, String link) {
        this(title, body, link, Site.APP);
    }

    /** The same, leading to a path on the host site. */
    Message onHostSite() {
        return new Message(title, body, link, Site.HOST);
    }
}
