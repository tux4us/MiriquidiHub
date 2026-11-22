










## Permissions and APIs Audit

### Hint: Deducing Core Functionality

Since you must determine if certain permissions are justified by the app's "core
purpose", use these fast heuristics:
1. **The "Broken" Test**: Is the feature essential to the app's primary purpose?
   If the app would still be functional and useful without the feature, it is
   NOT core functionality.
2. **Manifest Intent**: Review `AndroidManifest.xml`. The name of the `LAUNCHER`
   Activity and specialized `<intent-filter>` declarations (like default SMS
   handlers) strongly indicate the app's main purpose.
3. **Naming**: The package name (`io.github.tux4us.miriquidihub`) and app label
   (`app_name`) often describe the app's purpose explicitly.
4. **Execution Context**: Usage in classes like `BackupManager` suggest core
   functionality, whereas usage in `AdHelper`, `CrashReporter`, or
   `AnalyticsManager` indicates secondary features.
5. **Mandatory Rule**: Secondary features like **advertising, analytics, or
   social sharing never justify** restricted permissions like Background
   Location, All Files Access, or Broad Media Access.

---

### Policies to Verify

#### Photo and Video Access Policy (Policy ID: photo_video_access_policy)

- **Goal**: Evaluate if the app's core functionality justifies broad access to
  photos or if it should migrate to the Android Photo Picker.
- **The Policy Spirit**: User privacy is paramount. Apps should only request
  broad media storage permissions if they are dedicated media managers (like
  Gallery or Backup apps). For standard tasks like profile picture uploads,
  custom sharing, or attaching media, developers must use scoped APIs to prevent
  security risks.
- **Evidence**:
  - `app/src/main/java/io/github/tux4us/miriquidihub/DeviceReportActivity.kt (Pattern: image/*)`
- `app/src/main/java/io/github/tux4us/miriquidihub/PermissionOverviewActivity.kt (Pattern: READ_MEDIA_IMAGES)`
- `app/src/main/java/io/github/tux4us/miriquidihub/ScreenshotService.kt (Pattern: MediaStore)`
  **Relevant Permissions Requested**:
  - `android.permission.WRITE_EXTERNAL_STORAGE`
  
  **Target SDK**: `36`
  
- **Common Evaluation Matrix**:
  | Target SDK | Broad Media Permission Requested? | Condition / Context Checked | Severity | Direct Actionable Recommendation |
  | :--- | :--- | :--- | :--- | :--- |
  | **33 or higher** | Yes | App requests broad media access (e.g. `READ_MEDIA_IMAGES`), but features only require user-selected media. | `IMPORTANT` | Migrate to the **Android Photo Picker** (`MediaStore.ACTION_PICK_IMAGES`) which does not require any permission prompt. |
  | **Any** | Yes | App requests broad storage/media permissions but is not a dedicated media manager (e.g., a social or utility app). | `IMPORTANT` | Migrate to the **Android Photo Picker** for single-item or multi-item media selection. |

- **Domain-Specific Heuristics (Strictly Bounded)**:
  Look beyond standard native file pickers. Extrapolate using this heuristic:
  1. **User-Selected Media Heuristic**: Analyze where the code handles selected
     files. If images or videos are loaded strictly via a user-facing button
     click (e.g., "Upload Avatar", "Share Photo", "Attach File") and processed
     one-at-a-time, broad filesystem media permissions are structurally
     unnecessary. Flag an `IMPORTANT` violation and recommend Photo Picker
     migration.

#### All Files Access Policy (Policy ID: all_files_access_policy)

- **Goal**: Evaluate if the app's core purpose justifies the high-risk
  `MANAGE_EXTERNAL_STORAGE` permission.
- **The Policy Spirit**: Full filesystem access is a restricted privilege. The
  Play Store strictly limits `MANAGE_EXTERNAL_STORAGE` to apps where full disk
  reads/writes are critical to the core purpose (e.g., file managers, antivirus
  scanner, backup tools). Non-compliant apps must utilize Scoped Storage or
  Storage Access Framework (SAF).
