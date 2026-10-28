# QolAIJol cloud APIs (verified 2026-09-27)

Facts checked with real requests during Phase 3, for the voice (Phase 4), scene description
(Phase 5) and navigation (Phase 6) work. Local warnings (YOLO → decision → TTS) never use these
APIs and keep working offline.

## Language: English (switched 2026-10-01)

The app now speaks and listens in English only: TTS `en-US` (offline voice), Whisper
`language=en`, on-device recognizer `en-US`, English prompts. The Russian notes below are history.

- If the offline English voice or the on-device English speech model is missing, the camera screen
  shows a "Download English?" prompt by itself; "Download" opens the TTS engine's voice installer and
  calls `triggerModelDownload`, then the app re-checks every 5 s for 3 minutes and on every resume.
- **2GIS does not understand English queries in Aktobe** (checked 2026-10-01: `q=pharmacy` → 404,
  `q=аптека` → results; `locale=en_KZ` is rejected, only `ru_KZ` / `kk_KZ` exist for Kazakhstan).
  `navigation/PlaceQuery.kt` translates common place words to Russian, the LLM translates the rest.
- 2GIS names and addresses are Cyrillic; they are spoken through `navigation/Translit.kt`
  ("Мир здоровья" → "Mir zdorovya", "улица Аз Наурыз" → "Az Nauryz Street"). Places are named by
  `name_ex.primary` (without the ", аптека" category suffix).

## Keys

| Key | Where | Build |
|---|---|---|
| `groq_api_key`, `dgis_api_key` | `QolAIJol/local.properties` (git-ignored) | Injected into **debug** `BuildConfig` only; **release gets empty strings** |

Code reads them only through `config/ApiKeys.kt`. Never log, display or share them, and never share
a debug APK. Production: move the calls behind a Supabase Edge Function that holds the keys.

## Groq (`https://api.groq.com/openai/v1`, header `Authorization: Bearer <key>`)

| Use | Model | Result |
|---|---|---|
| Speech-to-text | `whisper-large-v3-turbo` | `POST /audio/transcriptions` (multipart: `file`, `model`, `language=ru`, `temperature=0`). Russian test phrase «Что впереди? Найди ближайшую аптеку.» transcribed exactly in 0.7 s. |
| Scene description | **`qwen/qwen3.8-27b`** | `POST /chat/completions`, image as `data:image/jpeg;base64,…` (768 px, JPEG q80 ≈ 116 KB), `reasoning_effort: "none"`, `max_tokens: 300`, prototype's Russian prompt: good Russian answer in 1.2 s. |
| Intent fallback | `openai/gpt-oss-20b` | With `max_tokens: 100` the answer is **empty** (reasoning uses the budget). Works with `reasoning_effort: "low"`, `response_format: {"type": "json_object"}`, `max_tokens: 300`. |

- **`qwen/qwen3.6-27b` (used by the Python prototype) no longer exists on Groq (HTTP 404)**, so the
  prototype's scene description is broken; use `qwen/qwen3.8-27b`.
- **Rate limit on this key for `qwen/qwen3.8-27b`: 8,000 tokens/minute, 1,000 requests.** A frame
  shrunk to 360x640 costs ~1,000 prompt tokens (768 px images ~2,000), so about 7 descriptions per
  minute. HTTP 429 carries `x-ratelimit-reset-tokens` (e.g. `33.217s`); the app turns it into
  «Подождите N секунд».
- Scene-description prompt (Phase 5, `vision/SceneDescriber.kt`): the prototype's prompt produced
  8 sentences and invented distances («в 3-4 метрах»); the tightened prompt gives 2-3 sentences, no
  metres/steps, reads visible signs, and forbids saying that crossing or walking on is safe. Measured:
  0.5-1.4 s per description; on the emulator 522-557 ms end to end for a 22 KB JPEG.
- The vision model states distances in metres it cannot know ("3-4 метрах"); the Phase 5 prompt
  must forbid exact distances.
- Models available on this key: `whisper-large-v3`, `whisper-large-v3-turbo`, `openai/gpt-oss-20b`,
  `openai/gpt-oss-120b`, `openai/gpt-oss-safeguard-20b`, `qwen/qwen3.8-27b`, `allam-2-7b`,
  prompt-guard and Orpheus TTS models.

### Offline speech-recognition fallback (no internet, or Groq fails)

- The same recording goes to Android's **on-device** recognizer
  (`voice/OfflineSpeechRecognizer.kt`, `SpeechRecognizer.createOnDeviceSpeechRecognizer`) through
  `RecognizerIntent.EXTRA_AUDIO_SOURCE` — **Android 13+ only**; Android 12 has no offline fallback.
- The Russian on-device model must be downloaded once while online; the app checks this at start
  (`checkRecognitionSupport`) and requests it (`triggerModelDownload`). On the test emulator it
  arrived within a few minutes.
- Verified offline (airplane mode): «Стоп», «Что впереди?», «А что слева?», «Включи подсказки»
  recognized correctly one after another.
- Quirks found: the final result bundle came back **empty** while partial results had the words
  (the app uses the last partial result); creating a new recognizer per command caused
  `ERROR_SERVER_DISCONNECTED` (11) on the next one (the app reuses one instance and retries once).
- Offline, only local commands work (scene questions, стоп/старт, повтори, помощь); the LLM
  fallback, «опиши» and navigation need the internet.

## 2GIS (`key` query parameter — never log request URLs)

| Use | Request | Result |
|---|---|---|
| Nearest place | `GET https://catalog.api.2gis.com/3.0/items?q=аптека&point=<lon>,<lat>&radius=3000&sort=distance&page_size=5&fields=items.point,items.name` | `sort=distance` works: results come nearest first (502, 508, 513, 525, 597 m). Note **lon,lat** order in `point`. |
| Walking route | `POST https://routing.api.2gis.com/routing/7.0.0/global` with `"transport": "walking"`, `"locale": "ru"`, two `stop` points | `type: "pedestrianrouting"`; maneuvers carry ready Russian hints in `outcoming_path_comment` («600 м прямо», «Вы на месте!»). |

The prototype used `driving` (car routes) and no distance sort; both are fixed by the requests above.

### Walking navigation findings (Phase 6)

- **Pedestrian routes have no turn-by-turn maneuvers** — only `begin` ("start") and `end`
  ("finish"), even for 2.5 km and with `"output": "detailed"`. The app therefore computes turns from
  the route geometry (`navigation/WalkingRoute.kt`: heading change ≥ 30° measured over ±12 m).
- The geometry parts carry accessibility data: `style` = `normal` / `stairway` / `overgroundway`
  (and presumably `undergroundway`) plus `zlevel` (`zlevel-normal`, `zlevel-positive`, …). The app
  announces «лестница вверх / вниз», «надземный переход», «подземный переход» from them.
- Street crossings are **not** marked in this data, so the app never announces "safe to cross".
- Test fixture: `app/src/test/resources/dgis_walking_route.json` (trimmed real response, public
  streets in central Aktobe).
- On-device location must use `LocationRequest.QUALITY_HIGH_ACCURACY`: the default balanced
  quality answers from Wi-Fi/cell only (20-100 m) and never starts GPS.
- Emulator testing: the emulator GPS reports a position only at the moment of `adb emu geo fix`,
  so a test has to keep sending fixes while the app waits for a position.
