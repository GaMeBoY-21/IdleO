package com.idleo.controlplane.notifications.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.BrowserSignInOnly;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's notification choices. See the notifications module's package documentation. */
@RestController
@RequestMapping("/v1/me/notifications")
class NotificationsController {

    private final NotificationPreferences preferences;

    NotificationsController(NotificationPreferences preferences) {
        this.preferences = preferences;
    }

    /**
     * One kind and its two switches. {@code locked} kinds are always on, and {@code lockedReason} says why.
     */
    record NotificationItem(
            String kind,
            String title,
            boolean locked,
            @Nullable String lockedReason,
            boolean email,
            boolean inApp) {}

    record NotificationChoices(List<NotificationItem> items) {}

    /** One kind's choice, in a request. */
    record KindChoice(
            @NotNull String kind,
            @NotNull Boolean email,
            @NotNull Boolean inApp) {}

    record ReplaceRequest(@NotNull @Size(max = 50) List<@Valid @NotNull KindChoice> items) {}

    @GetMapping
    NotificationChoices get(@AuthenticationPrincipal CurrentUser user) {
        return view(preferences.read(user.id()));
    }

    /**
     * Replaces every choice. A kind left out is on again. A locked kind with a switch off is 422
     * {@code notification-locked}; an unknown or repeated kind is 400 {@code validation-failed}.
     */
    @BrowserSignInOnly
    @PutMapping
    NotificationChoices put(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody ReplaceRequest request) {
        var choices = new EnumMap<NotificationKind, NotificationPreferences.Choice>(NotificationKind.class);
        var invalid = new ArrayList<ApiProblem.FieldError>();
        var locked = new ArrayList<ApiProblem.FieldError>();
        for (int i = 0; i < request.items().size(); i++) {
            var item = request.items().get(i);
            var field = "items[" + i + "].kind";
            var kind = NotificationKind.fromSlug(item.kind());
            if (kind.isEmpty()) {
                invalid.add(new ApiProblem.FieldError(field, "isn't a kind of notification"));
            } else if (choices.containsKey(kind.get())) {
                invalid.add(new ApiProblem.FieldError(field, "is given twice"));
            } else if (kind.get().locked() && !(item.email() && item.inApp())) {
                locked.add(new ApiProblem.FieldError(
                        field, Objects.requireNonNull(kind.get().lockedReason())));
            } else {
                choices.put(kind.get(), new NotificationPreferences.Choice(item.email(), item.inApp()));
            }
        }
        if (!invalid.isEmpty()) {
            throw ApiProblem.of(Problem.VALIDATION_FAILED, "A kind is unknown or repeated.", invalid);
        }
        if (!locked.isEmpty()) {
            throw ApiProblem.of(Problem.NOTIFICATION_LOCKED, "Messages about money and safety stay on.", locked);
        }
        return view(preferences.replace(user.id(), choices));
    }

    private static NotificationChoices view(Map<NotificationKind, NotificationPreferences.Choice> choices) {
        return new NotificationChoices(NotificationKind.all().stream()
                .map(kind -> new NotificationItem(
                        kind.slug(),
                        kind.title(),
                        kind.locked(),
                        kind.lockedReason(),
                        choices.get(kind).email(),
                        choices.get(kind).inApp()))
                .toList());
    }
}
