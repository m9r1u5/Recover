# Recover

Android app to find and restore deleted photos **without rooting** the phone.

## Install / update
1. Open **[Releases → latest](https://github.com/m9r1u5/Recover/releases/latest)** on your phone.
2. Download **Recover.apk** and open it (allow "Install unknown apps" for your browser the first time).
3. New versions install straight over the old one. In the app: **⋮ ▸ Check for update**.

## What it finds
Without root no app can read raw storage, and modern phones (TRIM + encryption) wipe deleted data quickly.
Recover finds the copies that are still on the phone:

| Source | What it is |
|---|---|
| **Recently deleted** | Android 11+ system bin (~30 days). Restores back to the original album. |
| **Bins & hidden** | Gallery recycle-bin folders (e.g. Samsung `Android/.Trash`), `.trashed-` files, hidden folders. |
| **Thumbnails** | `.thumbnails` caches – including JPEGs extracted from old `.thumbdata` files. |
| **WhatsApp / Telegram** | Copies kept by chat apps (Sent, Statuses, etc.). |
| **Not in gallery** | Photos the gallery doesn't show (e.g. folders with `.nomedia`, files with no extension). |

Restored files are saved to **Pictures/Recovered**. There are also shortcuts to the Google Photos bin and Samsung Gallery recycle bin.

## Permissions
- **All files access** – needed to look in hidden folders, bins, caches and chat-app folders.
- **Photos** – to read the system bin.
- No internet permission: nothing leaves the phone.

## Limits
- Android only (iPhone apps can't access other apps' files or deleted photos).
- `Android/data` folders are blocked by Android 11+ for all non-root apps.
- Photos that were fully deleted and overwritten/trimmed can't be recovered by any app.

## Build
GitHub Actions builds a signed APK on every push to `main` and publishes it as a release.
