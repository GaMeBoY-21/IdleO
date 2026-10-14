package com.idleo.controlplane.identity.internal;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * The shape of an invite code, like {@code IDLE-7K2Q-9XMD}: the prefix, then 8 characters from an alphabet
 * without 0, O, 1 and I. The database holds only its keyed hash ({@link CodeHashes}), of the code as {@link
 * #normalise} writes it, so a person may type it in any case, with or without the dashes.
 */
final class InviteCodes {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String PREFIX = "IDLE";
    private static final int LENGTH = 8;

    private InviteCodes() {}

    /** A new random code, written with its dashes. */
    static String generate(SecureRandom random) {
        var chars = new StringBuilder();
        for (int i = 0; i < LENGTH; i++) {
            chars.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return PREFIX + "-" + chars.substring(0, 4) + "-" + chars.substring(4);
    }

    /** Upper case, letters and digits only: what a code is compared as, whatever way it was typed. */
    static String normalise(String code) {
        var normalised = new StringBuilder();
        for (char c : code.toUpperCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                normalised.append(c);
            }
        }
        return normalised.toString();
    }
}
