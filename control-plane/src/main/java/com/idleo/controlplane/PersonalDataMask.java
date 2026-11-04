package com.idleo.controlplane;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * The one masking function for people's data in the admin console (ADR 0051). Every admin response passes through it,
 * so an admin reads {@code ni•••@gmail.com}, not the address.
 */
public final class PersonalDataMask {

    private static final String DOTS = "•••";

    private PersonalDataMask() {}

    /** The address's network: a /24 of IPv4, a /48 of IPv6; {@code •••} for what isn't an address. */
    public static String ip(String address) {
        var text = address.strip();
        if (!text.contains(":") && !text.matches("[0-9.]+")) {
            return DOTS;
        }
        try {
            // A literal, so this never looks a name up.
            var bytes = InetAddress.getByName(text).getAddress();
            if (bytes.length == 4) {
                return (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff) + ".0/24";
            }
            return String.format(
                            Locale.ROOT,
                            "%x:%x:%x::",
                            ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff),
                            ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff),
                            ((bytes[4] & 0xff) << 8) | (bytes[5] & 0xff))
                    + "/48";
        } catch (UnknownHostException e) {
            return DOTS;
        }
    }
}