- **Evidence**:
  - `app/src/main/java/io/github/tux4us/miriquidihub/AppLogActivity.kt (Pattern: text/plain)`
- `app/src/main/java/io/github/tux4us/miriquidihub/DeviceReportActivity.kt (Pattern: text/plain)`
- `app/src/main/java/io/github/tux4us/miriquidihub/FileManagerActivity.kt (Pattern: WRITE_EXTERNAL_STORAGE)`
  **Relevant Permissions Requested**:
  - `android.permission.MANAGE_EXTERNAL_STORAGE`
- **Common Evaluation Matrix**:
  | Core App Purpose | Is Permission Justified? | Severity | Direct Actionable Recommendation |
  | :--- | :--- | :--- | :--- |
  | **File Manager, Antivirus, Backup/Restore, or Document Manager** | Yes (Compliant) | None | No action needed; core purpose justifies the restricted permission. |
  | **Standard Utility, Social Media, Game, or Productivity App** | No (Violation) | `CRITICAL` | Remove `MANAGE_EXTERNAL_STORAGE` from the Manifest. For document picking or local file saving, migrate to the **Storage Access Framework (SAF)**. |

- **Domain-Specific Heuristics (Strictly Bounded)**:
  Surgically audit how the filesystem is queried:
  1. **Scoped Storage Sufficiency**: Check the files using the storage APIs. If
     the app is using filesystem pathways solely to log diagnostics, store
     custom caches, or save simple download artifacts, broad access is not
     justified. Recommend using app-specific directories
     (`Context.getExternalFilesDir()`) which require zero permissions.

#### Location Access Policy (Policy ID: location_access_policy)

- **Goal**: Verify that location access is essential, uses minimum scope, and is
  properly disclosed.
- **The Policy Spirit**: User tracking is extremely sensitive. Apps must collect
  the minimum scope of location required (approximate vs precise), provide clear
  prominent disclosures, and must never utilize background tracking unless it is
  essential for safety, navigation, or physical fitness features.
- **Evidence**:
  
  - **Precise Location usage**:
    - `app/src/main/java/io/github/tux4us/miriquidihub/HardwareTestActivity.kt (Pattern: ACCESS_FINE_LOCATION)`
- `app/src/main/java/io/github/tux4us/miriquidihub/LocationShareActivity.kt (Pattern: ACCESS_FINE_LOCATION)`
- `app/src/main/java/io/github/tux4us/miriquidihub/LocationShareService.kt (Pattern: ACCESS_FINE_LOCATION)`
  
  
  - **Approximate Location usage**:
    - `app/src/main/java/io/github/tux4us/miriquidihub/HardwareTester.kt (Pattern: LocationManager)`
- `app/src/main/java/io/github/tux4us/miriquidihub/LocationShareActivity.kt (Pattern: ACCESS_COARSE_LOCATION)`
- `app/src/main/java/io/github/tux4us/miriquidihub/LocationShareService.kt (Pattern: LocationManager)`
  
  **Relevant Permissions Requested**:
  - `android.permission.ACCESS_FINE_LOCATION`
- `android.permission.ACCESS_COARSE_LOCATION`
  
  **Target SDK**: `36`
  
