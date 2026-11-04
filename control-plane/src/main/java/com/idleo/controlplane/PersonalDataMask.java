package com.idleo.controlplane;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * The one masking function for people's data in the admin console (ADR 0051). {@code AdminResponseMasking} passes every
 * admin response through {@link #json}, so what an admin reads is masked however the endpoint was written and whatever a
 * person typed into a free-text field: an email {@code ni•••@gmail.com}, a phone its last 2 digits, a PAN and bank
 * details their last 4, a UPI ID {@code ni•••@okhdfc}, an IP its /24 or /48, and a postal address nothing at all. An
 * admin with {@code users.pii.reveal} reads one whole value, with a reason (ADR 0051).
 */
public final class PersonalDataMask {

    private static final String DOTS = "•••";

    private static final String EMAIL_PART =
            "[\\p{L}\\p{N}._%+'-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.[\\p{L}]{2,}";
    private static final String UPI_PART = "(?<![\\w.@/:-])[\\w.-]+@[a-zA-Z][a-zA-Z0-9]{1,30}(?![\\w.:/@-])";
    private static final String PAN_PART = "\\b[A-Z]{5}[0-9]{4}[A-Z]\\b";
    private static final String PHONE_PART = "(?<![\\w+.-])(?:\\+?91[ -]?)?[6-9][0-9]{4}[ -]?[0-9]{5}(?![\\w-])";
    private static final String IPV4_PART = "\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b";
    private static final String IPV6_PART =
            "\\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\\b|\\b(?:[0-9a-fA-F]{1,4}:){1,6}:(?:[0-9a-fA-F]{1,4})?(?![0-9a-fA-F:])";

    /** Anything in free text that is one of these, in the order they're tried. */
    private static final Pattern IN_TEXT = Pattern.compile("(?<email>" + EMAIL_PART + ")|(?<upi>" + UPI_PART
            + ")|(?<pan>" + PAN_PART + ")|(?<phone>" + PHONE_PART + ")|(?<ip>" + IPV4_PART + "|" + IPV6_PART + ")");

    /** A field that holds this kind of value, whatever it looks like. */
    private enum Kind {
        PHONE,
        PAN,
        UPI,
        BANK,
        HIDDEN
    }

    private static final Map<String, Kind> FIELDS = Map.ofEntries(
            Map.entry("phone", Kind.PHONE),
            Map.entry("mobile", Kind.PHONE),
            Map.entry("pan", Kind.PAN),
            Map.entry("vpa", Kind.UPI),
            Map.entry("upi", Kind.UPI),
            Map.entry("accountnumber", Kind.BANK),
            Map.entry("bankaccount", Kind.BANK),
            Map.entry("ifsc", Kind.BANK),
            Map.entry("postaladdress", Kind.HIDDEN),
            Map.entry("billingaddress", Kind.HIDDEN),
            Map.entry("street", Kind.HIDDEN),
            Map.entry("pincode", Kind.HIDDEN));

    /** What only looks like a person's data: machine values no one types, which a mask would make useless. */
    private static final Set<String> TECHNICAL = Set.of("useragent", "reference", "digest", "sha256", "otpauthuri");

    private PersonalDataMask() {}

    /** {@code ni•••@gmail.com}. */
    public static String email(String email) {
        return beforeTheAt(email);
    }

    /** {@code ni•••@okhdfc}. */
    public static String upi(String vpa) {
        return beforeTheAt(vpa);
    }

    private static String beforeTheAt(String value) {
        var at = value.lastIndexOf('@');
        if (at < 0) {
            return DOTS;
        }
        return value.substring(0, Math.min(2, at)) + DOTS + value.substring(at);
    }

    /** The last 2 digits. */
    public static String phone(String phone) {
        return lastOf(phone.replaceAll("[^0-9]", ""), 2);
    }

    /** The last 4 characters. */
    public static String pan(String pan) {
        return lastOf(pan.strip(), 4);
    }

    /** The last 4 characters. */
    public static String bank(String account) {
        return lastOf(account.strip(), 4);
    }

    private static String lastOf(String value, int shown) {
        return value.length() <= shown ? DOTS : DOTS + value.substring(value.length() - shown);
    }

    /** The address's network: a /24 of IPv4, a /48 of IPv6; {@code •••} for what isn't an address. */
    public static String ip(String address) {
        var text = address.strip();
        if (!text.contains(":") && !text.matches("[0-9.]+")) {
            return DOTS;
        }
        try {
            // A literal, so this never looks a name up.
            var bytes = InetAddress.getByName(text).getAddress();
            if (bytes.length == 4) {
                return (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff) + ".0/24";
            }
            return String.format(
                    Locale.ROOT,
                    "%x:%x:%x::/48",
                    ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff),
                    ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff),
                    ((bytes[4] & 0xff) << 8) | (bytes[5] & 0xff));
        } catch (UnknownHostException e) {
            return DOTS;
        }
    }

    /** Free text with everything in it that is a person's data masked, and the rest as it was. */
    public static String text(String text) {
        return IN_TEXT.matcher(text).replaceAll(found -> {
            var match = found.group();
            String masked;
            if (found.start("email") >= 0) {
                masked = email(match);
            } else if (found.start("upi") >= 0) {
                masked = upi(match);
            } else if (found.start("pan") >= 0) {
                masked = pan(match);
            } else if (found.start("phone") >= 0) {
                masked = phone(match);
            } else {
                masked = ip(match);
            }
            return java.util.regex.Matcher.quoteReplacement(masked);
        });
    }

    /** One named value: by what its field holds, or else by what's in it. */
    static String field(String name, String value) {
        var key = name.toLowerCase(Locale.ROOT);
        if (TECHNICAL.contains(key) || key.endsWith("version")) {
            return value;
        }
        var kind = FIELDS.get(key);
        if (kind != null) {
            return switch (kind) {
                case PHONE -> phone(value);
                case PAN -> pan(value);
                case UPI -> upi(value);
                case BANK -> bank(value);
                case HIDDEN -> DOTS;
            };
        }
        if (key.equals("address") || key.endsWith("address")) {
            // The security log's address is an IP. Any other is a place a person lives or works.
            return value.contains(":") || value.matches("[0-9.]+") ? ip(value) : DOTS;
        }
        return text(value);
    }

    /** A copy of a JSON response, with every value masked. */
    public static JsonNode json(JsonNode node) {
        if (node instanceof ObjectNode object) {
            var masked = object.objectNode();
            object.properties().forEach(member -> {
                var value = member.getValue();
                masked.set(
                        member.getKey(),
                        value.isString() ? StringNode.valueOf(field(member.getKey(), value.asString())) : json(value));
            });
            return masked;
        }
        if (node instanceof ArrayNode array) {
            var masked = array.arrayNode();
            array.forEach(item -> masked.add(json(item)));
            return masked;
        }
        return node.isString() ? StringNode.valueOf(text(node.asString())) : node;
    }
}
