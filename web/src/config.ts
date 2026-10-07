import type { AuthAdapterName } from "./build-settings";

/**
 * The build's settings, written into the bundle by Vite at build time. vite.config.ts checks them before
 * anything is built (src/build-settings.ts).
 */
export const config = {
  apiUrl: import.meta.env.VITE_API_URL ?? "",
  auth: (import.meta.env.VITE_AUTH ?? "oidc-mock") as AuthAdapterName,
  oidc: {
    issuer: import.meta.env.VITE_OIDC_ISSUER ?? "",
    clientId: import.meta.env.VITE_OIDC_CLIENT_ID ?? "",
  },
  firebase: {
    apiKey: import.meta.env.VITE_FIREBASE_API_KEY ?? "",
    authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN ?? "",
    projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID ?? "",
  },
} as const;
