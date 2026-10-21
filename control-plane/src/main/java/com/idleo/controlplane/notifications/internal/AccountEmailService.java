package com.idleo.controlplane.notifications.internal;

import com.idleo.controlplane.notifications.AccountEmails;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class AccountEmailService implements AccountEmails {

    private final Notifier notifier;
    private final EmailContent content;
    private final EmailSender sender;

    AccountEmailService(Notifier notifier, EmailContent content, EmailSender sender) {
        this.notifier = notifier;
        this.content = content;
        this.sender = sender;
    }

    @Override
    public void tell(UUID userId, String dedupeKey, String title, String body, String link) {
        notifier.tell(userId, NotificationKind.ACCOUNT_AND_DATA, dedupeKey, new Message(title, body, link));
    }

    @Override
    public void sendOnce(String to, UUID messageId, String title, String body, String link) {
        sender.send(content.direct(to, new Message(title, body, link), messageId));
    }
}
