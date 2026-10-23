package com.idleo.controlplane.billing.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * India's GST state codes (the first two digits of a GSTIN, and a place of supply), by the GST Council's list: the
 * renter's state decides CGST and SGST (the same state as ours) or IGST (another). LEGAL(confirm): the CA checks the
 * list once, and whenever a state or territory is formed or merged.
 */
final class GstStates {

    static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        String[][] states = {
            {"01", "Jammu and Kashmir"},
            {"02", "Himachal Pradesh"},
            {"03", "Punjab"},
            {"04", "Chandigarh"},
            {"05", "Uttarakhand"},
            {"06", "Haryana"},
            {"07", "Delhi"},
            {"08", "Rajasthan"},
            {"09", "Uttar Pradesh"},
            {"10", "Bihar"},
            {"11", "Sikkim"},
            {"12", "Arunachal Pradesh"},
            {"13", "Nagaland"},
            {"14", "Manipur"},
            {"15", "Mizoram"},
            {"16", "Tripura"},
            {"17", "Meghalaya"},
            {"18", "Assam"},
            {"19", "West Bengal"},
            {"20", "Jharkhand"},
            {"21", "Odisha"},
            {"22", "Chhattisgarh"},
            {"23", "Madhya Pradesh"},
            {"24", "Gujarat"},
            {"26", "Dadra and Nagar Haveli and Daman and Diu"},
            {"27", "Maharashtra"},
            {"29", "Karnataka"},
            {"30", "Goa"},
            {"31", "Lakshadweep"},
            {"32", "Kerala"},
            {"33", "Tamil Nadu"},
            {"34", "Puducherry"},
            {"35", "Andaman and Nicobar Islands"},
            {"36", "Telangana"},
            {"37", "Andhra Pradesh"},
            {"38", "Ladakh"},
            {"97", "Other Territory"}
        };
        for (var state : states) {
            NAMES.put(state[0], state[1]);
        }
    }

    private GstStates() {}

    static Optional<String> name(String code) {
        return Optional.ofNullable(NAMES.get(code));
    }

    static boolean known(String code) {
        return NAMES.containsKey(code);
    }
}
