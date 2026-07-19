# Launcher Icon & Splash Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Ship Happens a real launcher icon (white package/box on brand blue) and a matching splash / launch screen on Android and iOS, and carry the brand blue into the main-screen title.

**Architecture:** One box glyph is the source of truth. Android renders it as adaptive-icon vector XML + a core-splashscreen theme. iOS consumes PNGs rasterized by a Pillow script (`scripts/generate_brand_assets.py`) from the glyph geometry and the bundled Hanken font, wired through an asset catalog + LaunchScreen.storyboard. The Compose header wordmark recolors to a new `ShipColors.brand`.

**Tech Stack:** Kotlin/Compose Multiplatform, Android adaptive icons + `androidx.core:core-splashscreen`, iOS XcodeGen + storyboard, Python 3 + Pillow for asset rasterization.

## Global Constraints

- Brand blue: `#1E3A8F` — verbatim, everywhere blue is used.
- Paper background: `#F7F6F3`. White: `#FFFFFF`. Ink (old title): `#17150F`.
- Box glyph path (viewBox 0 0 24), stroked, round join/cap, no fill:
  - Outline: `M12,3 L3,7.5 L3,16.5 L12,21 L21,16.5 L21,7.5 Z`
  - Top seam: `M3,7.5 L12,12 L21,7.5`
  - Front seam: `M12,12 L12,21`
- Android minSdk 26 → adaptive icons only, no legacy density PNGs.
- Do not hard-code hex in Compose; use the `ShipColors.brand` constant.
- Generated iOS PNGs are committed alongside the generator script; the script is re-runnable.

---

### Task 1: Brand color + main-screen title recolor

**Files:**
- Modify: `design/src/commonMain/kotlin/com/shiphappens/design/Theme.kt` (add `brand` to `ShipColors`)
- Modify: `ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListScreen.kt:187` (title color)

- [ ] **Step 1:** In `Theme.kt`, add to `object ShipColors` next to the other colors:
  ```kotlin
  val brand = Color(0xFF1E3A8F)
  ```
- [ ] **Step 2:** In `ListScreen.kt` `Header`, change the "Ship Happens" `Text` `color = ShipColors.ink` to `color = ShipColors.brand`.
- [ ] **Step 3:** Build the UI module to confirm it compiles:
  Run: `./gradlew :ui:compileDebugKotlinAndroid -q` (or `:ui:compileKotlinMetadata`)
  Expected: BUILD SUCCESSFUL.
- [ ] **Step 4:** Commit.
  ```bash
  git add design/src/commonMain/kotlin/com/shiphappens/design/Theme.kt ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListScreen.kt
  git commit -m "[ui] Recolor main-screen title to brand blue"
  ```

---

### Task 2: Brand-asset generator (Pillow) + generated PNGs

**Files:**
- Create: `scripts/generate_brand_assets.py`
- Create (generated): iOS asset-catalog PNGs and the Android splash wordmark PNG (paths below)

**Interfaces:**
- Produces these committed assets, consumed by Tasks 4 and 5:
  - `app-ios/ShipHappens/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` (1024×1024, blue field + white box, no rounding — iOS masks)
  - `app-ios/ShipHappens/Assets.xcassets/BrandTile.imageset/BrandTile{,@2x,@3x}.png` (rounded blue tile + white box; base 96pt)
  - `app-ios/ShipHappens/Assets.xcassets/BrandWordmark.imageset/BrandWordmark{,@2x,@3x}.png` (blue "Ship Happens", Hanken 800; base height ~22pt)
  - `app-android/src/main/res/drawable-nodpi/brand_wordmark.png` (blue "Ship Happens", ~600px wide)

- [ ] **Step 1:** Write `scripts/generate_brand_assets.py`. It draws the box glyph and tile with 4× supersampling + LANCZOS downscale, and renders the wordmark from `design/src/commonMain/composeResources/font/hanken_800.ttf`. Full script:

