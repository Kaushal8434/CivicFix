# CivicFix – Project Documentation

**Smart Location-Based Civic Grievance & Resolution System**
*Report → Route → Resolve → Verify*

This file explains everything in the project: what the Android app does, how the AI was trained, which datasets and tools were used, which functions do what, and how to run and retrain everything.

---

## 1. What is in this folder

```
CivicFix/
├── CivicFix-debug.apk            ← ready-to-install Android app (debug build)
├── PROJECT_DOCUMENTATION.md      ← this file
├── android/                      ← Android Studio project (Kotlin + Jetpack Compose)
│   └── app/src/main/
│       ├── assets/               ← trained models + configuration used by the app
│       │   ├── text_model.json          (trained complaint-text model)
│       │   ├── civic_classifier.onnx    (trained photo model – created by ml/03_…)
│       │   ├── image_labels.json        (photo model labels – created by ml/03_…)
│       │   ├── departments.json         (routing table, SLA days, escalation levels)
│       │   └── locations.json           (City → Zone → Ward → Locality tree)
│       └── java/com/civicfix/app/       ← app source code (see section 6)
└── ml/                           ← Python training pipeline
    ├── config.py                        (categories, dataset list, Stable Diffusion prompts)
    ├── 01_download_datasets.py          (download + sort real photo datasets)
    ├── 02_generate_synthetic_images.py  (Generative AI: Stable Diffusion image synthesis)
    ├── 03_train_image_classifier.py     (train MobileNetV3 photo model → ONNX)
    ├── 04_build_text_dataset.py         (build complaint-text dataset)
    ├── 05_train_text_classifier.py      (train TF-IDF + Logistic Regression → JSON)
    ├── data/text/civic_complaints.csv   (12,000 labelled complaint texts)
    ├── data/text/handwritten_eval.csv   (30 hand-written complaints used only for testing)
    └── output/                          (metrics, confusion matrix, exported models)
```

---

## 2. Status: what is done and what you still need to run

| Item | Status |
|---|---|
| Android app (all screens and workflows) | ✅ Built successfully (`CivicFix-debug.apk`) |
| Complaint-text AI model (category + severity) | ✅ Trained and bundled in the app |
| Photo-recognition training pipeline (real data + Generative AI) | ✅ Written and tested end to end (train → ONNX export → ONNX Runtime load) |
| **Photo-recognition model weights** | ⏳ **You need to run steps 01–03 on your PC** (section 5.3). It needs the Kaggle downloads and a GPU for Stable Diffusion. It could not be trained inside this session. |

Until the photo model is trained, the app still works. It suggests the category from the description text, and the citizen picks or confirms it manually. Once you run `03_train_image_classifier.py`, the model is copied into `android/app/src/main/assets/` automatically. Rebuild the app and photo recognition is switched on. No code changes are needed.

---

## 3. How to run the app

**Option A – install the APK**
Copy `CivicFix-debug.apk` to an Android phone (Android 8.0 or newer), open it and allow "install unknown apps".

