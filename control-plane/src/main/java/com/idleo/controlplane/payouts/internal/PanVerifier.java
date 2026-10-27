package com.idleo.controlplane.payouts.internal;

/**
 * Checks a PAN online (ADR 0040): that the Income Tax Department has it, and that the name given matches its. One
 * small interface, so the provider the product owner chooses is one class: none is integrated yet ({@link ManualPanVerifier}
 * leaves each to an admin), and the tests and the local stack use {@link FakePanVerifier}. ADR 0040 lists three
 * providers to choose from.
 */
interface PanVerifier {

    enum Result {
        /** The PAN exists and the name matches it. */
        VERIFIED,
        /** The PAN exists, under another name: an admin looks. */
        NAME_MISMATCH,
        /** No such PAN. */
        INVALID,
        /** Not checked online: an admin confirms it by hand. */
        NOT_CHECKED
    }

    /** How the record names what checked it: {@code manual}, {@code fake}, or the provider. */
    String name();

    Result verify(String pan, String name);
}
