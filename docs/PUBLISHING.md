# Publishing RetroLink on Google Play

A checklist from code to a live Play Store app. Steps marked **(once)** only happen the first time.

## 1. Pick your app's name and id (once)

Edit `gradle.properties`:

```properties
app.name=RetroLink
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

To rename the app everywhere, also replace "RetroLink" in
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

## Rules to stay on Google Play

- Never include games, BIOS files or links to download them.
- Never show copyrighted game art or logos in the listing.
- This app is GPL-3.0: keep the source code public (for example on GitHub) and link to it in
  the listing. Keep the original copyright notices.
- Answer room reports. Reports appear in your room server's log.