- **Common Evaluation Matrix**:
  | Scope | Target SDK | Finding / Condition Checked | Severity | Direct Actionable Recommendation |
  | :--- | :--- | :--- | :--- | :--- |
  | **Foreground** | Any | Requests `ACCESS_FINE_LOCATION` but features only require city-level or approximate weather/search features. | `IMPORTANT` | Downgrade Manifest to `ACCESS_COARSE_LOCATION` to respect the minimum scope mandate. |
  | **Foreground** | Any | App purpose (`app_name`) does not imply location, yet foreground tracking is used, and prominent disclosure alert code is missing. | `IMPORTANT` | Implement a **Prominent In-App Disclosure** dialog explaining what location data is collected *before* requesting foreground permission. |
  | **Background** | Any | Requests `ACCESS_BACKGROUND_LOCATION` solely for advertising, marketing, or general analytics. | `CRITICAL` | **High-Risk Violation**: Completely remove background location collection from the codebase. |
  | **Background** | Any | Requests background location, but features could operate with foreground location access. | `IMPORTANT` | Downgrade the feature to foreground-only location tracking and remove background permission. |
  | **Background** | Any | Background location is legitimate, but prominent disclosure does not mention "location" and "when the app is closed or not in use." | `IMPORTANT` | Update the Prominent Disclosure text to explicitly state "location" and "when closed or not in use". |
  | **Foreground** | **37 or higher** | Precise location is requested on Android 17+. | `SUGGESTION` | Migrate to the **Location Button** API as the minimum scope mechanism for precise foreground location. |

- **Domain-Specific Heuristics (Strictly Bounded)**:
  Actively trace custom background workers and disclosures:
  1. **The Foreground Sufficiency Test**: Analyze background threads,
     `WorkManager` tasks, or background services triggering location updates. If
     the background process performs syncing or location calculations that could
     be deferred to when the user is actively viewing the app, flag an
     `IMPORTANT` violation.
  2. **The Reasonable Expectation Test**: If the app label (`app_name`) or
     packages suggest a utility that shouldn't logically track location,
     evaluate any indirect geo-tracking (such as geo-lookup of network IP
     addresses, or sending local Wi-Fi SSID logs off-device). If found, flag an
     `IMPORTANT` missing prominent disclosure.

#### Foreground Services (Policy ID: foreground_services_policy)

- **Goal**: Verify the declaration and justification of foreground services.
- **The Policy Spirit**: Foreground services keep processes alive in the
  background and must be highly visible to users. Every declared service must
  have an appropriate `foregroundServiceType` defined in the Manifest, and
  special types like `specialUse` require specific tag property justifications.
- **Evidence**:
  - `app/src/main/java/io/github/tux4us/miriquidihub/LocationShareActivity.kt (Pattern: startForeground)`
- `app/src/main/java/io/github/tux4us/miriquidihub/LocationShareService.kt (Pattern: FOREGROUND_SERVICE)`
- `app/src/main/java/io/github/tux4us/miriquidihub/ScreenshotActivity.kt (Pattern: startForeground)`
- `app/src/main/java/io/github/tux4us/miriquidihub/ScreenshotService.kt (Pattern: FOREGROUND_SERVICE)`
- `app/src/main/java/io/github/tux4us/miriquidihub/SimpleScreenshotActivity.kt (Pattern: startForeground)`
- `app/src/main/java/io/github/tux4us/miriquidihub/SimpleScreenshotService.kt (Pattern: startForeground)`
- `app/src/main/java/io/github/tux4us/miriquidihub/files/TransferManager.kt (Pattern: startForeground)`
- `app/src/main/java/io/github/tux4us/miriquidihub/files/TransferService.kt (Pattern: FOREGROUND_SERVICE)`
  **Relevant Permissions Requested**:
  - `android.permission.FOREGROUND_SERVICE`
  
  **Target SDK**: `36`
  
- **Common Evaluation Matrix**:
  | Service Configuration | Justification Check | Severity | Direct Actionable Recommendation |
  | :--- | :--- | :--- | :--- |
  | **Missing type tag** | Foreground service is declared but lacks a `foregroundServiceType` attribute. | `CRITICAL` | Add the appropriate `android:foregroundServiceType` attribute to the service declaration in the Manifest. |
  | **Lacks specialUse property** | Service type is `specialUse`, but Manifest lacks the required `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" ...>` tag. | `CRITICAL` | Add the `<property>` tag inside the service block with a valid subtype string. |
  | **Type Misalignment** | Declared FGS type does not logically align with the app's core purpose. | `IMPORTANT` | Re-align FGS type to match app features, or migrate background operations to **WorkManager** if user-visible foreground presence is not justified. |
  | **Declaration Reminder** | Foreground service is declared (even if type is correct). | `SUGGESTION` | **Play Console Declaration Required**: For apps targeting Android 14+, you must complete a Foreground Service declaration in the Play Console (App content section) for each type used, providing a functional description, user impact video, and a specific use case selection. |

