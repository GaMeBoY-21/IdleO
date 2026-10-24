package com.idleo.controlplane.razorpay;

import java.net.URI;

/**
 * The fake RazorpayX on its own, for the local stack and the end-to-end test (ADR 0040; scripts/lib/stack.sh starts it
 * from the test classpath, so it's never in the control plane's jar): RazorpayX's API from its documented samples, each
 * new payout moved to the end its UPI ID steers it to ({@code payoutfails@…} fails, {@code reversed@…} is processed and
 * then reversed, anything else is processed), with RazorpayX's webhooks sent to the control plane. Arguments: the port,
 * the webhook's address, and optionally how long to wait before each webhook, in milliseconds (300 by default).
 */
public final class FakeRazorpayXServer {

    private FakeRazorpayXServer() {}

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 2) {
            System.err.println("usage: FakeRazorpayXServer <port> <webhook url> [webhook delay ms]");
            System.exit(2);
        }
        try (var fake = new FakeRazorpayX(Integer.parseInt(args[0]))) {
            fake.webhookTarget = URI.create(args[1]);
            fake.webhookDelayMillis = args.length > 2 ? Long.parseLong(args[2]) : 300;
            fake.autoSettle = true;
            System.out.println("fake-razorpayx: serving on " + fake.url() + ", webhooks to " + args[1]);
            // Until the stack stops it.
            Thread.currentThread().join();
        }
    }
}
