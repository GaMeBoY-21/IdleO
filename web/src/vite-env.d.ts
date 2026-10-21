/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_URL?: string;
  readonly VITE_STORAGE_URL?: string;
  readonly VITE_HOST_RUNBOOK_URL?: string;
  readonly VITE_AUTH?: string;
  readonly VITE_OIDC_ISSUER?: string;
  readonly VITE_OIDC_CLIENT_ID?: string;
  readonly VITE_FIREBASE_API_KEY?: string;
  readonly VITE_FIREBASE_AUTH_DOMAIN?: string;
  readonly VITE_FIREBASE_PROJECT_ID?: string;
  /** The public website's address, written in by vite.config.ts from IDLEO_SITE_URL (ADR 0029). */
  readonly IDLEO_SITE_URL?: string;
  /** The release this build is, from IDLEO_RELEASE, which real visits' vitals and errors name (ADR 0036). */
  readonly IDLEO_RELEASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
