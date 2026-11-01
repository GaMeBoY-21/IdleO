package com.idleo.controlplane;

import java.util.ArrayList;
import java.util.List;
import org.springframework.test.web.servlet.MvcResult;

/**
 * What an open Server-Sent Events response has sent so far, as a client reads it: only whole events. The server
 * writes an event's lines one after another, so the body can end part way through one; that one isn't sent yet.
 */
public final class EventStreams {

    private EventStreams() {}

    /** An event as the stream sent it: its name and its data lines joined. */
    public record Event(String name, String data) {}

    /** The whole events the response has sent so far, oldest first. */
    public static List<Event> events(MvcResult stream) throws Exception {
        var body = stream.getResponse().getContentAsString();
        var whole = body.substring(0, body.lastIndexOf("\n\n") + 1);
        var events = new ArrayList<Event>();
        for (var block : whole.split("\n\n")) {
            String name = null;
            var data = new StringBuilder();
            for (var line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).strip());
                }
            }
            if (name != null) {
                events.add(new Event(name, data.toString()));
            }
        }
        return events;
    }
}
