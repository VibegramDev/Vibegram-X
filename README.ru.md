<p align="center">
  <img src="images/logo.png" width="112" alt="Vibegram X">
</p>

<h1 align="center">Vibegram X</h1>

<p align="center">
  Клиент Telegram для Android на основе <a href="https://github.com/TGX-Android/Telegram-X">Telegram X</a>.<br>
  <i>make Telegram great again</i>
</p>

<p align="center">
  <a href="https://t.me/VibegramDev">Telegram</a> · <a href="README.md">English</a>
</p>

## Что нового

- Дизайн Material 3, темы Monet в цветах системы
- Расшифровка голосовых, кружков и видео на устройстве, диктовка
- Темы форумов, ЛС каналов, чек-листы, rich-сообщения
- Мини-приложения ботов, Telegram Stars, платные сообщения, вход через Telegram
- Кружки в лучшем качестве, какое позволяют Telegram и устройство: до 640×640 при 60 к/с
- Кастомные эмодзи в названиях папок, «Удалить мои сообщения» в группах

Всё остальное — Telegram X: та же скорость и тот же стиль кода.

## Сборка

Нужны JDK 25, Android SDK, Git LFS и [rustup](https://rustup.rs).

1. `git clone --recursive https://github.com/VibegramDev/Vibegram-X`
2. Создайте `local.properties`:
   ```properties
   sdk.dir=<путь к Android SDK>
   rust.dir=<выводит scripts/setup-rust.sh>
   telegram.api_id=<с my.telegram.org>
   telegram.api_hash=<с my.telegram.org>
   ```
   и положите `app/google-services.json` проекта Firebase для `dev.vibegram.x`.
3. `./gradlew assembleLatestDebug -Pabi=arm64`

Подробности по платформам и все команды: [сборка Telegram X](https://github.com/TGX-Android/Telegram-X#build-instructions).

## Лицензия

[GPL-3.0](LICENSE). Основано на Telegram X от [vkryl](https://github.com/vkryl) и участников,
сторонние компоненты перечислены в [THIRDPARTY.md](docs/THIRDPARTY.md).
Перед тем как присылать изменения, прочитайте [стиль кода](docs/CODE_STYLE.md) и [руководство](docs/GUIDE.md).
