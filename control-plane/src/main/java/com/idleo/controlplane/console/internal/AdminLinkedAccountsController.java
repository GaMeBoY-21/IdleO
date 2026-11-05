package com.idleo.controlplane.console.internal;

import com.idleo.controlplane.AccountLinks;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.RequiresPermission;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accounts that may be the same person or ring (ADR 0052), for Trust &amp; Safety only. Each signal has its evidence and
 * strength, worked out on the read by the module that holds it. It's a lead for a person to look at: nothing here acts.
 */
@RestController
@ConditionalOnWebApplication
class AdminLinkedAccountsController {

    private final List<AccountLinks> modules;
    private final ConsoleSearchProperties properties;
    private final ConsoleFanOut fanOut;

    AdminLinkedAccountsController(
            List<AccountLinks> modules, ConsoleSearchProperties properties, ConsoleFanOut fanOut) {
        this.modules = modules;
        this.properties = properties;
        this.fanOut = fanOut;
    }

    /** One other account, and everything that links it, strongest first. */
    record LinkedAccount(UUID userId, List<AccountLinks.AccountLink> signals) {}

    /**
     * @param unanswered how many modules' signals didn't arrive in time, so the list may be short
     */
    record LinkedAccounts(List<LinkedAccount> accounts, int unanswered) {}

    @RequiresPermission(AdminPermission.TRUST_READ)
    @GetMapping("/admin/v1/users/{id}/linked-accounts")
    LinkedAccounts linked(@PathVariable UUID id) {
        var questions = new LinkedHashMap<String, Callable<List<AccountLinks.AccountLink>>>();
        modules.forEach(module -> questions.put(module.getClass().getSimpleName(), () -> module.linkedTo(id)));
        var answers = fanOut.ask(questions, properties.providerTimeout());
        var byAccount = new LinkedHashMap<UUID, java.util.ArrayList<AccountLinks.AccountLink>>();
        answers.answered()
                .values()
                .forEach(links -> links.forEach(link -> byAccount
                        .computeIfAbsent(link.otherUser(), other -> new java.util.ArrayList<>())
                        .add(link)));
        var accounts = byAccount.entrySet().stream()
                .map(entry -> new LinkedAccount(
                        entry.getKey(),
                        entry.getValue().stream()
                                .sorted(Comparator.comparing(AccountLinks.AccountLink::strength))
                                .toList()))
                .sorted(Comparator.comparing(
                        account -> account.signals().getFirst().strength()))
                .toList();
        return new LinkedAccounts(accounts, answers.unanswered().size());
    }
}
