package com.idleo.controlplane.payouts.internal;

/** No PAN provider yet (ADR 0040): every PAN waits for an admin to confirm it by hand, with a reason, audited. */
final class ManualPanVerifier implements PanVerifier {

    @Override
    public String name() {
        return "manual";
    }

    @Override
    public Result verify(String pan, String name) {
        return Result.NOT_CHECKED;
    }
}
