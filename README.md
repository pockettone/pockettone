# PocketTone

**Private, end-to-end encrypted messaging for keypad flip phones.**

PocketTone is a fork of [Signal for Android](https://github.com/signalapp/Signal-Android) built for phones with no touchscreen. It adds a keypad layer to the app: T9 predictive typing, soft-key menus and full D-pad navigation. You can read and send messages using only the phone's buttons.

PocketTone is an independent project. It is not affiliated with or endorsed by Signal Messenger LLC. It uses the regular Signal network, so you can message anyone who uses Signal.

> **Status:** early development. The first debug build works, but PocketTone hasn't been tested on a phone yet. Expect rough edges.

---

## Target device

| | |
|---|---|
| Phone | Kyocera DuraXV Extreme (E4810) |
| Screen | 240 × 320, no touch |
| Input | 12-key keypad, D-pad with OK, two soft keys, Call, Clear |

Other Android keypad phones with similar buttons may work, but they haven't been tried.

## Features

- **Built-in T9.** Predictive word typing with a suggestion bar. You don't need to install a separate keyboard app.
- **Three typing modes.** T9 word, multi-tap `abc` and `123`. Each letter mode has lower, Capitalized and UPPER case.
- **Learns your words.** Words you use often move up the suggestion list. Nothing is learned from password fields.
- **Soft keys**
  - **Left:** typing-mode menu while you're in a text box, otherwise Back.
  - **Right:** the options menu for the current screen.
- **Call key sends** the message.
- **Clear key** deletes one character. When the text box is empty, it goes back a screen. Holding it keeps deleting, but it never leaves the screen on its own.
- **D-pad.** Scrolls through T9 suggestions while you're picking a word, and moves between items everywhere else.
- **Has its own app ID** (`app.pockettone`), so it won't replace any messaging app that's already on the phone.

### Typing cheat sheet

| Key | T9 word mode |
|---|---|
| `2`–`9` | Type letters; the best-matching word appears |
| Hold `2`–`9` | Type the number itself |
| `◀ ▶` | Previous / next suggestion |
| `▲ ▼` | Jump a row of suggestions |
| `OK` | Accept the word |
| `#` | Space (also accepts the word) |
| Hold `#` | New line, where the text box allows one |
| `1` | Punctuation picker (`◀ ▶` to browse, `OK` to insert) |
| `0` | Start a number |
| `*` | Case: lower → Capitalized → ALL CAPS |
| `Clear` | Undo the last key of the word, or delete a character |

Switch between T9, `abc` and `123` with the **left soft key** menu.

## How it works

All of the keypad code is in
`app/src/main/java/org/thoughtcrime/securesms/flip/`:

| File | Purpose |
|---|---|
| `FlipKeypad.kt` | Attaches to every screen when the app starts. It switches off the phone's own keyboard for the app, sends key presses to T9 whenever a text box is selected, and draws the soft-key bar and suggestion bar. |
| `t9/T9Session.kt` | Holds the typing state for the selected text box and types into it through Android's `InputConnection`, so it works on both classic and Jetpack Compose screens. |
| `t9/T9Engine.kt`, `t9/Tt9WordEngine.kt` | Look up words from key sequences and rank them by how often they're used and which word came before. |
| `assets/t9/en.txt` | The English dictionary. |

Apart from this folder, the only code change is one line in `ApplicationContext.onCreate()` that switches the keypad layer on. Everything else is branding: the app name, the text shown on screen, and the icons. Keeping changes this small makes it easier to bring in new releases from upstream.

Internal Java package names (`org.thoughtcrime.securesms`, `org.signal.*`) and the upstream copyright notices are unchanged. Renaming the packages would touch thousands of files and make upstream updates very hard, and the copyright notices have to stay under the license.

## Building

Requirements:

- **JDK 21**
- **Android SDK.** Accept the SDK licenses with `sdkmanager --licenses`.
- About 10 GB of free disk space. The first build takes about 20 minutes.

```sh
git clone https://github.com/pockettone/pockettone.git
cd pockettone

# local.properties — use forward slashes, even on Windows
echo "sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk" > local.properties

./gradlew :PocketTone:assemblePlayProdDebug
```

The APKs end up in `app/build/outputs/apk/playProd/debug/` and are named `PocketTone-…apk`. Install the one that matches the phone's processor (usually `armeabi-v7a` or `arm64-v8a`):

```sh
adb install -r app/build/outputs/apk/playProd/debug/<apk-file>.apk
```

Release builds need your own signing key.

## Credits

- **[Signal](https://github.com/signalapp/Signal-Android)** by Signal Messenger LLC, the app PocketTone is built on.
- **[TurboText](https://github.com/Ben-Showalter)** by Ben Showalter. PocketTone's T9 typing rules follow TurboText and are used with his permission.
- **[Traditional T9 (TT9)](https://github.com/sspanak/tt9)**, the source of the English word list (Apache-2.0). See `assets/t9/NOTICE.txt`.
- **[Flip-DumbPhoneGuide](https://github.com/Ben-Showalter/Flip-DumbPhoneGuide)** (MIT), for its research on these phones.

## License

The Signal code PocketTone is based on is licensed under the **GNU AGPLv3**, so PocketTone's app code is also licensed under the AGPLv3. Third-party parts keep their own licenses, as listed in [Credits](#credits).

"Signal" is a trademark of Signal Messenger LLC. PocketTone is not a Signal product.
