# eAI Mobile for Android

[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](#-installation)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](./LICENSE)

> Android companion app for eAI. Connect to Ollama on your local network, OpenAI-compatible APIs, Anthropic, or Google Gemini.

The Android app is built with **Kotlin** and **Jetpack Compose**. It includes a touch-first chat interface, streaming responses, saved conversations, and persistent provider settings.

---

## ✨ Features

- **Multiple providers** — connect to Ollama, OpenAI-compatible services, Anthropic, and Google Gemini.
- **On-device model** — download Qwen3 0.6B to the phone and chat offline with Google's LiteRT-LM runtime. The model is about 586 MB and is downloaded separately from the APK.
- **Persistent settings** — API keys, base URLs, selected model, system instructions, and generation preferences are saved on the device.
- **Streaming chat** — responses stream into the conversation as they arrive.
- **Conversation history** — create, switch between, and delete saved chats.
- **Conversation tools** — search history, rename threads, edit messages, regenerate a reply, and export conversations.
- **Image attachments** — attach images for vision-capable models across supported providers.
- **Markdown replies** — readable headings, lists, inline emphasis, links, and fenced code blocks.
- **Web search** — optionally add SearXNG results to prompts so the model can cite sources.
- **Goal agent** — switch on Goal agent above the composer to plan a task, search with your configured SearXNG instance, and read public webpages. It is read-only: it cannot log in, submit forms, post, purchase, or modify website data. Connected providers are required; on-device models cannot browse yet.
- **Generation controls** — tune temperature, response token limit, and thinking mode.
- **Touch-friendly interface** — mobile navigation, prompt suggestions, model selection, and a keyboard-aware composer.
- **Encrypted settings** — API credentials and settings are stored with Android Keystore-backed encrypted preferences.
- **Private chat storage** — conversation history and attached images stay in app-private device storage.

---

## 🛠 Tech Stack

| Component | Library |
| :--- | :--- |
| UI | [Jetpack Compose](https://developer.android.com/develop/ui/compose) / Material 3 |
| HTTP | [OkHttp](https://square.github.io/okhttp/) |
| Secure settings | AndroidX Security Crypto / Android Keystore |
| Conversation storage | App-private JSON files |

---

## 📦 Installation & Setup

### Install the APK

Download the latest debug APK from the repository's build artifacts or build it from source using the steps below. Install the APK on an Android device running Android 8.0 (API 26) or later.

### Connect an API

Open **Settings & connection**, choose a provider, enter its base URL and API key if required, select a model, and tap **Save settings**. The provider configuration is encrypted and stored on the device so it does not need to be entered each time.

To run AI on the phone, choose **On this phone** in Settings and download the Qwen3 0.6B model (about 586 MB). Once installed, select **Use on this phone**. The model runs locally and can chat without a network connection. This starter model is text-only; image prompts require a remote vision-capable provider.

For Ollama, the phone and computer must share a network. Set the URL to the computer's LAN address, such as `http://192.168.1.20:11434`, and configure Ollama on the computer to accept LAN connections.

For OpenAI-compatible APIs, enter the service's API base URL (typically ending in `/v1`), API key, and model ID. Anthropic and Gemini use their respective API base URLs and keys. To use web search or Goal agent, enable **Web search** in Settings and set the SearXNG server URL; its JSON search format must be enabled. Goal agent currently requires a connected cloud or Ollama provider. It can search and read public pages, but it will stop before sign-ins or actions that change data.

### Build from Source

Open the `Mobile` directory in Android Studio and sync Gradle, or run:

```bash
gradle assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 🔒 Privacy

API keys and settings are stored with Android Keystore-backed encrypted preferences. Conversations and attached images are stored in app-private device storage. Requests are sent directly to the configured AI provider and, when enabled, the configured SearXNG server. The app allows cleartext HTTP for local LAN services; use trusted networks or HTTPS endpoints for remote services.

## 📄 License

This project is available under the [MIT License](./LICENSE).

### Phone control mode

Choose **Agents → Control this phone** to set up Android's accessibility service. Android will ask you to enable **eAI Phone Control** manually. Before each phone-control task, the app explains that visible screen text and screenshots are sent to your selected model provider. While the task runs, a draggable floating panel offers **Stop**, redirect, minimize-to-icon, and close controls. On Android 11 and newer, the agent can inspect screenshots and use screen-coordinate taps and swipes for games and apps that expose little accessibility text. On older Android versions, it uses the accessibility tree, taps, typing, and scrolling.

The phone agent can inspect visible controls, tap, type in non-password fields, scroll, and press Back. It asks before taps on common high-impact controls such as Send, Submit, Delete, Purchase, and Allow. Do not use it on sensitive screens. Android accessibility grants broad access to screen contents and interaction; disable the service in Android Accessibility settings when you no longer need it. The phone agent requires a connected provider; on-device model support is not available yet.
