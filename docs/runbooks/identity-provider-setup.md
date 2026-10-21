# Identity provider setup (one time, by Nikki)

**Nothing from this runbook is ever committed.** Your project id and the web app's settings go only into two gitignored files, `control-plane/.env` and `web/.env.local`, and later into the production environment's variables. Claude Code doesn't touch your Google account and never asks for a key; you do every step yourself.

The control plane accepts ID tokens from Google Identity Platform, using Google sign-in and passwordless email links, with no passwords (ADR 0009). The Google Cloud console's labels change from time to time; if one below has moved, look for the same setting nearby.

It takes about 20 minutes. At the end you sign in to the local web app with your own Google account (section 10).

## 1. Create the Google Cloud project

1. Go to https://console.cloud.google.com/projectcreate and sign in with your own Google account.
2. Pick a project name. The **project id** shown under it is what the control plane needs; you can't change it later.
3. Link a billing account. Identity Platform needs one, but the first 50,000 monthly active users are free.

## 2. Turn on Identity Platform

1. In the console, open **Identity Platform**: search for it in the top bar, or use https://console.cloud.google.com/customer-identity.
2. Click **Enable Identity Platform**.

## 3. Set up the OAuth consent screen

Google sign-in shows this screen to the person signing in. Do it before adding the Google provider.

1. Open **APIs & Services → OAuth consent screen** (https://console.cloud.google.com/apis/credentials/consent). In the newer console it is called **Google Auth Platform**; use **Branding**, **Audience** and **Data access** there.
2. **App name:** `IdleO`.
3. **User support email:** your own address.
4. **Developer contact email:** your own address.
5. **Audience / User type:** **External**. Leave the publishing status on **Testing**.
6. Under **Test users**, click **Add users** and add the Google address you will sign in with. Only test users can sign in while the app is in Testing; anyone else sees "Access blocked".
7. Save. Leave scopes as they are: the default `email`, `profile` and `openid` are all we use.

## 4. Enable Google and email-link sign-in, and nothing else

Under **Identity Platform → Providers**:
1. **Add a provider → Google.** Enable it. The console fills in the web client for you; if it asks for a "Web SDK configuration", leave the defaults. Save.
2. **Add a provider → Email / Password.** Enable it with **Email link (passwordless sign-in)** turned on. Leave **Password** sign-in off. Save.
3. Leave every other provider off, including **Phone**. Phone verification comes later, with host payouts.

Check the provider list: Google and Email (link only) are enabled, and no password sign-in is.

## 5. Set the authorized domains

Under **Identity Platform → Settings → Security → Authorized domains**:
- Keep `localhost`, for local development. (Nothing else is needed for it: not `127.0.0.1`, and no port.)
- Add the web app's real domain once it exists. Sign-in redirects and email links work only for these domains.

## 6. Set up the email-link message

Under **Identity Platform → Settings → Templates** (or **Email templates**, if the console words it that way), open **Email link sign-in**:
1. **Sender name:** `IdleO`. The sender address stays Google's own until we have our domain.
2. **Subject:** leave the default, or use `Your IdleO sign-in link`.
3. **Action URL:** the link in the email goes to Google's handler first, on your project's own domain (`<project-id>.firebaseapp.com`), then returns to the page the web app asked for. For local development leave the default. When the real domain exists, customise the action URL to it and add it to the authorized domains (section 5).

## 7. Find the web app's settings

1. In **Identity Platform → Providers**, click **Application setup details** (top right of the page).
2. A box shows a snippet with `apiKey`, `authDomain` and the project id. You need those three values. The API key isn't a secret, but it still lives only in env files and build settings, never in tracked files.

## 8. Put the values in the two env files

**Control plane**, in `control-plane/`:
```
cp .env.example .env
```
Edit `.env` and add these two lines, with your project id in both:
```
IDENTITY_ISSUERS_0_ISSUERURI=https://securetoken.google.com/<your-project-id>
IDENTITY_ISSUERS_0_AUDIENCE=<your-project-id>
```
The issuer ends in the same project id as the audience. Both must be the **project id** (for example `idleo-dev-123456`), not the project's display name or number.

**Web app**, in `web/`:
```
cp env.local.example .env.local
```
Edit `.env.local`: change `VITE_AUTH` to `firebase`, delete the two `VITE_OIDC_*` lines, and add the three values from section 7:
```
IDLEO_DEPLOYMENT=local
VITE_API_URL=http://localhost:8080
VITE_STORAGE_URL=http://localhost:9100
VITE_AUTH=firebase
VITE_FIREBASE_API_KEY=<apiKey>
VITE_FIREBASE_AUTH_DOMAIN=<authDomain>
VITE_FIREBASE_PROJECT_ID=<your-project-id>
```

Both files are gitignored. Run `git status` and confirm neither ever shows up. For production, set the same variables in the platform's environment settings, never in a file in the repo.

## 9. Start everything with real sign-in

The development sign-in server stays the default: `scripts/run-local.sh` ignores the two issuer lines in `.env`. To use your project instead, pass the switch.

1. In `control-plane/`:
   ```
   scripts/run-local.sh --real-sign-in
   ```
   It refuses to start, and names the line to check, if either value is missing, still the example, or the two don't match. When it starts it prints `real sign-in: trusting project <your-project-id>`.
2. In `web/`: `pnpm dev`. It reads `.env.local`, so it uses Firebase.
3. Open http://localhost:3000.

To go back to the development sign-in server, start `scripts/run-local.sh` without the switch and put `.env.local` back to `VITE_AUTH=oidc-mock`.

Access is by invite while the app is invite-only: a first sign-in lands on a code step, and `docs/runbooks/invites.md` shows how to make a code.

## 10. Check it worked

1. Click **Continue with Google** and choose your test-user account.
2. If a code is asked for, enter one from `create-invites`.
3. Open **Account** from the avatar button. It shows your name and your Google email address.

## 11. Troubleshooting

| What you see | Cause and fix |
| --- | --- |
| `Firebase: Error (auth/unauthorized-domain)` | The page's domain isn't authorized. Add it in section 5. For local use it must be `localhost`: open http://localhost:3000, not http://127.0.0.1:3000. |
| Google sign-in works, but every API call answers 401 `unauthenticated` (the web app sends you back to sign in) | The control plane rejects the token. Check the two lines in `control-plane/.env`: `IDENTITY_ISSUERS_0_AUDIENCE` must equal `VITE_FIREBASE_PROJECT_ID`, and `IDENTITY_ISSUERS_0_ISSUERURI` must be exactly `https://securetoken.google.com/<your-project-id>` with no trailing slash. Both are the project id, not its display name. Restart `scripts/run-local.sh --real-sign-in`. To see Spring's reason, add `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY=DEBUG` to `.env` and look for "Failed to authenticate since the JWT was invalid". |
| Google's page says `Error 400: redirect_uri_mismatch` | The OAuth client Firebase uses for Google doesn't list Firebase's sign-in handler. In the Google Cloud console, **APIs & Services → Credentials**, open the OAuth 2.0 client named "Web client (auto created by Google Service)", then add `https://<project-id>.firebaseapp.com/__/auth/handler` under **Authorised redirect URIs** and `http://localhost:3000` under **Authorised JavaScript origins** (and the deployed web app's address, once there is one). Save, wait a few minutes, and try again in a fresh tab. |
| `Firebase: Error (auth/popup-blocked)` | The browser blocked the Google window. Allow pop-ups for localhost:3000 and try again. |
| `Error 403: access_denied` or "Access blocked: IdleO has not completed the Google verification process" | The app is in Testing and your address isn't a test user. Add it in section 3. |
| `Firebase: Error (auth/api-key-not-valid...)` or `auth/invalid-api-key` | `VITE_FIREBASE_API_KEY` is missing or mistyped in `web/.env.local`. Copy it again from section 7, then restart `pnpm dev`: it reads the file only at start. |
| `Firebase: Error (auth/operation-not-allowed)` | That provider isn't enabled. Check section 4. |
| The email link says it has expired or was already used | Links work once. Ask for a new one; open it in the same browser you asked from. |
| `run-local: ... Check those two lines in control-plane/.env` | `scripts/run-local.sh --real-sign-in` found the issuer or audience missing, left as the example, or not matching. Fix the line it names. |

