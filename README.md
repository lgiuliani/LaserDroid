# LaserDroid

Android application driving GRBL 1.1 laser CNCs (reference: Sculpfun S10)
over USB OTG, with a LaserGRBL Wi-Fi server. Kotlin, Android 10+ (API 29).

## Modules

| Module | Role | Android |
|---|---|---|
| `communication-api` | `SerialTransport`: byte link, independent of the CNC protocol | no |
| `communication-usb` | USB serial transport (usb-serial-for-android, 115200 8N1) | yes |
| `communication-network` | Telnet / WebSocket servers (LaserGRBL) | no |
| `controllers-api` | `CncController` abstraction, states, results, events | no |
| `controllers-grbl` | GRBL 1.1 driver: parser, line buffer, jog builder | no |
| `simulator` | GRBL simulator and fake transport (tests) | no |
| `core-gcode` | Normalizer, analyzer (bounds, duration, preview), modal tracker | no |
| `core-streaming` | Send/Response streaming engine (critical module) | no |
| `core-machine` | `MachineManager`: machine arbiter, jog, workshop functions | no |
| `core-jobs` | `JobManager`: job lifecycle, recovery, workshop G-code | no |
| `core-remote` | LaserGRBL reception (virtual GRBL) and bridge modes | no |
| `app` | Foreground service, UI (Views), settings, logs, history | yes |

Dependencies go from `app` towards the core; the core never depends on
Android, the UI never writes to the USB link directly.

## Safety rules

- A job is never started or resumed automatically (reconnection,
  program received from LaserGRBL, boot).
- A job owns the machine (`MachineManager.beginJob`): manual commands
  are refused meanwhile.
- An `ok` is not the end of a motion: a job is completed when the
  controller reports Idle after the last acknowledgement.
- Any change to `core-streaming` must pass its regression tests.

## Build and tests

```
./gradlew assembleDebug
./gradlew test          # JVM tests of every core module
```

Tests use the GRBL simulator: they never replace a validation on the
real machine.
