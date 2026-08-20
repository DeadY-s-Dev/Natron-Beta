<img src="assets/logo.png" alt="Natron logo" width="96" height="96">

# Natron

Minecraft 1.8.9 Forge 용 지형 렌더링 최적화 모드.

[Angelica](https://github.com/GTNewHorizons/Angelica) 와 그 렌더러인
**[Celeritas](https://github.com/GTNewHorizons/Celeritas)** 를 1.8.9 로 옮긴 비공식 포트.
Celeritas 는 Embeddium 의 포크이고, Embeddium 은 마지막 FOSS 라이선스 버전의 Sodium
(CaffeineMC) 에서 갈라져 나왔다.

**디스코드:** https://discord.gg/bvDjwkVqV

## 라이선스

**LGPL-3.0.** 번들 구성요소와 각각의 출처는 [THIRD-PARTY.md](THIRD-PARTY.md) 참고.

## 다운로드

**Modrinth 배포가 자리잡으면 그쪽이 유일한 배포처가 된다.** 그 시점부터 GitHub 릴리스는
더 올라오지 않으니, 최신 버전은 Modrinth 에서 받을 것.

이 저장소는 그 뒤로도 소스 공개용으로 계속 유지된다 — LGPL-3.0 이 요구하는 것이 소스이지
바이너리가 아니고, 빌드하고 싶은 사람과 무엇이 바뀌었는지 보려는 사람에게 필요한 것도
소스 쪽이다.

## 요구사항

- Minecraft **1.8.9**
- Forge **11.15.1.2318** (다른 1.8.9 Forge 빌드도 대체로 무방)
- Java **8**

`mods/` 에 jar 하나만 넣으면 된다. 자립형이라 별도로 받을 것이 없다.
클라이언트 전용 — 서버엔 넣지 않아도 된다.

## 성능

실측(저사양 기계, 멀티플레이):

| | FPS |
|---|---|
| OptiFine | 60 ~ 80 |
| Natron | **약 200** |

기계와 상황에 따라 다르다. 이 수치는 한 대에서 나온 것이고, 아래 "검증 범위"를 같이 볼 것.

## OptiFine 과 같이 쓰기

Angelica 는 OptiFine 을 **영구 비호환**으로 명시한다. 원인은 1.7.10 의 렌더링이 전역
`Tessellator` 하나를 공유하는 구조라, OptiFine 과 Angelica 가 같은 클래스를 각자 패치하면
`IncompatibleClassChangeError` 로 JVM 링크 단계에서 죽기 때문이다.

**1.8.9 에는 그 구조가 없다.** Mojang 이 1.7.10 이후 렌더링을 리팩터링해서 호출마다 새로
생기는 `WorldRenderer` + `BlockRendererDispatcher` 로 바뀌었고, 이 포트의 메셔는 그 위에서
동작한다. 그래서 Angelica 를 죽이는 그 충돌은 1.8.9 에 존재하지 않는다.

실제 확인된 것:

| 조합 | 상태 |
|---|---|
| 런처 내장 OptiFine (Lunar, Badlion 등) | **정상** |
| OptiFine 셰이더팩 실시간 토글 + `F3+A` | **정상** |
| OptiFine 을 `mods/` 에 직접 넣은 경우 | **엔티티가 보이지 않음** |

### 알려진 문제: OptiFine 을 모드로 넣으면 엔티티가 안 보임

OptiFine 을 Forge 모드로 넣으면 `OptiFineClassTransformer` 가 LaunchWrapper 단계에서
vanilla 클래스를 직접 패치하는데, 그중에 `GlStateManager` 가 있다. 런처에 내장된
OptiFine 은 이 경로를 타지 않아서 문제가 없다.

이 포트는 Celeritas 가 GL 을 직접 건드린 뒤 vanilla 의 상태를 `glPushAttrib` /
`glPopAttrib` 로 복원하는데, 복원할 상태 집합을 **vanilla `GlStateManager` 가 캐싱하는
필드 목록을 근거로** 정해두었다. OptiFine 이 그 클래스를 자기 버전으로 갈아끼우면 그
전제가 성립하지 않는다 — 지금으로선 이것이 가장 유력한 원인이고, 확정된 것은 아니다.

**당분간은 런처 내장 OptiFine 을 쓰거나, OptiFine 없이 쓸 것.**

## 무엇을 하는가

지형 렌더링을 vanilla 대신 Celeritas 가 맡는다. `RenderGlobal` 의 `setupTerrain`,
`updateChunks`, `renderBlockLayer` 등을 가로채 취소하고 Celeritas 파이프라인으로 보낸다.

Celeritas 가 가져오는 것:

- 리전 단위 버퍼 아레나와 멀티드로우 배치
- 오클루전 그래프 컬링 (렌더 스레드 밖에서 비동기 수행)
- 비동기 멀티스레드 청크 메싱
- 컴팩트 정점 포맷, 반투명 정렬

지오메트리 자체는 vanilla 의 `BlockRendererDispatcher` 가 만든다. Angelica 는 1.7.10 에
데이터 기반 모델 시스템이 없어서 메셔를 직접 구현해야 했지만, 1.8.9 는 baked model 이
있으므로 vanilla 에 맡기고 그 결과를 Celeritas 정점 포맷으로 옮긴다
(`VanillaBufferTranscoder`). AO, 스무스 라이팅, 바이옴 색, Forge 모델 확장이 전부
vanilla 경로를 그대로 타므로 모드 블록도 그대로 그려진다.

## 검증 범위

정직하게 적어둔다. 이 포트는 **한 사람이 한 대에서** 테스트했다.

확인된 것:

- 런처 내장 OptiFine (Lunar) 과 공존, 셰이더팩 실시간 토글 후 `F3+A` 정상
- 저사양 기계에서 OptiFine 대비 약 3배 FPS
- 블록 설치/파괴, 청크 스트리밍, 조명

확인되지 않은 것:

- 다른 GPU 벤더 / 드라이버 (테스트는 한 대에서만)
- 네더, 엔드
- 모드 블록이 많은 환경
- macOS — GPU 가 OpenGL 2.1 컨텍스트만 주면 Celeritas 셰이더(GLSL 330)가 컴파일되지
  않는다. GPU 지원이 부족하면 자동으로 vanilla 렌더러로 넘어가고 로그에 이유를 남긴다.

## 진단

시작 시 게임 디렉터리에 `natron-diagnostics.log` 를 남긴다. GL capability, 선택된 업로드
경로, 렌더러 활성 여부가 적힌다. 문제 제보 시 이 파일을 같이 올려주면 도움이 된다.

## 빌드

**Gradle 데몬은 JDK 21 로 띄워야 한다.** JDK 25 에서는 Gradle 8.x 가 빌드 스크립트를
컴파일하지 못한다. 컴파일 자체는 툴체인이 JDK 8 로 한다.

```bash
export JAVA_HOME=/path/to/jdk-21

./gradlew build -PincludeCeleritas=true
```

산출물은 `build/libs/natron-1.8.9-<version>.jar`.

`-PincludeCeleritas` 없이 빌드하면 렌더러가 빠진 껍데기가 나온다 — 개발 중 컴파일 확인용이고
실제로 쓸 물건이 아니다.

`Lists.newArrayList()` 관련 경고는 무시해도 된다 (Guava 클래스라 난독화 매핑이 없다).

`libs/` 에 들어있는 두 jar 는 빌드에 필요하다. Celeritas 는 Maven 배포본이 Java 17 +
LWJGL 3 대상이라 1.8.9 에서 쓸 수 없어서, Angelica 릴리스 jar 의 루트 엔트리(이미 Java 8 +
LWJGL 2 로 다운그레이드된 것)를 추출해 쓴다. 추출 절차는 `scripts/extract-celeritas.py` 에
있고 재현 가능하다. JOML 은 1.8.9 FML 의 ASM 5.0.3 이 Java 9 module-info 를 만나면 jar 를
통째로 무시하므로 그것만 제거한 빌드다.

## 참고 문서

- [THIRD-PARTY.md](THIRD-PARTY.md) — 번들 구성요소, 라이선스, 출처, 변경 사항
