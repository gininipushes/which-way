# Which Way?

A tiny, fully offline Android compass that points you to the nearest **smoke shop, liquor store, or temple** around MIT Manipal.

No internet. No maps. No accounts. Just GPS, the phone's compass, and a needle.

<p align="center"><i>SUTTA · DAARU · MANDIR</i></p>

## Features

- **Three swipeable tabs**: Sutta (tobacco / convenience stores), Daaru (bars, wine & liquor shops), Mandir (temples, churches, mosques, all places of worship).
- **Compass to the nearest place** in the selected tab, with distance, using GPS + rotation vector sensor, corrected to true north offline (`GeomagneticField`).
- **Offline data**: OpenStreetMap POIs within 8 km of MIT Manipal (13.3525, 74.7926) are bundled in the APK (`assets/places.csv`).
- **Tap the compass** to cycle through the nearest few places.
- **LIST** menu: every place in the tab, nearest first. Tap one to lock the needle on it.
- **Long-press to pin** your current spot as a new place; long-press a pin to remove it.
- **FRIENDS + Bluetooth mesh sync**: add friends by 12-character code or by tapping a nearby phone. Mutual friends exchange pins over Bluetooth LE, and pins relay onward through friends (gossip style). Last edit wins; deletions propagate.
- **No `INTERNET` permission.** ~50 KB APK.

## Install

**[⬇ Download WhichWay.apk (v2.2, ~50 KB)](https://github.com/gininipushes/which-way/raw/main/release/WhichWay.apk)**

Open it on your phone, allow installs from unknown sources, and install. Grant location (and Bluetooth/notifications if you want sync).

Requires Android 7.0+ (API 24). Targets API 34.

## Build

No Gradle. Needs JDK 17+, `aapt2`, `zipalign`, `apksigner`, a platform-34 `android.jar`, and `r8.jar`.

```bash
# Debian/Ubuntu
sudo apt install openjdk-21-jdk-headless aapt zipalign apksigner zip

ANDROID_JAR=/path/to/android.jar R8_JAR=/path/to/r8.jar \
KEYSTORE=release.keystore KS_PASS=yourpass ./build.sh
```

A keystore is generated if none exists. Keep it safe: updates must be signed with the same key to install over an older version.

## Refreshing the place data

```bash
python3 scripts/fetch_api.py    # pull OSM data around campus
python3 scripts/make_csv.py     # write assets/places.csv
./build.sh
```

`places.csv` format: `category<TAB>lat<TAB>lon<TAB>name<TAB>kind` with category `0` sutta, `1` daaru, `2` mandir.

## Project layout

```
AndroidManifest.xml
assets/places.csv            bundled OSM places
res/                         icon, theme
src/com/pratyush/disha/
  MainActivity.java          sensors, GPS, LIST & FRIENDS dialogs
  CompassView.java           custom-drawn tabs, compass, bottom bar
  Place.java                 place model + distance/bearing
  PinStore.java              pins (LWW + tombstones), friends, device id
  SyncService.java           BLE advertise/scan + GATT pin exchange
scripts/                     OSM fetch + CSV generation
build.sh                     Gradle-free build
```

## How sync works

- Each phone advertises a BLE service (`6d3f1a20-4b7e-4c1e-9a55-d15000000001`) carrying its 6-byte ID.
- When it sees a **mutual** friend, it connects over GATT (HELLO / NAME / DATA characteristics) and pulls their full pin snapshot in 480-byte chunks.
- Merge is last-writer-wins by timestamp; deletions are tombstones, so they spread too.
- Runs as a foreground service. Aggressive battery savers may stop it.

## Status

v2.2. Compass and list are working; Bluetooth sync is new and still needs real-world testing on two phones. OSM coverage of small shops is thin, so pins are the fallback. Issues and PRs welcome.

## License

Code: MIT. Place data © OpenStreetMap contributors, available under the [ODbL](https://www.openstreetmap.org/copyright).