**Option B – Android Studio**
1. Install [Android Studio](https://developer.android.com/studio).
2. *File → Open* → select the `android/` folder.
3. Wait for Gradle sync, then press ▶ Run (on an emulator or a USB-connected phone).

Command line: `cd android` then `gradlew assembleDebug`. The output is `app/build/outputs/apk/debug/app-debug.apk`.

**Demo logins** (no password, prototype only):
- **Citizen**: enter your name and report problems.
- **Department officer**: pick a department and handle its complaints.
- **Supervisor**: sees every department plus escalations.

The app comes with 7 sample complaints in Lucknow, Patna and Delhi, so the dashboards are not empty.

---

## 4. App features (mapped to the project report)

| Report section | Feature in the app | Where in code |
|---|---|---|
| 4.1 Step 1 – Location | City → Town/Zone → Ward → Locality dropdowns, **or** GPS button that picks the nearest locality automatically | `ReportWizardScreen.kt`, `nearestLocality()` in `Engines.kt` |
| 4.1 Step 2 – Problem | 8 categories; the **AI pre-selects the category and severity** from the photo and description | `AiAnalyzer.analyze()` |
| 4.1 Step 3 – Evidence | Camera / gallery photo + description. Creates the complaint ID `CF-YYYYMMDD-NNNN` with a timestamp | `PhotoInput.kt`, `ComplaintRepository.newId()` |
| 4.1 Step 4 – Tracking | Status pills, SLA progress bar, target date, full timeline | `ComplaintDetailScreen.kt` |
| 4.1 Step 5 – Resolution | Officer uploads a **mandatory after-photo** and an Action Taken note | `OfficerActions()` |
| 4.1 Step 6 – Verification | Citizen taps **"Yes, fixed" → Closed** or **"No, not fixed" → Reopened + escalated** with a new deadline | `CitizenVerification()` |
| 4.2 Department workflow | Officer dashboard: New → Assigned → In Progress → Resolved, with filters (Open / Overdue / Escalated / Resolved) | `OfficerDashboardScreen` |
| 4.3 SLA model | Configurable target days per **category × severity** (e.g. streetlight medium = 7 days, road damage medium = 14 days) | `departments.json`, `RoutingEngine.route()` |
| 5.1 AI classification | On-device photo model + text model, fused; officers can **correct the category** (this re-routes the complaint) | `AiAnalyzer`, `OfficerActions()` |
| 5.2 Severity estimation | Text model predicts low/medium/high; **safety keywords** (accident, live wire, children, flood…) force HIGH | `AiAnalyzer.analyze()` |
| 5.3 Duplicate detection | Same category and open, in the same locality **or within 150 m (GPS)**: the citizen can **support the existing complaint** instead of filing a duplicate | `DuplicateDetector.findSimilar()` |
| 5.5 Automatic escalation | Overdue: level 1 (Executive Engineer), +3 days: level 2 (Addl. Commissioner), +7 days: level 3 (Commissioner) | `SlaEngine.apply()` |
| 5.6 Government analytics | Totals, overdue, % resolved within SLA, verified/reopened counts, average resolution time by department, workload, hotspot localities, repeated problems | `AnalyticsScreen` |
| Verification (extra) | **AI after-photo check**: if the after-photo still looks like the original problem, a warning is shown | `AiAnalyzer.checkAfterPhoto()` |
| Notifications | Citizen gets a phone notification when a complaint is marked resolved | `Notifier.notify()` |
| Demo | *AI models & demo* screen (🤖): "+1 day / +3 days" to **simulate time** and watch escalation happen live; "Reset demo" | `ModelInfoScreen`, `DemoClock` |

---

## 5. Artificial Intelligence – how it was trained

The app uses **two trained models** whose outputs are combined.

### 5.1 Model 1 – Complaint-text classifier (✅ trained)

| | |
|---|---|
| Task | Predict **category** (8 classes) and **severity** (low/medium/high) from the citizen's description |
| Algorithm | **TF-IDF** (word unigrams + bigrams, sublinear TF, L2 norm, 3,273 features) + **multinomial Logistic Regression** (scikit-learn) |
| Dataset | `ml/data/text/civic_complaints.csv`: **12,000 labelled complaints** built by `04_build_text_dataset.py` from phrase banks in English and **Hinglish** ("sadak toot gayi hai", "naali jam ho gayi hai", "kooda nahi uthaya gaya"…), with place, duration and impact phrases. The impact phrase sets the severity label. |
| Split | 80% train (9,600) / 20% test (2,400), stratified |
| Deployment | Exported to `text_model.json` (vocabulary, IDF and weights). `TextClassifier.kt` re-implements the maths in pure Kotlin, so no ML library is needed and it works offline. Checked: phone-side probabilities match scikit-learn within **0.000003**. |

**Results (honest):**

| Test set | Category accuracy | Severity accuracy |
|---|---|---|
| Template test split (2,400 rows) | 100% | 99.9% |
| **30 hand-written complaints** that are not from the templates (`handwritten_eval.csv`) | **96.7%** (29/30) | **53.3%** |

The 100% on the template split is **not** a meaningful measure, because those sentences come from the same templates as the training data. The hand-written set is the realistic number. Severity is harder to learn from short texts, so the app adds **rule-based safety keywords** on top of it, and officers can change the severity. To improve it, add real complaints to `ml/data/text/extra_complaints.csv` (columns `text,category,severity`) and re-run steps 04 and 05.

### 5.2 Model 2 – Photo-recognition model (Generative-AI-assisted training)

| | |
|---|---|
| Task | Recognise the civic problem in the citizen's photo (8 classes) |
| Architecture | **MobileNetV3-Large**, pre-trained on ImageNet, **fine-tuned** (transfer learning) |
| Training data | **Real photos** (public datasets, section 7) **+ synthetic photos generated with Generative AI** (Stable Diffusion) |
| Generative AI | **SD-Turbo** (`stabilityai/sd-turbo`, a distilled Stable Diffusion text-to-image model) via Hugging Face **diffusers**. Up to 2,250 images from prompts = *subject × style* (e.g. "an overflowing open drain with dirty black water on a street" × "photo taken on a smartphone, Indian city"). Each image's prompt and seed are logged in `metadata.csv`. |
| Why Generative AI | No good public datasets exist for **water leakage, drainage, road blockage and damaged infrastructure** in Indian streets. Stable Diffusion fills these gaps and balances the classes (400 images each for the rare classes, 150 for classes that already have real data). |
| Training tricks | Frozen backbone for the first 2 epochs, AdamW + cosine LR, label smoothing 0.1, class-balanced sampling, augmentation (random crop, flip, colour jitter, rotation) |
| Evaluation | Validation and test sets use **real photos only**, so accuracy reflects real citizen photos, not generated ones. A confusion matrix is saved to `ml/output/`. Use `--no-synthetic` to measure how much the generated images help (ablation). |
| Deployment | Exported to **ONNX** (`civic_classifier.onnx`), run on the phone with **ONNX Runtime Mobile** (`ImageClassifier.kt`). Input `[1,3,224,224]`, output `[1,8]` logits. |

### 5.3 How to train the photo model on your PC

```bash
cd ml
pip install -r requirements.txt
# For your NVIDIA RTX GPU, install CUDA PyTorch first:  https://pytorch.org/get-started/locally/

# 1) Real datasets (needs a Kaggle API token in %USERPROFILE%\.kaggle\kaggle.json)
python 01_download_datasets.py
#    Optional: put your own photos into ml/data/real/<category>/

# 2) Generative AI – synthesise training photos with Stable Diffusion
python 02_generate_synthetic_images.py --scale 0.1   # quick test (~225 images)
python 02_generate_synthetic_images.py               # full run (~2,250 images)

# 3) Train + export (copies the model into the Android app automatically)
python 03_train_image_classifier.py --epochs 12

# Text model (already done – re-run only if you change the data)
python 04_build_text_dataset.py --rows 12000
python 05_train_text_classifier.py
```
Then rebuild the app (Android Studio ▶ Run). The 🤖 screen in the app shows the model's test accuracy.

### 5.4 How the two models are combined (on the phone)

```
photo ──► MobileNetV3 (ONNX) ──► P_image(category)
                                              ├─► 0.65·P_image + 0.35·P_text ──► suggested category (top-3 shown)
text  ──► TF-IDF + LogReg     ──► P_text(category)
      └─► TF-IDF + LogReg     ──► severity ──► + safety-keyword rule ──► suggested severity
```
- If confidence is below 45%, the app shows "Low confidence – please confirm".
- If only one input is available (for example, no photo model yet), that model alone is used.
- The AI **only pre-selects**. The citizen and officer always make the final choice, as the report recommends (section 5.1).

---

## 6. Android app – architecture and important functions

**Language / UI:** Kotlin, Jetpack Compose (Material 3). **Min Android:** 8.0 (API 26). **Target:** API 34.

```
UI (Compose screens) ──► Domain engines (routing, SLA, duplicates) ──► Repository (JSON storage)
         │                                                                   ▲
         └──────────────► AI (AiAnalyzer → ImageClassifier + TextClassifier) │
                         Reference data (locations.json, departments.json) ──┘
```

| File | Key classes / functions | Purpose |
|---|---|---|
| `CivicFixApp.kt` | `CivicFixApp` | Application class; creates and holds all services; saves the login session |
| `MainActivity.kt` | `Nav`, `Screen`, `Root()` | Screen navigation (back stack) and role-based home screen |
| `data/Models.kt` | `Complaint`, `Status`, `TimelineEvent`, `Role`, `Session` | Data model + JSON conversion (`toJson()`, `fromJson()`) |
| `data/ReferenceData.kt` | `ReferenceData`, `LocationPath`, `pathOf()`, `allLocalities()` | Loads the location tree, departments, categories, SLA days, escalation levels, safety keywords |
| `data/ComplaintRepository.kt` | `add()`, `update()`, `newId()`, `refreshSla()`, `resetDemo()` | Stores complaints (`complaints.json`); applies escalation; seeds demo data |
| `domain/Engines.kt` | `RoutingEngine.route()` | **Route**: category → department; ward/zone → office; severity → SLA days |
| | `SlaEngine.apply()`, `progress()`, `escalationLevelFor()` | Deadline tracking and **automatic escalation** |
| | `DuplicateDetector.findSimilar()` | Duplicate complaint detection (same locality or ≤150 m) |
| | `distanceMeters()` (Haversine), `nearestLocality()` | GPS → nearest locality |
| | `DemoClock` | Time offset used to demonstrate escalation |
| `ml/TextClassifier.kt` | `classify()`, `vectorize()` | Pure-Kotlin TF-IDF + Logistic Regression inference |
| `ml/ImageClassifier.kt` | `classify(bitmap)` | ONNX Runtime inference with ImageNet normalisation |
| `ml/AiAnalyzer.kt` | `analyze()`, `checkAfterPhoto()` | Combines both models; severity rules; after-photo verification |
| `util/Utils.kt` | `Photos.loadBitmap()` (EXIF-aware), `Photos.importUri()`, `Gps.current()`, `Notifier.notify()` | Photo storage, GPS, notifications |
| `ui/screens/ReportWizardScreen.kt` | `ReportWizardScreen` | 4-step reporting wizard: Location → Evidence → Category & routing → Review |
| `ui/screens/ComplaintDetailScreen.kt` | `OfficerActions`, `CitizenVerification` | Tracking, before/after evidence, officer workflow, citizen verification |
| `ui/screens/HomeScreens.kt` | `LoginScreen`, `CitizenHomeScreen`, `OfficerDashboardScreen` | Role-based home screens |
| `ui/screens/AnalyticsScreens.kt` | `AnalyticsScreen`, `ModelInfoScreen` | Analytics dashboard; AI information + demo controls |

### Configuration files (edit these without touching code)

**`departments.json`**: category → department and SLA days (illustrative values, as the report says in section 4.3):

| Category | Department | Low | Medium | High |
|---|---|---|---|---|
| 🛣️ Pothole / Road Damage | Roads & Public Works | 21 | 14 | 3 |
| 💡 Streetlight | Electrical / Streetlight | 10 | 7 | 2 |
| 🚰 Water Leakage | Water Supply | 7 | 4 | 1 |
| 🕳️ Drainage / Sewer | Drainage & Sewerage | 7 | 4 | 1 |
| 🗑️ Garbage | Municipal Sanitation | 3 | 2 | 1 |
| 🚧 Road Blockage | Traffic & Encroachment Cell | 5 | 2 | 1 |
| 🏗️ Damaged Infrastructure | Municipal Civil Works | 21 | 10 | 2 |
| 📝 Other | General Grievance Cell | 21 | 14 | 7 |

Escalation: **L1** when overdue → Executive Engineer / Zonal Officer; **L2** after 3 more days → Additional Municipal Commissioner; **L3** after 7 days → Municipal Commissioner. A citizen answering "Not fixed" also raises the escalation by one level.

**`locations.json`**: demo hierarchy for **Lucknow, Patna and Delhi** (3 cities, 9 zones, 14 wards, 22 localities, each with coordinates). Ward names and coordinates are **illustrative**, not official boundaries. Replace them with real municipal ward data for a real deployment.

---

## 7. Datasets used

| # | Dataset | Used for | Source | Notes |
|---|---|---|---|---|
| 1 | **Pothole Detection Dataset** (A. Kumar) | Pothole / road damage vs. normal road ("other") | [kaggle.com/datasets/atulyakumar98/pothole-detection-dataset](https://www.kaggle.com/datasets/atulyakumar98/pothole-detection-dataset) | ~330 labelled road images (`normal/`, `potholes/`) |
| 2 | **Road Issues Detection Dataset** | Potholes, damaged roads, garbage/littering, broken road signs | [kaggle.com/datasets/programmerrdai/road-issues-detection-dataset](https://www.kaggle.com/datasets/programmerrdai/road-issues-detection-dataset) | Compiled from several sources |
| 3 | **Street-Light Dataset** (Team16Project) | Streetlight photos (functional / non-functional), Indian streets (Chennai) | [github.com/Team16Project/Street-Light-Dataset](https://github.com/Team16Project/Street-Light-Dataset) ([paper, Data in Brief](https://www.sciencedirect.com/science/article/pii/S2352340922008630)) | 800+ images |
| 4 | **TACO – Trash Annotations in Context** | Garbage / litter in real environments | [tacodataset.org](http://tacodataset.org/), [github.com/pedropro/TACO](https://github.com/pedropro/TACO) | Downloaded manually with TACO's own script |
| 5 | **Stable Diffusion synthetic images** (generated by us) | All 8 classes, mainly water leakage, drainage, road blockage, damaged infrastructure | Generated by `02_generate_synthetic_images.py` with `stabilityai/sd-turbo` | Prompts and seeds logged for reproducibility |
| 6 | **CivicFix complaint-text corpus** (generated by us) | Text category + severity model | `ml/data/text/civic_complaints.csv` | 12,000 rows, English + Hinglish |
| 7 | **Hand-written evaluation set** (written by us) | Honest test of the text model | `ml/data/text/handwritten_eval.csv` | 30 rows, never used for training |
| 8 | ImageNet (via pretrained weights) | Starting point for MobileNetV3 | torchvision | Transfer learning |

Check each dataset's licence before publishing results. Kaggle and GitHub datasets carry their own licences.

---

## 8. Tools, libraries and technologies

| Area | Tool / library | Version | Why |
|---|---|---|---|
| Android language | Kotlin | 2.0.20 | Modern Android language |
| Android UI | Jetpack Compose + Material 3 | BOM 2024.09.02 | Declarative UI |
| Build | Gradle / Android Gradle Plugin | 8.9 / 8.5.2 | Builds the APK |
| On-device photo AI | ONNX Runtime Android | 1.19.2 | Runs the trained MobileNetV3 on the phone |
| Images | Coil (display), AndroidX ExifInterface (rotation) | 2.7.0 / 1.3.7 | Photo previews and correct orientation |
| Location | Android `LocationManager` + `LocationManagerCompat` | AndroidX Core 1.13.1 | GPS without Google Play Services |
| Storage | JSON files (`org.json`) in app storage | built-in | Simple offline persistence for the prototype |
| Notifications | NotificationCompat | AndroidX | "Please verify" alerts |
| ML language | Python | 3.10+ | Training pipeline |
| Deep learning | PyTorch + torchvision (MobileNetV3-Large) | 2.x / 0.2x | Photo model training |
| **Generative AI** | **Hugging Face diffusers + Stable Diffusion (SD-Turbo)** | latest | Synthetic training photos |
| Classical ML | scikit-learn (TfidfVectorizer, LogisticRegression) | 1.x | Text model |
| Data | pandas, NumPy, Pillow | – | Data handling |
| Model export | ONNX / onnxruntime | – | Phone-compatible model format |
| Datasets | Kaggle API, git | – | Download datasets |

---

## 9. Suggested demo script (5 minutes)

1. **Login as Citizen** → *Report a problem* → press **GPS** (or pick Lucknow → Zone 1 → Ward 12 → Hazratganj).
2. Take a photo, type *"Deep pothole near the school gate, two bikes fell yesterday"*. The **AI suggests Pothole / Road Damage, severity HIGH** (safety keyword "school"). Routing shows **Roads & Public Works, 3-day target**.
3. The app **warns about a similar open complaint** in Hazratganj. Show "Support instead", or continue and **Submit**. Point out the complaint ID and timeline.
4. **Exit → login as Officer (Roads)** → open the complaint → **Assign → Start work → upload after-photo + action → Mark resolved**.
5. **Exit → login as the same citizen** → "Is the problem actually fixed?" → **No** → the complaint is reopened and **escalated**.
6. Open **🤖 → +3 days**, then open the **Supervisor** dashboard to see overdue and escalated complaints.
7. Open **📊 Analytics**: resolution time, hotspots, SLA compliance, repeated problems.

---

## 10. Limitations (be upfront about these in the presentation)

- This is an **academic prototype**. There is no real government integration, and login is simulated. Data is stored **on the device only**, so one phone plays all roles in the demo. A real system would need a backend (for example Node.js/Flask + PostgreSQL, as in report section 4.4) with a REST API, replacing `ComplaintRepository`.
- The location hierarchy and SLA values are **illustrative**.
- The text model's training data is template-generated. Its real-world accuracy is best estimated by the hand-written test (96.7% category, 53.3% severity). **Severity** needs real labelled complaints to improve.
- The photo model's accuracy depends on how many **real** photos you collect. Synthetic images help with rare classes but cannot fully replace real data. Always report the **real-photo test accuracy** printed by step 03.
- The 84 MB debug APK is mostly ONNX Runtime native libraries for all CPU types. A release build with ABI splits would be much smaller.

## 11. Future work (from report section 5)
Public civic map (OpenStreetMap), multilingual and voice reporting, image-based duplicate detection (photo similarity), object detection (bounding boxes) to measure pothole size for severity, and a web dashboard for departments.

---
*"Report. Route. Resolve. Verify."*