```python
#!/usr/bin/env python3
"""Regenerate Ship Happens brand raster assets (iOS + Android splash wordmark).
Source of truth is the box glyph geometry below; run from the repo root:
    python3 scripts/generate_brand_assets.py
"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BLUE = (0x1E, 0x3A, 0x8F, 255)
WHITE = (0xFF, 0xFF, 0xFF, 255)
FONT = os.path.join(ROOT, "design/src/commonMain/composeResources/font/hanken_800.ttf")
SS = 4  # supersample

# Box glyph in a 24-unit space: (points, closed?)
OUTLINE = [(12,3),(3,7.5),(3,16.5),(12,21),(21,16.5),(21,7.5)]
SEAM_TOP = [(3,7.5),(12,12),(21,7.5)]
SEAM_FRONT = [(12,12),(12,21)]

def draw_box(draw, size, color, stroke_frac=0.066, pad_frac=0.30):
    """Draw the box glyph centered in a size×size box, glyph inset by pad_frac."""
    inner = size * (1 - 2*pad_frac)
    off = size * pad_frac
    sc = inner / 24.0
    w = max(1, int(round(size * stroke_frac)))
    def P(pts): return [(off + x*sc, off + y*sc) for (x, y) in pts]
    draw.line(P(OUTLINE) + [P(OUTLINE)[0]], fill=color, width=w, joint="curve")
    draw.line(P(SEAM_TOP), fill=color, width=w, joint="curve")
    draw.line(P(SEAM_FRONT), fill=color, width=w, joint="curve")

def app_icon(px=1024):
    img = Image.new("RGBA", (px*SS, px*SS), BLUE)
    draw_box(ImageDraw.Draw(img), px*SS, WHITE, stroke_frac=0.052, pad_frac=0.30)
    img.resize((px, px), Image.LANCZOS).save(
        p("app-ios/ShipHappens/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"))

def tile(base=96):
    for scale, suffix in ((1, ""), (2, "@2x"), (3, "@3x")):
        px = base * scale
        img = Image.new("RGBA", (px*SS, px*SS), (0,0,0,0))
        d = ImageDraw.Draw(img)
        r = int(px*SS*0.25)
        d.rounded_rectangle([0,0,px*SS-1,px*SS-1], radius=r, fill=BLUE)
        draw_box(d, px*SS, WHITE, stroke_frac=0.055, pad_frac=0.28)
        img.resize((px, px), Image.LANCZOS).save(
            p(f"app-ios/ShipHappens/Assets.xcassets/BrandTile.imageset/BrandTile{suffix}.png"))

def wordmark(base_h=44, color=BLUE, out=None, ios=True):
    for scale, suffix in ((1, ""), (2, "@2x"), (3, "@3x")):
        h = base_h * scale
        font = ImageFont.truetype(FONT, int(h*0.82))
        tmp = ImageDraw.Draw(Image.new("RGBA", (10,10)))
        bbox = tmp.textbbox((0,0), "Ship Happens", font=font)
        tw, th = bbox[2]-bbox[0], bbox[3]-bbox[1]
        img = Image.new("RGBA", (tw+int(h*0.4), th+int(h*0.4)), (0,0,0,0))
        ImageDraw.Draw(img).text((int(h*0.2)-bbox[0], int(h*0.2)-bbox[1]),
                                 "Ship Happens", font=font, fill=color)
        if ios:
            img.save(p(f"app-ios/ShipHappens/Assets.xcassets/BrandWordmark.imageset/BrandWordmark{suffix}.png"))
        else:
            img.save(p("app-android/src/main/res/drawable-nodpi/brand_wordmark.png"))
            return  # single density for Android nodpi

def p(rel):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    return path

if __name__ == "__main__":
    app_icon()
    tile()
    wordmark()                 # iOS @1x/2x/3x
    wordmark(base_h=132, ios=False)  # Android single hi-res
    print("brand assets generated")
```

- [ ] **Step 2:** Run it from repo root:
  Run: `python3 scripts/generate_brand_assets.py`
  Expected: prints `brand assets generated`; the PNGs above exist.
- [ ] **Step 3:** Sanity-check dimensions:
  Run: `sips -g pixelWidth -g pixelHeight app-ios/ShipHappens/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`
  Expected: 1024×1024.
- [ ] **Step 4:** Eyeball the app icon and tile (open in Preview / `qlmanage -p`) — white box centered on blue, box not clipped.
- [ ] **Step 5:** Commit script + generated PNGs.
  ```bash
  git add scripts/generate_brand_assets.py app-ios/ShipHappens/Assets.xcassets app-android/src/main/res/drawable-nodpi
  git commit -m "[brand] Add asset generator and rasterized icon/tile/wordmark PNGs"
  ```

---

### Task 3: Android adaptive launcher icon