- **Domain-Specific Heuristics (Strictly Bounded)**:
  Critique specialUse justifications and service behavior:
  1. **Justification String Audit**: Read the text of the `<property>` tag for
     `specialUse`. If the text contains weak, boilerplate, or placeholder
     justifications (e.g., "requires background process for app to run"), flag
     an `IMPORTANT` violation warning the developer that Google Play reviewers
     will reject this service.
  2. **Notification Integrity**: Verify if the FGS implementation creates a
     valid user-facing notification. If no `startForeground()` or notification
     builder logic is associated with the service initiation, flag an
     `IMPORTANT` violation.
  3. **Play Console Declaration Confirmation**: If any foreground service is
     used, flag a `SUGGESTION` to remind the developer that a specialized
     declaration form in the Play Console is mandatory, requiring a video
     demonstration of the feature.

#### Accessibility API Policy (Policy ID: accessibility_api_policy)

- **Goal**: Verify the configuration and justification of Accessibility
  Services.
- **The Policy Spirit**: Accessibility APIs provide deep system access to assist
  users with disabilities. Using these APIs for non-accessibility tasks (like UI
  automation, screen scraping, background monitoring, or ad blocking) is
  strictly prohibited by Google Play and causes immediate app rejection.
- **Evidence**:
  - `app/src/main/java/io/github/tux4us/miriquidihub/ScreenshotActivity.kt (Pattern: AccessibilityService)`
- `app/src/main/java/io/github/tux4us/miriquidihub/ScrollingScreenshotService.kt (Pattern: AccessibilityService)`
  **Relevant Permissions Requested**:
  - `android.permission.BIND_ACCESSIBILITY_SERVICE`
- **Common Evaluation Matrix**:
  | Service Configuration | Real Code Usage | Justified? | Severity | Direct Actionable Recommendation |
  | :--- | :--- | :--- | :--- | :--- |
  | **`isAccessibilityTool="true"`** | Code performs screen scraping, ad blocking, or automated click routines for standard users. | No (Violation) | `CRITICAL` | **High Risk of Play Store Rejection**: Remove accessibility helper configs. Migrate UI automation to standard Android testing libraries. |
  | **`isAccessibilityTool` false/missing** | Code implements an accessibility listener but is not a dedicated helper app. | No prominent disclosure | `CRITICAL` | **High Risk of Play Store Rejection**: Because you request accessibility permission for a standard utility, you MUST implement an in-app **Prominent Disclosure and Affirmative Consent screen** before asking the user to enable the service, otherwise your app will be rejected. |

- **Domain-Specific Heuristics (Strictly Bounded)**:
  Trace the ingestion of accessibility events:
  1. **Telemetry Siphoning**: Scan the Accessibility Service methods
     (`onAccessibilityEvent`). If the service captures on-screen text,
     notifications, or keystrokes and routes them to local caches, shared
     preferences, or off-device network logging endpoints, flag a `CRITICAL`
     violation under the Accessibility API policy.

#### Package Visibility (Policy ID: package_visibility_policy)

- **Goal**: Evaluate if the app's core functionality justifies broad visibility
  into installed apps (`QUERY_ALL_PACKAGES` or related package APIs).
- **The Policy Spirit**: The list of installed apps reveals sensitive user
  habits. The Play Store strictly restricts the `QUERY_ALL_PACKAGES` permission
  to apps that directly manage device safety (Antivirus, File Managers, Device
  Search).
- **Evidence**:
  - `app/src/main/java/io/github/tux4us/miriquidihub/AppInfoDialog.kt (Pattern: getPackageInfo)`
