/**
 * The privacy module (ADR 0037). Owns: a person's view of their own data, its export, and deleting their account.
 *
 * <p>Its tables live in the {@code privacy} database schema, and no other module reads them. It reads no other
 * module's tables either: every module that keeps anything about a person declares a {@link
 * com.idleo.controlplane.PersonalData} bean, and this module asks them all, in the order docs/reference/personal-data-map.md
 * gives. Nothing depends on it.
 *
 * <ul>
 *   <li>{@code GET /v1/me/data}: what we hold about the person, by group (account, sessions, files, money, hosting,
 *       messages, reports), with counts and dates.
 *   <li>{@code POST /v1/me/exports}: "Download my data", one at a time and one a day; a job makes a zip of a JSON file
 *       a group, with links to their files that work for 7 days, stores it, and emails a link to Account. {@code GET
 *       /v1/me/exports} lists them; {@code GET /v1/me/exports/{id}/download} gives a short link to the zip, only to
 *       its owner, signed in. The zip is deleted 7 days after it's ready.
 *   <li>{@code GET/POST/DELETE /v1/me/deletion} and {@code POST /v1/me/deletion/confirm}: deleting the account, after
 *       a fresh sign-in, an emailed confirmation and a 7-day wait the person can cancel with one click ({@code POST
 *       /v1/deletion-requests/{id}/cancel}, signed out, with the link's token). Then a job erases it step by step,
 *       resumable, each step audited by id only.
 * </ul>
 */
@ApplicationModule
package com.idleo.controlplane.privacy;

import org.springframework.modulith.ApplicationModule;
