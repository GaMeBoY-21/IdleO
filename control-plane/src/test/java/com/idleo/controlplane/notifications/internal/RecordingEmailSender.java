package com.idleo.controlplane.notifications.internal;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** What the tests use instead of SMTP: it keeps every email, and can refuse the next few. */
@Component
@ConditionalOnProperty(name = "notifications.email.provider", havingValue = "recording")
public class RecordingEmailSender implements EmailSender {

    /** An email as the tests read it. */
    public record Sent(String to, String subject, String text, String html) {}

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger refusals = new AtomicInteger();

    @Override
    public void send(Email email) {
        if (refusals.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
            throw new EmailFailedException("refused for the test", new RuntimeException("no"));
        }
        sent.add(new Sent(email.to(), email.subject(), email.text(), email.html()));
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    /** The mail server refuses the next {@code times} emails. */
    public void refuseNext(int times) {
        refusals.set(times);
    }

    public void clear() {
        sent.clear();
        refusals.set(0);
    }
}