- `app/src/main/java/io/github/tux4us/miriquidihub/AppLogAccess.kt (Pattern: getPackageInfo)`
- `app/src/main/java/io/github/tux4us/miriquidihub/AppSelectionDialog.kt (Pattern: getPackageInfo)`
  **Relevant Permissions Requested**:
  - `android.permission.QUERY_ALL_PACKAGES`
- **Common Evaluation Matrix**:
  | Core App Purpose | Permission Requested | Justified? | Severity | Direct Actionable Recommendation |
  | :--- | :--- | :--- | :--- | :--- |
  | **Antivirus, File Manager, or Device Search** | `QUERY_ALL_PACKAGES` | Yes (Compliant) | None | No action needed. |
  | **Any App** | `QUERY_ALL_PACKAGES` | No, used for ads, analytics, or secondary marketing. | `IMPORTANT` | **High-Risk Violation**: Remove the permission from the Manifest. Use specific `<queries>` intent declarations if package checks are strictly required for sharing/integration. |

- **Domain-Specific Heuristics (Strictly Bounded)**:
  Examine indirect queries:
  1. **Indirect Package Searching**: Check if the code queries package details
     dynamically using `PackageManager.getInstalledPackages()` or checks intents
     in loops. If the intent lists are used to deduce if competitor apps or
     advertising profiles exist, flag an `IMPORTANT` violation of package
     visibility guidelines.

#### Audio Recording Policy (Policy ID: audio_recording_policy)

- **Goal**: Evaluate if the app's core functionality justifies broad access to
  audio recording.
- **The Policy Spirit**: Unprompted audio recording is a severe privacy breach.
  Apps should request microphone access strictly for user-visible, time-bounded
  actions. Target SDK 34+ encourages using the system-managed Microphone Button
  for temporary needs.
- **Evidence**:
  - `app/src/main/java/io/github/tux4us/miriquidihub/BatteryMonitorActivity.kt (Pattern: record)`
- `app/src/main/java/io/github/tux4us/miriquidihub/HardwareTestActivity.kt (Pattern: RECORD_AUDIO)`
- `app/src/main/java/io/github/tux4us/miriquidihub/HardwareTester.kt (Pattern: AudioRecord)`
  **Relevant Permissions Requested**:
  - `android.permission.RECORD_AUDIO`
- `android.permission.WRITE_EXTERNAL_STORAGE`
  
  **Target SDK**: `36`
  
- **Common Evaluation Matrix**:
  | Target SDK | Audio Recording Trigger Context | Severity | Direct Actionable Recommendation |
  | :--- | :--- | :--- | :--- |
  | **34 or higher** | App requests broad `RECORD_AUDIO` permission for occasional, user-initiated vocal input or short recording. | `IMPORTANT` | Migrate to the **Android Microphone Button** API to process temporary audio securely. |
  | **Any** | App captures audio for secondary features (analytics, user-agent details, etc.) without explicit user control. | `IMPORTANT` | Remove microphone permissions. For general searches, integrate standard Android Speech Recognizer intents. |

- **Domain-Specific Heuristics (Strictly Bounded)**:
  Verify recording indicators and threads:
  1. **Continuous Capture**: Scan for active recorder threads (`AudioRecord` or
     `MediaRecorder`). If recording loops can be active when the app is
     minimized or without a visible user-facing indicator, flag this as a
     `CRITICAL` violation.

#### Files and Docs Access Policy (Policy ID: files_and_docs_policy)

- **Goal**: Evaluate if broad file access (non-media) is justified or if the
  Storage Access Framework should be used.
- **The Policy Spirit**: Storage isolation (Scoped Storage) is mandatory on
  modern Android versions. Broad access to shared files is heavily restricted.
  Standard files, documents, and download folders should be navigated using
  scoped contracts to prevent global filesystem snooping.
- **Evidence**:
  - `app/src/main/java/io/github/tux4us/miriquidihub/AppLogActivity.kt (Pattern: text/plain)`
