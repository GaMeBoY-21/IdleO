package com.idleo.controlplane.notifications.internal;

/**
 * What a person is told: a short title, a body of a sentence or two in plain words, and where it leads, a path in
 * the web app.
 */
record Message(String title, String body, String link) {}