**Files:**
- Create: `app-android/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `app-android/src/main/res/drawable/ic_launcher_monochrome.xml`
- Create: `app-android/src/main/res/values/ic_launcher_background.xml` (color)
- Create: `app-android/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app-android/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Modify: `app-android/src/main/AndroidManifest.xml` (icon attrs)

- [ ] **Step 1:** `ic_launcher_foreground.xml` — box centered in 108dp, scaled into the safe zone (glyph ≈ 51dp), white stroke:
  ```xml
  <vector xmlns:android="http://schemas.android.com/apk/res/android"
      android:width="108dp" android:height="108dp"
      android:viewportWidth="108" android:viewportHeight="108">
    <group android:scaleX="2.8" android:scaleY="2.8"
           android:translateX="20.4" android:translateY="20.4">
      <path android:strokeColor="#FFFFFF" android:strokeWidth="1.7"
          android:strokeLineJoin="round" android:strokeLineCap="round" android:fillColor="@null"
          android:pathData="M12,3 L3,7.5 L3,16.5 L12,21 L21,16.5 L21,7.5 Z M3,7.5 L12,12 L21,7.5 M12,12 L12,21"/>
    </group>
  </vector>
  ```
- [ ] **Step 2:** `ic_launcher_monochrome.xml` — identical but `android:strokeColor="#000000"` (system tints the alpha).
- [ ] **Step 3:** `values/ic_launcher_background.xml`:
  ```xml
  <resources><color name="ic_launcher_background">#1E3A8F</color></resources>
  ```
- [ ] **Step 4:** `mipmap-anydpi-v26/ic_launcher.xml`:
  ```xml
  <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
  </adaptive-icon>
  ```
- [ ] **Step 5:** `mipmap-anydpi-v26/ic_launcher_round.xml` — identical contents to Step 4.
- [ ] **Step 6:** In `AndroidManifest.xml` `<application>`, add:
  `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"`.
- [ ] **Step 7:** Build the APK resources:
  Run: `./gradlew :app-android:assembleDebug -q`
  Expected: BUILD SUCCESSFUL (no AAPT icon errors).
- [ ] **Step 8:** Commit.
  ```bash
  git add app-android/src/main/res app-android/src/main/AndroidManifest.xml
  git commit -m "[android] Add adaptive launcher icon (white box on brand blue)"
  ```

---

### Task 4: Android splash screen (core-splashscreen)

**Files:**
- Modify: `gradle/libs.versions.toml` (add `androidx-core-splashscreen`)
- Modify: `app-android/build.gradle.kts` (dependency)
- Modify: `app-android/src/main/res/values/themes.xml` (starting theme)
- Modify: `app-android/src/main/AndroidManifest.xml` (application theme → starting theme)
- Modify: `app-android/src/main/kotlin/com/shiphappens/android/MainActivity.kt` (installSplashScreen)

**Interfaces:**
- Consumes: `@drawable/ic_launcher_foreground` (Task 3), `@drawable/brand_wordmark` (Task 2), `ic_launcher_background` color (Task 3).

