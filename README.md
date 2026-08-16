# SALUTE Mesh

SALUTE Mesh is a phone app for sending and receiving **SALUTE** and shorter **SALT** reports over a Meshtastic radio network. Phones talk to a radio already on your belt or dashboard. The radios carry the message. Other people running this app on the same radio channel see the report.

There is no iPhone version yet. You install the Android app yourself (sideload). It is not on the Play Store.

## Download the phone app

Sideload the field APK from **[GitHub Releases](https://github.com/rellefsen/saluteMesh/releases)**. Allow unknown sources. This is a debug build for field trials, not a Play Store app.

- **Field radio:** `SaluteMesh-mesh-debug-0.1.3.apk` — Bluetooth to a Meshtastic radio. Includes the Meshtastic SDK (**GPL-3.0**).
- **Practice (no radio):** `SaluteMesh-mock-debug-0.1.3.apk` — form and history only.

Install over a previous 0.1.x build from this project (same debug signing key). The two APKs can sit on one phone (practice has a different app id).

---

## What is a SALUTE report?

SALUTE is a short field report. Each letter is a box on the form:

| Letter | Means | Example |
|--------|--------|---------|
| **S** | Size | 4 people |
| **A** | Activity | static, moving, checkpoint |
| **L** | Location | Oak and 3rd, or a grid |
| **U** | Unit | who you are / who you saw |
| **T** | Time | when this was true |
| **E** | Equipment | 1 truck, HT, etc. |

You also set a **callsign** (your short name on the net). That stays when you clear the form.

### SALT (short form)

Tap **SALT** on the form when you only need the short report: **Size, Activity, Location, Time**. Unit and Equipment are hidden and are not sent. Tap **SALUTE** when you need the full six-line report.

The send button changes to **Send SALT** or **Send SALUTE** so people on the net know which format went out. History labels each card as SALT or SALUTE.

You do not have to fill every box. Fill what you know, then send.

---

## What you need

1. An **Android phone** (field) and/or a **Linux or Windows laptop** (command center).
2. A **Meshtastic radio** for each station that will send or receive.
3. Those radios already on the **same channel**, with the **same secret key** (PSK). Someone who knows the radios sets that up once. **This app does not set encryption or channel keys.**
4. The **SALUTE Mesh** app (phone APK, or the laptop command-center window).

Close the official Meshtastic app while you use SALUTE Mesh. Pair the radio in Android Bluetooth, then **disconnect** it in Android settings so this app can hold the connection.

---

## Two places the app runs

| Where | When to use |
|---------|-------------|
| **Phone — Practice** (`mock`) | Try the form and history with no radio. **Fake incoming** pretends a report arrived. |
| **Phone — Field** (`mesh`) | Talk to a real Meshtastic radio over Bluetooth. This is the one for the street. |
| **Laptop — Command center** | Linux or Windows at EOC / district. USB cable **or** Bluetooth to a radio on the desk. Same SALUTE/SALT form, history, and long-message splitting as the phone. |

Ask whoever builds the install files which phone file is which. Install the Field version on phones that will go out with radios.

---

## How to use it

### Compose (write and send)

1. Open the app. Stay on **Compose** (scan and connect are on **Radio**).
2. Put in your **callsign**.
3. Choose **SALUTE** (full) or **SALT** (short).
4. Fill the boxes that are shown.
5. **Time** is a real field. Tap **Now** for the current time, or type a different time (`year-month-day hour:minute:second`).
6. Tap **Send SALUTE** or **Send SALT**.

**Clear data** empties the report boxes and stamps a new Now. It keeps your callsign. It does **not** erase old messages.

A line under Equipment shows how big the report is. Short reports go as one radio message. Long ones are split automatically (see below).

### History (what went out and what came in)

Open the **History** tab.

- **OUT** is something you sent.
- **IN** is something you received.

History stays on **that phone** after you close the app. It is not a shared cloud inbox.

**Fill form** copies an old report back onto Compose so you can change it and send again. It does **not** change your callsign.

When a new report arrives, a banner appears at the top. Open **History** from there, or **Dismiss** it.

**Clear history** wipes the log on this phone. It does not unsay anything already on the mesh.

### Radio (phone and laptop)

Open the **Radio** tab. On the phone this is next to Compose and History, same as the laptop command center.

**Field (mesh) phone**

1. Scan for radios and tap yours.
2. Connect / Reconnect.
3. Tap the **mesh channel** this net uses (often named **salute**). You can change it any time without reconnecting.

Then go back to **Compose** to send. Incoming reports show a banner at the top and land in **History**.

**Practice (mock) phone**

Radio explains this build has no Bluetooth. Use **Fake incoming** on Compose to drill History.

The radios must already share that channel and key. If people cannot hear each other, the radios are not on the same channel — not a problem the app can fix.

---

## Long reports

Meshtastic can only carry a small amount of text in one burst. If your SALUTE is too long, the app **cuts it into several pieces**, waits about **two seconds** between them, and the other phones **put the pieces back together**.

You still see one report in History, not nine scraps.

If a report is extremely long, the app will ask you to shorten a field. Nine pieces is the cap.

---

## Command center (Linux or Windows laptop)

This is the full app on a laptop: Compose, History, and **Radio**.

1. Plug the radio in with USB, **or** pair it over Bluetooth (then disconnect it in the computer’s Bluetooth settings so this app can hold the link).
2. Open the command-center window (see the start commands below).
3. Open the **Radio** tab.
4. Choose **USB serial** or **Bluetooth**.
5. Find/scan, tap the radio. After **Connect**, tap the **mesh channel** this net uses (often **salute**).
6. Go back to **Compose** and send. Incoming reports show a banner at the top and land in **History**.

History is saved on **that laptop** (`~/.saluteMesh/` on Linux, similar under your user folder on Windows). It is not a cloud inbox.

**Fake incoming** still works for drills without a second radio.

Close the official Meshtastic app on any phone that was talking to the same radio. One computer or phone at a time per radio.

### Start the laptop app

**Linux**

```bash
cd ~/saluteMesh
./scripts/run-command-center.sh
```

The first run installs a small Python helper for talking to the radio. You need JDK 21 and Python 3. Linux USB: your user should be in the `dialout` group.

**Windows**

1. Install JDK 21 and Python 3.
2. Double-click `scripts\run-command-center.bat`, or run it from the `saluteMesh` folder.

Do not install Ubuntu’s `gradle` package. Use the script that comes with this project.

---

## Trying the form with no radio

You can still open the window and use **Fake incoming**. Without Connect, **Send** will ask you to connect a radio first.

---

## What this version does not do

- It does not set radio channel names or secret keys. Do that on the radios.
- It does not run on iPhone.
- It does not store history in the cloud. Each phone and each laptop keeps its own History. The command-center laptop hears the mesh only while it is connected to a radio.

---

## For the person who builds the install files

Use the Gradle wrapper in this repo, JDK 21 (Android Studio’s JBR is fine), and `./gradlew` — not apt `gradle`.

```bash
cd ~/saluteMesh
export JAVA_HOME="/opt/android-studio/jbr"
./gradlew assembleMockDebug
./gradlew assembleMeshDebug
```

Install files:

- Practice: `app/build/outputs/apk/mock/debug/app-mock-debug.apk`
- Field: `app/build/outputs/apk/mesh/debug/app-mesh-debug.apk`

Linux preview / command center: `./scripts/run-command-center.sh` or `./gradlew :desktop:run` after `python/setup-venv.sh`.

Radio helper: `python/radio_bridge.py` (Meshtastic Python serial + Bluetooth).
