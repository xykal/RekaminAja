# Privacy Policy — RekaminAja

_Last updated: 24 October 2026_

RekaminAja is a free and open-source screen recorder. Short version:
**we collect nothing.**

## What we (don't) collect

- RekaminAja has **no internet permission**. The app cannot send your data anywhere, even if it wanted to.
- No accounts, no ads, no analytics, no crash-report SDKs, no tracking of any kind.
- Your recordings and screenshots are saved only on your device
  (`Movies/RekaminAja` and `Pictures/RekaminAja`) and never leave it unless you share them yourself.

## Permissions and why they are needed

| Permission | Why |
|---|---|
| Screen capture (MediaProjection, asked every session) | Core screen recording & screenshots |
| Microphone (`RECORD_AUDIO`) | Recording mic / internal audio (only if you pick those modes) |
| Camera (`CAMERA`) | Optional facecam overlay |
| Display over other apps (`SYSTEM_ALERT_WINDOW`) | Floating ball, facecam, draw-on-screen, censor zones |
| Notifications | Recording controls & “saved” notification |
| Usage access (`PACKAGE_USAGE_STATS`, optional, off by default) | Detecting *your selected* sensitive apps in the foreground to auto-pause recording. This information is only used live on-device and is never stored or shared |
| Storage (Android 9 and below) | Saving recordings to the public Movies folder |
| Ignore battery optimizations (optional) | Keeps long recordings alive on aggressive OEM battery savers |

## Third parties

None. There are no third-party SDKs in the app.

## Children

RekaminAja is a general-purpose utility and does not target children.

## Changes

If this policy changes, the new version will be published in the project
repository together with the app update.

## Contact

Open an issue in the GitHub repository of this project.
