# 번들된 서드파티 구성요소

배포되는 jar 안에 포함되는 코드 목록. 각 항목의 원래 라이선스와 출처.

## Celeritas — LGPL-3.0

패키지: `org.embeddedt.embeddium.*`, `org.taumc.celeritas.*`, `com.mitchej123.lwjgl.*`,
`com.mitchej123.glsm.*`, `assets/sodium/shaders/*`

- 출처: https://github.com/GTNewHorizons/Celeritas (미러) / https://git.taumc.org/embeddedt/celeritas
- Celeritas 는 Embeddium 의 포크이며, Embeddium 은 마지막 FOSS 라이선스 버전의 Sodium
  (CaffeineMC) 과 Oculus 1.7 에 기반한다.
- 라이선스 전문: 이 저장소의 `COPYING`(GPLv3), `COPYING.LESSER`(LGPLv3)

**입수 방식**: Maven 의 `celeritas-common` 은 Java 17 바이트코드에 LWJGL 3 대상이라 1.8.9
(Java 8 + LWJGL 2)에서 쓸 수 없다. 대신 Angelica 릴리스 jar 의 루트 엔트리를 사용한다 —
같은 코드가 이미 클래스 버전 52 로 다운그레이드되어 있고 LWJGL 2 를 대상으로 빌드돼 있다.
추출 절차는 `scripts/extract-celeritas.py` 에 있으며 재현 가능하다.

**변경 사항**: 코드 자체는 수정하지 않았다. 런타임 동작 하나를 믹스인으로 바꾼다 —
`GLRenderDevice$ImmediateCommandList.copyBufferSubData` 를 `@Overwrite` 하여, 드라이버가
`glCopyBufferSubData`(GL 3.1 / ARB_copy_buffer)를 제공하지 않을 때 GL 1.5 왕복으로
대체한다. 기능이 있는 드라이버에서는 원래 GPU 경로를 그대로 탄다.
(`src/celeritas/java/me/natron/mixin/celeritas/MixinImmediateCommandList.java`)

## Angelica — LGPL-3.0

- 출처: https://github.com/GTNewHorizons/Angelica

Angelica 의 코드가 직접 포함되지는 않는다. 다만 이 프로젝트의 브릿지 계층
(`src/celeritas/java/me/natron/render/*`)은 Angelica 의
`com.gtnewhorizons.angelica.rendering.celeritas` 패키지를 1.8.9 API 로 옮긴 것으로,
클래스 구성과 Celeritas 연동 방식을 그대로 따랐다. 파생 저작물로 보는 것이 타당하며
그래서 이 프로젝트도 LGPL-3.0 이다.

대응 관계는 각 소스 파일 주석에 명시돼 있다 (예: `NatronFogService` ↔ `AngelicaFogService`).

## GTNHLib (bytebuf 부분) — LGPL-3.0

패키지: `com.gtnewhorizon.gtnhlib.bytebuf.*` (19 클래스)

- 출처: https://github.com/GTNewHorizons/GTNHLib

LWJGL 2 에는 `org.lwjgl.system.MemoryUtil` 이 없어서 Celeritas 가 오프힙 접근에 이걸 쓴다.
Minecraft 비의존 순수 자바라 해당 패키지만 가져왔다. 수정 없음.

## JvmDowngrader 런타임 — LGPL-2.1

패키지: `xyz.wagyourtail.jvmdg.*` (954 클래스)

- 출처: https://github.com/unimined/JvmDowngrader
- LGPLv2.1 또는 상업 라이선스 중 선택 가능하며, 비상업 사용은 LGPLv2.1 을 따른다.

Angelica 가 배포하는 클래스들은 JvmDowngrader 로 Java 17 → 8 변환된 것이라
`ServiceLoader.stream()`, `List.of()`, record 호출이 jvmdg 스텁 호출로 치환돼 있다.
GTNH 는 이 스텁을 별도 배포하므로 여기서는 `jvmdowngrader-java-api` 를 번들한다.
(스텁이 자신이 흉내내는 버전으로 컴파일돼 있어 클래스 버전 52 로 먼저 내린 뒤 넣는다.)
수정 없음.

## JOML — MIT

패키지: `org.joml.*`

- 출처: https://github.com/JOML-CI/JOML

Celeritas 가 행렬/절두체 연산에 사용한다. **`module-info.class` 만 제거**했다 — 1.8.9 FML 이
ASM 5.0.3 으로 모든 jar 를 스캔하는데 Java 9 module-info 를 만나면 해당 jar 를 통째로
무시해 버리기 때문. 그 외 수정 없음.

## fastutil — Apache-2.0

패키지: `it.unimi.dsi.fastutil.*`

- 출처: https://github.com/vigna/fastutil

Celeritas 의 기본 컬렉션. 수정 없음.

## SpongePowered Mixin — MIT

패키지: `org.spongepowered.asm.*`

- 출처: https://github.com/SpongePowered/Mixin
- 버전 0.7.11 (1.8.9 의 LaunchWrapper + ASM 5 와 맞는 마지막 계열)

수정 없음.

---

## 라이선스 종합

번들 구성요소 중 Celeritas, Angelica(파생), GTNHLib 가 **LGPL-3.0** 이고, 이들이 별도
라이브러리로 링크되는 것이 아니라 jar 안에 함께 배포되며 브릿지가 그 파생 저작물이므로,
**이 프로젝트 전체는 LGPL-3.0 으로 배포한다.**

JvmDowngrader 의 LGPL-2.1 은 LGPL-3.0 과 함께 배포 가능하다(LGPLv2.1 §3 의 상위 버전 선택
조항). MIT / Apache-2.0 항목은 LGPL-3.0 배포에 포함되는 데 제약이 없다.

배포 시 지켜야 할 것:

1. 전체 소스 공개 (이 저장소)
2. `COPYING`, `COPYING.LESSER` 동봉 — jar 안과 저장소 양쪽
3. 변경 사항 명시 — 위 각 항목의 "변경 사항" 절
4. 원저작자 표시 — 이 파일과 README

*이 문서는 각 라이선스 파일에 적힌 내용을 정리한 것이며 법률 자문이 아니다. 상업적 배포나
분쟁 소지가 있는 상황이라면 별도로 확인할 것.*
