# Identity provider setup (one time, by Nikki)

**Nothing from this runbook is ever committed.** The project id goes only into your local, gitignored `control-plane/.env`, and into the production environment's variables. Claude Code doesn't touch your Google account; you do every step yourself.

The control plane accepts ID tokens from Google Identity Platform, using Google sign-in and passwordless email links, with no passwords (ADR 0009). The Google Cloud console's labels change from time to time; if one below has moved, look for the same setting nearby.

## 1. Create the Google Cloud project

1. Go to https://console.cloud.google.com/projectcreate and sign in with your own Google account.
2. Pick a project name. The **project id** shown under it is what the control plane needs; you can't change it later.
3. Link a billing account. Identity Platform needs one, but the first 50,000 monthly active users are free.

## 2. Turn on Identity Platform

1. In the console, open **Identity Platform**: search for it in the top bar, or use https://console.cloud.google.com/customer-identity.
2. Click **Enable Identity Platform**.

## 3. Enable Google and email-link sign-in, and nothing else

Under **Identity Platform → Providers**:
1. **Add a provider → Google.** Enable it, and follow the console's prompts to set up the OAuth consent screen. Save.
2. **Add a provider → Email / Password.** Enable it with **passwordless sign-in (email link)** turned on, and sign-in with a password turned off. Save.
3. Leave every other provider off, including **Phone**. Phone verification comes later, with host payouts.

Check the provider list: Google and Email (link only) are enabled, and no password sign-in is.

## 4. Set the authorized domains

Under **Identity Platform → Settings → Security → Authorized domains**:
- Keep `localhost`, for local development.
- Add the web app's domain once it exists.

Sign-in redirects and email links work only for these domains.

## 5. Find the project id and put it in your local `.env`

1. The project id is on the console's project picker and on the project's **Dashboard → Project info**.
2. In `control-plane/`, copy the example file once:
   ```
   cp .env.example .env
   ```
3. Edit `.env` and replace `demo-project` in both lines with your project id:
   ```
   IDENTITY_ISSUERS_0_ISSUERURI=https://securetoken.google.com/<your-project-id>
   IDENTITY_ISSUERS_0_AUDIENCE=<your-project-id>
   ```
4. Load it before running the app:
   ```
   set -a && . ./.env && set +a
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```

`.env` is gitignored. Run `git status` and confirm it never shows up. For production, set the same two variables in the platform's environment settings, never in a file in the repo.

The project id isn't a secret, but it's still configuration: it lives in the environment, never in code or tracked files.