## 12. A service account for erasing accounts

When a person's account is erased (ADR 0037), the control plane deletes their Identity Platform account too, through the Identity Toolkit admin API. It needs a service account allowed to do that, and nothing more.

1. In the Google Cloud console, **IAM & Admin → Service Accounts → Create service account**: name it `idleo-erasure`.
2. Grant it one role: **Identity Platform → Identity Toolkit Admin** (`roles/identitytoolkit.admin`). No other role.
3. Open it, **Keys → Add key → Create new key → JSON**. The browser downloads the key once.
4. Put the whole file in the secret store as `IDENTITY_PROVIDER_ADMIN_CREDENTIALS` (or put it on the server, readable only by the control plane's user, and set the variable to `file:/path/to/key.json`). Delete the downloaded copy. Never commit it, never paste it into a ticket.
5. The prod profile refuses to start without it. Locally the development sign-in server keeps no accounts, so nothing is needed: an erasure only records that it would have deleted one.

To check it: erase a test account on staging (Account, Your data, Delete my account; staging's wait can be shortened with `LEGAL_ERASURE_WAITING_PERIOD`) and look it up in Identity Platform's **Users**: it's gone. If the erasure log says "the provider refused to delete the account: 403", the role is missing.

Rotate it yearly: add a new key, update the secret, restart, delete the old key.

