package com.idleo.controlplane.payouts.internal;

/**
 * The tests' and the local stack's PAN check (ADR 0040; {@code payouts.pan.verifier: fake}, which the prod profile
 * refuses). Any well-formed PAN is verified under the name given, except two kinds kept for the tests and the runbook:
 * digits {@code 0000} (ABCPE0000F) are no PAN at all, and digits {@code 9999} (ABCPE9999F) are another person's.
 */
final class FakePanVerifier implements PanVerifier {

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public Result verify(String pan, String name) {
        var digits = pan.substring(5, 9);
        return switch (digits) {
            case "0000" -> Result.INVALID;
            case "9999" -> Result.NAME_MISMATCH;
            default -> Result.VERIFIED;
        };
    }
}
