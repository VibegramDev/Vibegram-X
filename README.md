<p align="center">
  <img src="images/logo.png" width="112" alt="Vibegram X">
</p>

<h1 align="center">Vibegram X</h1>

<p align="center">
  Telegram client for Android, built on <a href="https://github.com/TGX-Android/Telegram-X">Telegram X</a>.<br>
  <i>make Telegram great again</i>
</p>

<p align="center">
  <a href="https://t.me/VibegramDev">Telegram</a> · <a href="README.ru.md">Русский</a>
</p>

## What's new

- Material 3 design, Monet themes that follow the system palette
- On-device transcription of voice, round and video messages, dictation
- Forum topics, channel direct messages, checklists, rich messages
- Bot mini apps, Telegram Stars, paid messages, Log in with Telegram
- Round videos in the best quality Telegram and your device allow: up to 640×640 at 60 fps
- Custom emoji in folder names, «Delete my messages» in groups

Everything else is Telegram X: same speed, same code style.

## Build

Requires JDK 25, Android SDK, Git LFS and [rustup](https://rustup.rs).

1. `git clone --recursive https://github.com/VibegramDev/Vibegram-X`
2. Create `local.properties`:
   ```properties
   sdk.dir=<Android SDK>
   rust.dir=<printed by scripts/setup-rust.sh>
   telegram.api_id=<from my.telegram.org>
   telegram.api_hash=<from my.telegram.org>
   ```
   and put `app/google-services.json` of a Firebase project for `dev.vibegram.x`.
3. `./gradlew assembleLatestDebug -Pabi=arm64`

Platform details and all commands: [Telegram X build instructions](https://github.com/TGX-Android/Telegram-X#build-instructions).

## License

[GPL-3.0](LICENSE). Based on Telegram X by [vkryl](https://github.com/vkryl) and contributors,
third-party components are listed in [THIRDPARTY.md](docs/THIRDPARTY.md).
Before contributing, read the [code style](docs/CODE_STYLE.md) and the [guide](docs/GUIDE.md).
