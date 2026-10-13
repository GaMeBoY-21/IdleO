package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A renter rates a session they ran (ADR 0028). Another person's session, or one that hasn't ended, is {@code
 * not-found}.
 */
@RestController
@RequestMapping("/v1/sessions/{id}/rating")
class RatingController {

    private final Ratings ratings;

    RatingController(Ratings ratings) {
        this.ratings = ratings;
    }

    /**
     * @param stars 1 to 5
     * @param comment up to 500 characters, read only by us, never by the host; empty or left out for none
     */
    record RatingRequest(
            @NotNull @Min(1) @Max(5) Integer stars,
            @Size(max = Ratings.MAX_COMMENT) @Nullable String comment) {}

    /**
     * A session's rating and whether it can be given or changed now.
     *
     * @param rateable it can be rated, or its rating changed, now
     * @param rateableUntil when that closes: 7 days after the session ended, or after the rating was given; empty if it
     *     can't be rated at all, because it didn't run for a minute
     * @param stars empty until it's rated
     * @param ratedAt when the rating was first given
     */
    record SessionRating(
            UUID sessionId,
            boolean rateable,
            @Nullable Instant rateableUntil,
            @Nullable Integer stars,
            @Nullable String comment,
            @Nullable Instant ratedAt,
            @Nullable Instant changedAt) {}

    @GetMapping
    SessionRating get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return ratings.standing(user.id(), id).map(RatingController::view).orElseThrow(RatingController::notFound);
    }

    /**
     * Rates the session, or changes its rating. 409 {@code session-not-rateable} if it didn't run for a minute, {@code
     * rating-closed} once the 7 days have passed.
     */
    @PostMapping
    SessionRating rate(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody RatingRequest request) {
        if (request.comment() != null && request.comment().strip().length() > Ratings.MAX_COMMENT) {
            throw ApiProblem.invalid("comment", "must be at most " + Ratings.MAX_COMMENT + " characters.");
        }
        return switch (ratings.rate(user.id(), id, request.stars(), request.comment())) {
            case Ratings.Outcome.Rated rated -> view(rated.standing());
            case Ratings.Outcome.NotFound _ -> throw notFound();
            case Ratings.Outcome.NotRateable _ ->
                throw ApiProblem.of(Problem.SESSION_NOT_RATEABLE, "This session didn't run for a minute.");
            case Ratings.Outcome.Closed closed ->
                throw ApiProblem.of(Problem.RATING_CLOSED, "Rating this session closed at " + closed.closedAt() + ".");
        };
    }

    private static SessionRating view(Ratings.Standing standing) {
        var rating = standing.rating();
        return new SessionRating(
                standing.sessionId(),
                standing.rateable(),
                standing.rateableUntil(),
                rating == null ? null : rating.stars(),
                rating == null ? null : rating.comment(),
                rating == null ? null : rating.ratedAt(),
                rating == null ? null : rating.changedAt());
    }

    private static ApiProblem notFound() {
        return ApiProblem.of(Problem.NOT_FOUND, "There's no ended session with that id.");
    }
}