- `app/src/main/java/io/github/tux4us/miriquidihub/DeviceReportActivity.kt (Pattern: text/plain)`
- `app/src/main/java/io/github/tux4us/miriquidihub/FileManagerActivity.kt (Pattern: WRITE_EXTERNAL_STORAGE)`
  **Relevant Permissions Requested**:
  - `android.permission.WRITE_EXTERNAL_STORAGE`
  
  **Target SDK**: `36`
  
- **Common Evaluation Matrix**:
  | Target SDK | Storage Configuration | Justified? | Severity | Direct Actionable Recommendation |
  | :--- | :--- | :--- | :--- | :--- |
  | **30 or higher** | App requests broad `READ_EXTERNAL_STORAGE` or `WRITE_EXTERNAL_STORAGE` for simple document selection. | No (Violation) | `IMPORTANT` | **Scoped Storage Mandate**: Migrate your document/file selections to the **Storage Access Framework (SAF)** (`Intent.ACTION_OPEN_DOCUMENT`). |

- **Domain-Specific Heuristics (Strictly Bounded)**:
  Critique external directory creation:
  1. **Manual File Sync Heuristic**: Check if the code creates custom root-level
     folders on external storage (e.g.
     `Environment.getExternalStorageDirectory() + "/my_folder"`). If directories
     are created for standard document outputs or logging, flag an `IMPORTANT`
     violation. Direct the developer to utilize scoped storage paths.

## Output schema

Save final JSON output to `/home/tuxus/Android/MiriquidiHub/.scratch/play_policy_insights_f98e348f-3ba7-43ca-8a9d-55eaca20bf28/worker_{{GOAL_NAME}}.json`.

```json
{
  "domain": "Permissions and APIs",
  "findings": [
    {
      "policy_id": "STRING_VALUE (The exact Policy ID, e.g., photo_video_access_policy)",
      "issue_summary": "STRING_VALUE",
      "severity": "CRITICAL | IMPORTANT | SUGGESTION",
      "files_involved": ["STRING_VALUE"],
      "evidence": "STRING_VALUE",
      "recommendation": "STRING_VALUE"
    }
  ]
}
```

# Execution Mandates

### Technical Rules

1.  **Absolute Paths Only**: Always resolve and use absolute paths.
2.  **Containment**: Write all artifacts strictly within `/home/tuxus/Android/MiriquidiHub/.scratch/play_policy_insights_f98e348f-3ba7-43ca-8a9d-55eaca20bf28`.
3.  **Fail-fast**: If any required input file is missing, stop immediately and
    report the failure.

### Surgical Input Protocol & Efficient Search (MANDATORY)

-   **Direct Evidence First**: Prioritize files listed in the **Context &
    Evidence** sections. Use the provided file/line evidence (e.g., from Data
    Sources or Sinks) to jump directly to the relevant code. Do not perform
    broad workspace searches if these surgical starting points are available.
-   **Path Filtering Over File Crawling**: Locate target files by name, path, or
    extension *first* using directory/file listing tools before performing any
    text/content-based searches. Restrict searches and file reads strictly to
    the target `/home/tuxus/Android/MiriquidiHub`.
-   **Strict Exclusions (The Noise Wall)**: Configure search, glob, and find
    tools to ignore build, cache, dependencies, and testing folders. You MUST
    exclude matches from: `**/build/**`, `**/.gradle/**`, `**/.scratch/**`,
    `**/androidTest/**`, `**/test/**`, `**/node_modules/**`.
-   **Targeted Extensions**: Restrict content searches and file reads strictly
    to source and configuration files: `.java`, `.kt`, `.xml`, `.gradle`, `.kts`
    (and `.js`, `.ts`, `.jsx`, `.tsx`, `.dart` if a hybrid/cross-platform
    environment is analyzed). Never search or read inside compiled `.class`
    files, binary resources, or output assets.
