import { API_SCHEMAS } from "../paths";

/**
 * The API reference's schemas moved to their own page (ADR 0029), so a link made before that, `/docs/api/#schema-Name`,
 * is sent on to `/docs/api/schemas/#schema-Name`. The operations' own anchors stay where they were.
 */
const hash = window.location.hash;
if (hash === "#schemas" || hash.startsWith("#schema-")) {
  window.location.replace(hash === "#schemas" ? API_SCHEMAS : `${API_SCHEMAS}${hash}`);
}