- [ ] **Step 1:** Add to `gradle/libs.versions.toml` — a `coreSplashscreen = "1.0.1"` version and a library `androidx-core-splashscreen = { module = "androidx.core:core-splashscreen", version.ref = "coreSplashscreen" }`. (Match the existing catalog's formatting.)
- [ ] **Step 2:** In `app-android/build.gradle.kts` dependencies, add `implementation(libs.androidx.core.splashscreen)`.
- [ ] **Step 3:** In `themes.xml`, add the starting theme:
  ```xml
  <style name="Theme.ShipHappens.Starting" parent="Theme.SplashScreen">
      <item name="windowSplashScreenBackground">#F7F6F3</item>
      <item name="windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item>
      <item name="windowSplashScreenIconBackgroundColor">@color/ic_launcher_background</item>
      <item name="android:windowSplashScreenBrandingImage" tools:targetApi="31">@drawable/brand_wordmark</item>
      <item name="postSplashScreenTheme">@style/Theme.ShipHappens</item>
  </style>
  ```
  Add `xmlns:tools="http://schemas.android.com/tools"` to `<resources>`.
- [ ] **Step 4:** In `AndroidManifest.xml`, change `<application android:theme="@style/Theme.ShipHappens"` to `@style/Theme.ShipHappens.Starting`.
- [ ] **Step 5:** In `MainActivity.kt`, add `import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen` and call `installSplashScreen()` as the first line of `onCreate`, before `super.onCreate(...)`.
- [ ] **Step 6:** Build:
  Run: `./gradlew :app-android:assembleDebug -q`
  Expected: BUILD SUCCESSFUL.
- [ ] **Step 7:** Commit.
  ```bash
  git add gradle/libs.versions.toml app-android/build.gradle.kts app-android/src/main/res/values/themes.xml app-android/src/main/AndroidManifest.xml app-android/src/main/kotlin/com/shiphappens/android/MainActivity.kt
  git commit -m "[android] Add splash screen (paper bg, blue icon, wordmark)"
  ```

---

### Task 5: iOS app icon + launch screen

**Files:**
- Create: `app-ios/ShipHappens/Assets.xcassets/Contents.json`
- Create: `app-ios/ShipHappens/Assets.xcassets/AppIcon.appiconset/Contents.json`
- Create: `app-ios/ShipHappens/Assets.xcassets/BrandTile.imageset/Contents.json`
- Create: `app-ios/ShipHappens/Assets.xcassets/BrandWordmark.imageset/Contents.json`
- Create: `app-ios/ShipHappens/LaunchScreen.storyboard`
- Modify: `app-ios/project.yml` (APPICON name, launch storyboard, drop `UILaunchScreen`)

**Interfaces:**
- Consumes the PNGs generated in Task 2.

- [ ] **Step 1:** `Assets.xcassets/Contents.json` — standard `{ "info": { "author": "xcode", "version": 1 } }`.
- [ ] **Step 2:** `AppIcon.appiconset/Contents.json` — single universal iOS marketing icon:
  ```json
  { "images": [ { "filename": "AppIcon-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024" } ],
    "info": { "author": "xcode", "version": 1 } }
  ```
- [ ] **Step 3:** `BrandTile.imageset/Contents.json` and `BrandWordmark.imageset/Contents.json` — 3-scale image sets referencing `Name.png`, `Name@2x.png`, `Name@3x.png` (`"scale": "1x"/"2x"/"3x"`, `"idiom": "universal"`).
- [ ] **Step 4:** `LaunchScreen.storyboard` — a view controller whose view background is paper `#F7F6F3` (RGB 0.969, 0.965, 0.953), a centered `UIImageView` using image `BrandTile` (96×96, centerX, centerY offset −20), and a `UIImageView` using image `BrandWordmark` (centerX, top pinned ~16pt below the tile). Use `launchScreen="YES"`.
- [ ] **Step 5:** In `project.yml`:
  - Remove `UILaunchScreen: {}`; add under `properties:` → `UILaunchStoryboardName: LaunchScreen`.
  - Under target `settings.base`, add `ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon`.
- [ ] **Step 6:** Regenerate the Xcode project:
  Run: `cd app-ios && xcodegen generate`
  Expected: "Created project at .../ShipHappens.xcodeproj".
- [ ] **Step 7 (if Xcode/simulator available):** Build for the simulator and confirm the icon + launch screen. Otherwise verify the asset catalog and storyboard are well-formed (`plutil -lint` the Contents.json files, `xmllint --noout LaunchScreen.storyboard`).
- [ ] **Step 8:** Commit.
  ```bash
  git add app-ios/ShipHappens/Assets.xcassets app-ios/ShipHappens/LaunchScreen.storyboard app-ios/project.yml app-ios/ShipHappens.xcodeproj
  git commit -m "[ios] Add app icon and launch screen (paper bg, blue tile, wordmark)"
  ```

---

### Task 6: End-to-end Android verification

- [ ] **Step 1:** Use the `verify` skill to build, install, and launch on an emulator.
- [ ] **Step 2:** Capture screenshots of: the launcher grid (icon), the splash on cold start, and the main screen (blue title). Confirm the box-on-blue icon, paper splash with blue circle/box + wordmark, and blue "Ship Happens" header.
- [ ] **Step 3:** If anything looks off (glyph clipped, stroke too thin, wordmark oversized), adjust the relevant `pad_frac`/`stroke_frac` (Task 2) or vector `scale`/`strokeWidth` (Task 3) and re-verify.

## Notes / accepted constraints

- Android 12+ masks the splash icon to a **circle**, so the tile reads as a blue circle with the white box on the system splash — accepted, still cohesive. The rounded-square tile is used on iOS's launch screen where we control layout.
- The splash branding wordmark shows on Android 12+ only; API 26–30 show just the centered icon. Acceptable.
