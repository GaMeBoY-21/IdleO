/**
 * The scheduling module. Owns: matching sessions to hosts, and offers (ADR 0015). Its tables live in the
 * {@code scheduling} database schema, and no other module reads them.
 *
 * <p>It listens for the sessions module's {@code SessionRequested} and {@code SessionCancelled} events,
 * reads hosts, presence and sessions through their modules' public APIs, holds credit through billing's
 * {@code CreditReservations}, queues offers through the agent gateway's {@code AgentCommands}, and
 * implements the agent gateway's {@code OfferReplies} port. Every dependency points from scheduling to
 * those modules, never back.
 *
 * <p>Its hard filters (active, listed, online, current, owner away, below the temperature cap, no live session) live
 * in one place, and two endpoints read them as well as matching does (ADR 0019), which is why they live here and not
 * in hosts or sessions, which may not depend on scheduling:
 *
 * <ul>
 *   <li>{@code GET /v1/availability}: one row per GPU model and memory among online hosts, with how many are free and
 *       their prices; never a host's identity.
 *   <li>{@code POST /v1/session-quotes}, the same body as {@code POST /v1/sessions}: the hold, the available credit,
 *       and how many hosts fit now, after the sessions module's own check of the request, so a request that quotes
 *       cleanly books cleanly. It reserves nothing.
 * </ul>
 */
@ApplicationModule
package com.idleo.controlplane.scheduling;

import org.springframework.modulith.ApplicationModule;
