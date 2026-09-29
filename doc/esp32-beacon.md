# Make an ESP32 a desk beacon

An ESP32 board (about $5) can be a Bluetooth LE **iBeacon** that LockMyPhone Extended uses as a place, e.g. "at my desk". A coin-cell iBeacon you can buy works the same way. So does a tablet or spare phone with **Settings → Act as a beacon** turned on.

## What you need
- Any ESP32 with Bluetooth LE (ESP32, ESP32-C3, ESP32-S3). The plain ESP32-S2 has **no** Bluetooth.
- The Arduino IDE with the **esp32** board package (Boards Manager → "esp32 by Espressif").
- A USB power source at the desk: a phone charger or the monitor's USB port.

## Minimal sketch (always on)

```cpp
#include <BLEDevice.h>
#include <BLEBeacon.h>
#include <BLEAdvertising.h>

// Make your own UUID (e.g. uuidgenerator.net) so the app doesn't confuse your desk with another beacon.
static const char *BEACON_UUID = "8b0f6d1e-2c4a-4e7b-9a53-6f1d2e3c4b5a";
static const uint16_t MAJOR = 1;   // e.g. 1 = home
static const uint16_t MINOR = 1;   // e.g. 1 = desk

void setup() {
  BLEDevice::init("LMP desk");

  BLEBeacon beacon;
  beacon.setManufacturerId(0x4C00);             // Apple's ID, little-endian: marks it as an iBeacon
  beacon.setProximityUUID(BLEUUID(BEACON_UUID));
  beacon.setMajor(MAJOR);
  beacon.setMinor(MINOR);
  beacon.setSignalPower(-59);                   // typical RSSI at 1 m; the app calibrates anyway

  BLEAdvertisementData data;
  data.setFlags(0x04);                          // BR/EDR not supported
  std::string payload;
  payload += (char)26;                          // length
  payload += (char)0xFF;                        // manufacturer data
  payload += beacon.getData();
  data.addData(payload);

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->setAdvertisementData(data);
  adv->setMinInterval(0x640);                   // 1 s (units of 0.625 ms); slower = less power
  adv->setMaxInterval(0x640);
  adv->start();
}

void loop() {
  delay(1000);
}
```

> Newer esp32 cores (3.x) use `String` instead of `std::string` in `addData`. If the sketch doesn't compile, change `std::string payload;` to `String payload;` and `beacon.getData()` to `String(beacon.getData().c_str())`.

Upload it, then on your phone:
1. Automations → Locations → **+** (or open a place).
2. Signals: tick **Bluetooth beacon**.
3. **Pick a beacon** while standing next to it. It shows as *iBeacon* ending in *:1:1* (major:minor), usually at the top because it's the strongest. Some esp32 core versions send the UUID byte-reversed; that's fine, since the app remembers whatever it hears.
4. Sit where you usually do and tap **Calibrate at my spot**.

## Only during set hours (optional)

The ESP32 can get the time from the internet and broadcast only during your hours. Outside them it sleeps, and the phone sees you as "not here" after about 2 minutes.

Add Wi-Fi + time to the sketch above:

```cpp
#include <WiFi.h>
#include <time.h>

static const char *WIFI_SSID = "your-wifi";
static const char *WIFI_PASS = "your-password";
static const char *TIMEZONE = "EST5EDT,M3.2.0,M11.1.0";  // POSIX TZ string for your zone
static const int START_MINUTE = 9 * 60;                  // 09:00
static const int END_MINUTE = 17 * 60;                   // 17:00

int minuteOfDay() {
  struct tm now;
  if (!getLocalTime(&now)) return -1;
  return now.tm_hour * 60 + now.tm_min;
}

// Call this at the top of setup(), before BLEDevice::init().
void sleepOutsideHours() {
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  for (int i = 0; i < 40 && WiFi.status() != WL_CONNECTED; i++) delay(250);
  configTzTime(TIMEZONE, "pool.ntp.org");
  int now = minuteOfDay();
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
  if (now < 0) return;                                   // no time: broadcast anyway
  if (now >= START_MINUTE && now < END_MINUTE) return;   // inside the hours
  int minutesUntilStart = (START_MINUTE - now + 24 * 60) % (24 * 60);
  esp_deep_sleep((uint64_t)minutesUntilStart * 60ULL * 1000000ULL);
}
```

And in `loop()`, go to sleep when the hours end:

```cpp
void loop() {
  int now = minuteOfDay();
  if (now >= END_MINUTE) {
    int minutesUntilStart = (START_MINUTE - now + 24 * 60) % (24 * 60);
    esp_deep_sleep((uint64_t)minutesUntilStart * 60ULL * 1000000ULL);
  }
  delay(30000);
}
```

Usually it's simpler to leave the beacon always on and use the place's **time slots** in the app instead.

## Tips
- **Range:** the app counts you as near when the smoothed signal is at or above the calibrated threshold for about 45 s, and as gone after about 2 min without it. If it triggers from the next room, lower the threshold (e.g. -75 → -70 dBm) or recalibrate closer.
- **Placement:** metal desks and monitors block the signal. Mount the board on the side of the desk facing your chair.
- **Battery beacons:** most coin-cell iBeacons let you set the UUID/major/minor and the interval in their own app. A 1 s interval is plenty.
- **Several beacons:** give each a different minor (desk = 1, bed = 2) and make one place per beacon.
- **Privacy:** an iBeacon only broadcasts its fixed ID. It collects nothing and can't see your phone.
