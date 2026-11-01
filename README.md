# QolAIJol

**AI navigation assistant for blind and visually impaired pedestrians.** A camera on smart glasses
(or a USB webcam) streams to an Android phone. A YOLO26n detector, fine-tuned by the team on
pedestrian-view street data, runs on the phone and short voice warnings tell the user what is in the
way: *"Person ahead."*, *"Vehicle on the left."*. The user can ask "What's ahead?", "Describe what's
around", or "Take me to the nearest pharmacy" by voice.

**Try it in the browser:** https://soejongghan.github.io/QolAIJol/
The web prototype runs the same model and the same warning rules as the app on your own device.
Pick a street scene, take a photo, or turn on the camera and listen.

QolAIJol is a student project (ICT course, Astana IT University, group IT-2609). It is a research
prototype, **not a certified mobility aid**: always keep using a white cane or a guide dog.

## What the app does

| Feature | How it works | Needs internet |
|---|---|---|
| Obstacle warnings | YOLO26n (ONNX Runtime, on the phone) every 500 ms → object tracker → decision engine → offline English TTS | No |
| "What's ahead / left / right?" | Counts the latest detections per zone: "Ahead: bus, 2 people." | No |
| Voice commands | Push-to-talk → Groq Whisper; Android on-device recognizer as offline fallback (Android 13+) | Partly |
| "Describe what's around" | One frame → vision-language model (Qwen on Groq) with a safety-tuned prompt, 2–3 sentences | Yes |
| Find a place, walking route | 2GIS search (nearest first) and pedestrian routing; turns computed from the route geometry; stairs and underpasses announced | Yes |
| Hands-free control | Meta Ray-Ban glasses gestures: double tap = speak, swipe = what's around, capture button = describe | No |

### How a warning is chosen (`decision/`)

1. Only objects with confidence ≥ 0.50, seen in at least two detection rounds (filters one-frame errors).
2. Priority by class: vehicle and cyclist HIGH; person and animal MEDIUM; pole and obstacle LOW.
   An object in the middle third of the image (the walking path) is one level higher.
3. LOW objects off to the side are not spoken.
4. One warning at a time, at least 2.5 s apart (CRITICAL may interrupt); the same object type at the
   same place is not repeated for 10 s.
5. **Stairs, traffic lights and crosswalks are never spoken.** In our evaluation the detector was too
   weak on them (stairs AP50 0.13, traffic light 0.05), and a false "crosswalk ahead" could lead
   someone onto the road. The app never says that it is safe to cross.

## Architecture

```
Meta Ray-Ban glasses (DAT SDK)  ─┐
USB UVC webcam (OTG)            ─┼─► CameraFrame ─► VisionPipeline (YOLO26n, ONNX Runtime, 2 Hz)
Mock device (testing)           ─┘                        │
                                                          ▼
                                   ObjectTracker ─► DecisionEngine ─► TtsManager (offline en-US)
                                                          │
                     VoiceAssistant ◄── mic (glasses / phone) ── Whisper (Groq) / on-device STT
                          │
                          ├─► SceneAnalyzer        (offline answers)
                          ├─► SceneDescriber       (vision LLM, on request only)
                          └─► NavigationManager    (2GIS search + walking route + GPS RouteGuide)
```

- Kotlin, Jetpack Compose, minSdk 31, targetSdk 36
- Meta Wearables Device Access Toolkit 1.0.0 (camera, inputs, mock device)
- ONNX Runtime 1.30.0, model `app/src/main/assets/models/yolo26n_qolaijol_ft.onnx` (9.8 MB, 9 classes,
  NMS-free end-to-end head)
- UVCAndroid for USB webcams

## The detector

The model comes from the team's reproducible study
*Pedestrian-View Obstacle Detection for Assistive Smart Glasses: A Reproducible YOLO26 Study on Public
Egocentric Data* ([doi.org/10.5281/zenodo.23429509](https://doi.org/10.5281/zenodo.23429509)).

- Data: SANPO-Real (Google Research, CC BY 4.0), head- and chest-camera video of people walking;
  9,465 human-annotated frames, session-level split 6,375 / 1,149 / 1,941.
- Classes: pedestrian, rider, vehicle, animal, obstacle, pole, stairs, crosswalk, traffic light.
- Test results (1,941 frames): fine-tuning raised mAP50 from 0.141 (COCO YOLO26n) to 0.295 for
  YOLO26n; YOLO26s reached 0.328. Pole, rider, stairs and crosswalk cannot be detected at all by the
  COCO model.
- Speed: YOLO26n 27 ms per frame on a laptop CPU (ONNX, 4 threads); 0.4–0.9 s per frame in a
  desktop browser (WebAssembly, 1 thread).
- Known limits: about 4 in 10 nearby objects are missed; night, rain and indoor scenes are untested.

## Testing

- 70 JVM unit tests (decision engine, tracker, command parser, scene answers, walking-route guidance
  with simulated walks, phrases, frame conversion) and instrumentation tests with a mock glasses
  device and a recorded video. Run: `./gradlew testDebugUnitTest`
- Real API checks are recorded in [CLOUD_APIS.md](CLOUD_APIS.md), including what was changed after
  each test (prompt tightening, walking instead of driving routes, offline speech fallback).

## Build

1. A current Android Studio (Android Gradle Plugin 8.11), JDK 17+.
2. Optional cloud features: add to `local.properties` (git-ignored)
   ```
   groq_api_key=...
   dgis_api_key=...
   ```
   Keys go into **debug** builds only. Without them, obstacle warnings and offline answers still work.
3. Run on a phone with Android 12+. Pair Meta Ray-Ban glasses through the Meta AI app, or plug in a
   USB webcam, or use the mock device for testing.

## Privacy and safety

- Camera frames are processed on the phone; nothing is stored or uploaded for warnings.
- A frame leaves the phone only when the user asks "Describe what's around"; the prompt forbids
  describing faces or appearance.
- Exact GPS coordinates are never logged. API keys are never logged or shipped in release builds.
- The app never claims a path is clear: an empty zone is reported as "nothing detected".

## Team

| Member | Role |
|---|---|
| Dair Syrlybayev | Android app, detector research; users and caregiver access |
| Alibek Bolat | Devices and telemetry |
| Abylai Zhakyp | Navigation and sessions |
| Arman Nagim | Places and accessibility |

## Use of AI tools

AI coding assistants (Claude Code) were used to write and review code, run experiments and draft
documentation. Features were checked with unit tests, on the Android emulator and with Meta's mock
glasses device; the details and example prompts are in the project report.

## Credits

- Street frames in the web prototype: SANPO dataset, Google Research, CC BY 4.0.
- Ultralytics YOLO26, ONNX Runtime, Meta Wearables Device Access Toolkit, UVCAndroid, 2GIS API, Groq.
