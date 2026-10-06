/**
 * The scheduling module. Owns: matching sessions to hosts, and offers (ADR 0015). Its tables live in the
 * {@code scheduling} database schema, and no other module reads them.
 *
 * <p>It listens for the sessions module's {@code SessionRequested} and {@code SessionCancelled} events,
 * reads hosts, presence and sessions through their modules' public APIs, holds credit through billing's
 * {@code CreditReservations}, queues offers through the agent gateway's {@code AgentCommands}, and
 * implements the agent gateway's {@code OfferReplies} port. Every dependency points from scheduling to
 * those modules, never back.
 */
@ApplicationModule
package com.idleo.controlplane.scheduling;

import org.springframework.modulith.ApplicationModule;
