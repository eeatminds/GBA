# Publishing GBA/GBC Emulator on Google Play

A checklist from code to a live Play Store app. Steps marked **(once)** only happen the first time.

> **Not a programmer? Read this first.** Two steps need a computer with
> [Android Studio](https://developer.android.com/studio): creating the upload key (step 3) and
> building the app file (step 4). Everything else is done in a web browser. If you would rather not
> install Android Studio, ask whoever maintains this project to build and sign the `.aab` file for
> you once — after that, every update is the same one command, and you only ever upload the file in
> the browser.

> **⚠️ Pick a safe app name before you submit.** Google often rejects apps whose **title** uses
> console or company names such as "GBA", "GBC", "Game Boy", "Nintendo" or "Pokémon". Use an
> **original name** for the app title (for example a made-up brand name), and describe what it does
> ("a retro handheld emulator") only in the store **description**, never in the title or icon. See
> step 1.

## 1. Pick your app's name and id (once)

Edit `gradle.properties`:

```properties
app.name=GBA/GBC Emulator
app.applicationId=com.yourname.retrolink
app.versionCode=1
app.versionName=1.0.0
app.onlineServerUrl=https://your-room-server.example.com
app.linkScheme=retrolink
```

- `app.applicationId` can **never change** after your first upload. Use a domain or name you own,
  for example `com.yourname.retrolink`.
- Raise `app.versionCode` by 1 for every upload.
- Pick an original app name. Do not use console, game or company names (Nintendo, Game Boy,
  PlayStation, Pokémon and so on) in the name, icon or screenshots.

To rename the app everywhere, also replace "GBA/GBC Emulator" in
`lemuroid-app/src/main/res/values*/strings.xml` and run `APP_NAME="New Name" python3 scripts/make_icons.py`.

## 2. Put the room server online (once)

Follow `server/README.md`. The free Render option takes about 5 minutes.

## 3. Create an upload key (once)

```bash
keytool -genkeypair -v -keystore upload-key.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Create `keystore.properties` in the project folder (it is ignored by git; keep a backup of both files):

```properties
storeFile=upload-key.jks
storePassword=your-password
keyAlias=upload
keyPassword=your-password
```

## 4. Build the app bundle

Install Android Studio (or the Android SDK command-line tools, with NDK 26.1.10909125), then:

```bash
git submodule update --init --depth 1
```

```bash
./gradlew :lemuroid-app:bundlePlayDynamicRelease
```

The file to upload is `lemuroid-app/build/outputs/bundle/playDynamicRelease/lemuroid-app-play-dynamic-release.aab`.
Each emulator core is a separate download inside the bundle, so players only download the
cores for the consoles they use.

## 5. Set up the app in Play Console (once)

1. Create a developer account at https://play.google.com/console (one-time fee of US$25).
   New personal accounts must run a closed test with at least 12 testers for 14 days before
   publishing to everyone.
2. **Create app**: name, default language, "App", "Free".
3. **App content** (left menu → Policy → App content):
   - Privacy policy: host `docs/PRIVACY.md` (fill in the placeholders) on a web page and paste its link.
   - Ads: No ads.
   - App access: All functionality is available without special access.
   - Content rating: fill in the questionnaire. Answer "yes" to users being able to interact
     (online rooms with player names).
   - Target audience: 13+ is the simplest choice for an app with online rooms.
   - Data safety: declare "Name" (display name, optional, not stored) and "App interactions"
     sent to your server for app functionality, encrypted in transit, not shared.
4. **Store listing**: use `docs/store/listing.md`, `docs/store/icon-512.png` and
   `docs/store/feature-graphic-1024x500.png`, plus your own screenshots.

## 6. Upload and test

1. Testing → Closed testing → Create release → upload the `.aab`.
2. Add your testers' emails, roll out, and share the opt-in link.
3. After the testing period, apply for production access and roll out to Production.

## 7. Turn on verified invite links (optional)

After the first upload, copy the **SHA-256 certificate fingerprint** from
Play Console → Test and release → App integrity → App signing. Set `ANDROID_PACKAGE` and
`ANDROID_CERT_SHA256` on your room server. Invite links then open the app directly.

## 8. Cloud save backup (optional)

The Google Drive save sync needs a Google Cloud OAuth client for your app id and signing
certificate. Without it, everything else works and save sync just stays off. To set it up,
create an Android OAuth client in Google Cloud Console for your package name and the
SHA-1 from Play App Signing, and enable the Google Drive API.

## Protecting your app from copies

Honest summary: **no setting can make an Android app impossible to copy** — anyone can pull an `.apk`
off their phone. What actually stops a copycat from hurting *you* is owning your app's **identity**,
and that is already handled:

- **Only you can update or impersonate your app.** With **Play App Signing** (step 5), Google holds
  the real signing key. Nobody else can publish an update to your listing, and no re-uploaded copy
  can share your app's identity. A thief can only post a *separate*, lower-ranked clone — they can
  never take over your users or your reviews.
- **The code is already obfuscated.** The release build runs R8, which shrinks and renames the code,
  so a copied build is hard to read or rebrand. This is on by default (`isMinifyEnabled = true`).
- **Your keys never go in the project.** `keystore.properties` and your `.jks` file are git-ignored.
  Keep a private backup of both; with Play App Signing you can reset a lost *upload* key through
  Google, so a lost upload key is recoverable.
- **Optional, stronger:** the [Play Integrity API](https://developer.android.com/google/play/integrity)
  lets the app refuse to run if it was tampered with or installed from outside Play. It is powerful
  but can also block legitimate users (rooted phones, some regions), so add it only if copycats
  become a real problem.

**About "encryption so it can't be copied and sold":** this app is **GPL-3.0** (it is built on
Lemuroid). That licence *requires* the source to stay public and *allows* anyone to redistribute it —
so a license-lock or DRM that forbids copying would be illegal to ship here, not just ineffective.
The protections above are the real, lawful way to keep control: your brand, your listing and your
update channel stay yours.

## Game cover pictures on the games web page

The games web page (`/games` on your room server) shows each game's box art. The 11 original games
download their covers automatically. The two ROM hacks (Radical Red, Unbound) have no cover in the
public library, so add them by hand **once**:

1. Put `radicalred.webp` and `unbound.webp` into `/etc/gba-rooms/covers/` on your server.
2. That's it — refresh the page. (Covers you place by hand are always kept, even when the server
   updates.)

These two pictures are fan-made game art, so they live only on your own server, never in the public
code repository.

## Rules to stay on Google Play

- Never include games, BIOS files or links to download them.
- Never show copyrighted game art or logos in the listing.
- This app is GPL-3.0: keep the source code public (for example on GitHub) and link to it in
  the listing. Keep the original copyright notices.
- Answer room reports. Reports appear in your room server's log.
