# 메카 패럴랙스 라이브 월페이퍼

폰을 기울이면 전경이 화면 위로 떠오르는 3D 패럴랙스에, 미래형 기계가 돌아가는 연출을 더한 안드로이드 라이브 월페이퍼입니다.
Kotlin과 Canvas만 쓰고, 다른 라이브러리는 쓰지 않습니다.

## 화면에서 보이는 것

| 레이어 | 기울기 이동량 | 내용 |
|---|---|---|
| 배경 `background.png` | +5% | 없으면 어두운 금속 패널 + 그리드 |
| 글로우 `glow.png` | +12% | 펄스: 투명도 70→100%, 4초 주기 (심장박동 또는 호흡) |
| 기계 (코드로 그림) | −8% | 눈금 링(24초/바퀴), 반대로 도는 분절 링(14초), 기어(8초), 게이지, 코어로 빨려드는 빛 입자 30개 |
| 전경 그림자 | 전경의 55% | 흐린 드롭 섀도우 → 전경이 떠 보임 |
| 전경 `foreground.png` | −25% (반대 방향) | 없으면 육각형 금속 코어 + 빛나는 렌즈 |
| HUD + 스캔 라인 | −12% | CORE / RPM / SYNC 수치, 5초마다 위→아래로 훑는 빛줄기 |

- **부팅 연출**: 화면을 켤 때마다 링이 6배속으로 돌다가 1.6초 동안 감속하고, 글로우가 번쩍입니다.
- **배터리**: 화면이 꺼지거나 앱이 월페이퍼를 덮으면 센서와 렌더링이 바로 멈춥니다. 보일 때는 최대 60fps로 그립니다.
- **설정 화면**: 깊이 강도 0.5x~2x, 기계 회전 속도 0.25x~3x, 빛 입자 켜기/끄기, 심장박동/호흡 펄스 전환.

## 1. 빌드하고 폰에 설치하기

1. [Android Studio](https://developer.android.com/studio)를 설치합니다.
2. Android Studio → **Open** → 이 `MechParallaxWallpaper` 폴더를 선택합니다.
3. 처음 열면 Gradle 동기화(Sync)가 몇 분 걸립니다. Gradle wrapper나 SDK 설치를 물어보면 **OK / Install**을 누르세요.
4. 폰에서 **개발자 옵션**을 켭니다 (설정 → 휴대전화 정보 → 빌드번호 7번 터치). 개발자 옵션에서 **USB 디버깅**을 켭니다.
5. USB로 폰을 연결하고 위쪽 기기 목록에서 내 폰을 고른 다음 ▶ **Run**을 누릅니다.
6. 폰에 "메카 패럴랙스" 설정 화면이 뜨면 **월페이퍼로 설정하기** → **배경화면 설정**(홈 화면 / 잠금화면 / 둘 다)을 누릅니다.

다시 설정하려면 앱 서랍의 "메카 패럴랙스" 아이콘을 누르면 됩니다.

## 2. 내 사진으로 바꾸기

1. 사진 한 장을 고릅니다 (인물, 피규어, 차, 로봇 등 주인공이 뚜렷한 사진).
2. AI 배경 제거기(remove.bg, 갤럭시 사진 편집의 '객체 잘라내기' 등)로 주인공만 잘라 **투명 PNG**로 저장합니다 → `foreground.png`
3. 원본 사진에서 주인공을 지우거나 흐리게 한 버전 → `background.png`
4. (선택) 주인공 뒤에서 빛날 빛 번짐을 투명 PNG로 만듭니다 → `glow.png`. 없으면 기본 시안 글로우가 쓰입니다.
5. 세 파일을 `app/src/main/res/drawable/`에 넣고 다시 ▶ Run 합니다.

팁:
- 세 장은 **같은 크기·같은 구도**로 만드세요. 그래야 가만히 있을 때 정확히 겹칩니다.
- 파일 이름은 소문자 그대로 `background.png`, `glow.png`, `foreground.png`여야 합니다.
- 기계 링은 화면 가운데에 그려집니다. 주인공이 사진 가운데에 오도록 자르면 링이 주인공 뒤에서 돌아가는 모습이 됩니다.
- 하나만 넣어도 됩니다. 넣지 않은 레이어는 기본 기계 그림으로 채워집니다.

## 3. 느낌 조절하기 (코드)

`MachineScene.kt` 맨 위 숫자만 바꾸면 됩니다.

- `BG_PCT`, `GLOW_PCT`, `MACHINE_PCT`, `FG_PCT`: 레이어별 이동량
- `SHADOW_FOLLOW`: 그림자가 전경을 따라가는 정도 (작을수록 더 높이 떠 보임)
- `PARTICLE_COUNT`, `BOOT_SECONDS`, `PULSE_PERIOD`, `SCAN_PERIOD`
- `CYAN`, `ORANGE`: 메인 색상

`TiltSensor.kt`:
- `RANGE`: 작을수록 조금만 기울여도 크게 움직임
- `SMOOTH_TAU`: 클수록 더 부드럽고 느리게 따라옴

## 파일 구조

```
app/src/main/
├─ AndroidManifest.xml
├─ java/com/kimdaepyo/mechwall/
│  ├─ MechWallpaperService.kt   월페이퍼 엔진, 60fps 루프, 보일 때만 동작
│  ├─ MachineScene.kt           레이어, 기계 애니메이션, 그림자, HUD
│  ├─ TiltSensor.kt             회전 벡터 센서 + 저역통과 필터
│  ├─ SettingsActivity.kt       설정 화면
│  └─ Prefs.kt                  설정 저장 키
└─ res/
   ├─ xml/wallpaper.xml
   ├─ layout/activity_settings.xml
   ├─ drawable/                 ← 여기에 PNG 3장
   └─ values/strings.xml, colors.xml
```

요구 사양: Android 8.0 (API 26) 이상, 회전 벡터 센서가 있는 기기 (대부분의 폰). 센서가 없으면 기울기 없이 기계 애니메이션만 돌아갑니다.