-   **Surgical Queries & Limiters**: Use highly specific search patterns (e.g.,
    search for `getLastKnownLocation` or `deleteAccount` instead of general
    words like `location` or `delete`). If search tools support limits or
    pagination, cap results at a maximum of 50 matches. Do not load unlimited
    search results into your context window.
-   **Parallel Reading Required (Turn Efficiency)**: You are operating under a
    strict maximum turn limit. To prevent timeouts, you MUST request to read
    multiple target files concurrently in a single response. Do not read the
    evidence files sequentially one-by-one. Issue all of your file-reading tool
    calls simultaneously whenever possible.

### Evidentiary Standard & Guardrails (CRITICAL)

To prevent over-auditing, false positives, and speculative "prosecution" of
compliant code during extrapolation:

1.  **Presumption of Compliance**: Treat code as compliant unless there is
    *definitive, visible evidence* in the provided files of a policy violation.
    If code is ambiguous, or if network/database logic is hidden behind
    abstractions (e.g., calling an interface or repository method like
    `clearSession()`), you must assume standard compliant behavior. Do NOT guess
    or speculate about what happens behind interfaces.
2.  **Benefit of the Doubt**: When compliance cannot be strictly verified due to
    code abstractions or missing source file contexts, you must downgrade your
    finding:
    -   Never flag a `🔴 Critical` or `🟡 Important` finding based on suspicion or
        lack of context.
    -   Instead, output a `🔵 Suggestion` (informational) to advise the developer
        on what to double-check in their backend or configuration.
3.  **Exclusion of Local State**: Local-only processing (e.g., caching theme
    settings, user-selected visual configurations, or on-device-only database
    operations) is explicitly exempt from Data Safety collection or Account
    Deletion mandates.
4.  **Concrete Attributions**: Every `🔴 Critical` or `🟡 Important` finding must
    cite the exact file, line number, or configuration block containing the
    direct violation. If you cannot cite the exact line of code containing the
    violation, you cannot flag it as a violation.
5.  **Empty-List Discipline**: If no policy violations, discrepancies, or review
    items are identified during your audit, you MUST represent this as an empty
    array `[]` for that field (e.g., `"findings": []`, `"verified_findings":
    []`, or `"manual_verification_required": []`). **DO NOT** populate arrays
    with "dummy" objects, placeholder strings, or `"N/A"` / `"None"` values.
6.  **Heuristics & Extrapolation Boundaries**: Whenever applying specific
    heuristics defined in your goal (e.g., searching for implicit logger leaks
    or
    SDK siphoning), you must strictly bound them to the provided evidence and
    their immediate callers. You are strictly forbidden from initiating broad,
    unbounded searches for custom paths or variables across the wider codebase.
    Base your extrapolation only within the specific files already provided to
    you in the prompt.

### Finalization & Output Mandates (CRITICAL)

-   **Iterative Saving**: If your investigation requires multiple steps, save
    partial or intermediate JSON states to disk as you progress. Do not hold all
    data in memory until the very end to prevent data loss upon interruption.
-   **Strict File Output (NO TRIPLE BACKTICKS)**: You MUST save your final JSON
    output to disk at the exact path specified in the goal schema using your
    file-writing capabilities.
    -   **CRITICAL: The content written to the file MUST be pure, raw JSON. DO
        NOT wrap the contents inside the JSON file with Markdown code blocks
        (such as triple backticks `json ...`). Writing markdown blocks into the
        file makes the JSON unparseable by the compiler.**
-   **NO Chat Summaries**: **MANDATORY: DO NOT summarize your findings, explain
    your reasoning, or output JSON in your final chat response.** Your chat
    output wastes context and is ignored by the orchestrator.
-   **Verification Before Termination**: You MUST only terminate and return the
    "SUCCESS" string *after* you have explicitly verified that your JSON file
    successfully wrote to disk and contains valid JSON (e.g., by reading the
    file back or checking the directory contents).
-   **Final response**: Your final response MUST be exactly the word: "SUCCESS"
    and nothing else.
