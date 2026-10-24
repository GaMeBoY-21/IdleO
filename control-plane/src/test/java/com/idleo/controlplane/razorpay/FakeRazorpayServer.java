package com.idleo.controlplane.razorpay;

import java.net.URI;

/**
 * The fake Razorpay on its own, for the local stack and the end-to-end test (ADR 0039; scripts/lib/stack.sh starts it
 * from the test classpath, so it's never in the control plane's jar): Razorpay's API from its documented samples, the
 * browser's fake Checkout at {@code /v1/checkout.js}, and Razorpay's webhooks sent to the control plane after each
 * payment. Arguments: the port, the webhook's address, and optionally how long to wait before each webhook, in
 * milliseconds (300 by default).
 */
public final class FakeRazorpayServer {

    private FakeRazorpayServer() {}

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 2) {
            System.err.println("usage: FakeRazorpayServer <port> <webhook url> [webhook delay ms]");
            System.exit(2);
        }
        try (var fake = new FakeRazorpay(Integer.parseInt(args[0]))) {
            fake.webhookTarget = URI.create(args[1]);
            fake.webhookDelayMillis = args.length > 2 ? Long.parseLong(args[2]) : 300;
            System.out.println("fake-razorpay: serving on " + fake.url() + ", webhooks to " + args[1]);
            // Until the stack stops it.
            Thread.currentThread().join();
        }
    }
}
