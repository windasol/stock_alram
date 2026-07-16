package com.example.dart;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * docs/ARCHITECTURE.md §3(의존 방향)·§4(도메인 순수성)의 기계 검증.
 * 규칙을 바꾸려면 문서를 먼저 고치고 이 테스트를 맞출 것 — 반대 순서 금지.
 */
@AnalyzeClasses(packages = "com.example.dart", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** §4 — domain은 record + 순수 함수: HTTP·파일IO·HTML파싱·로깅·동시성·jackson-databind 금지(어노테이션만 허용). */
    @ArchTest
    static final ArchRule 도메인은_IO와_인프라_라이브러리를_모른다 =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "java.net..", "java.io..", "java.nio.file..",
                            "org.jsoup..", "org.slf4j..", "com.fasterxml.jackson.databind..",
                            "java.util.concurrent..");

    /** §3 — 컨텍스트 내부 의존은 infra → application → domain 단방향. */
    @ArchTest
    static final ArchRule 도메인은_application과_infra를_모른다 =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("..application..", "..infra..");

    /** §3 — 공유 커널(common)은 어떤 컨텍스트도 모른다(config 포함). */
    @ArchTest
    static final ArchRule common은_어떤_컨텍스트도_모른다 =
            noClasses().that().resideInAPackage("com.example.dart.common..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.example.dart.disclosure..", "com.example.dart.pricetrack..",
                            "com.example.dart.kis..", "com.example.dart.news..",
                            "com.example.dart.notify..", "com.example.dart.llm..",
                            "com.example.dart.trade..", "com.example.dart.config..");

    /** §1 — 타 컨텍스트의 infra 직접 접근 금지: disclosure.infra는 disclosure와 App만 쓴다. */
    @ArchTest
    static final ArchRule disclosure_infra는_컨텍스트_밖에서_못_쓴다 =
            noClasses().that().resideOutsideOfPackages("com.example.dart.disclosure..", "com.example.dart")
                    .should().dependOnClassesThat().resideInAPackage("com.example.dart.disclosure.infra..");

    /** §1 — kis.infra는 컨텍스트 밖에서 공유 KisClient만 허용(토큰 한도 때문의 의도된 공유 어댑터). */
    @ArchTest
    static final ArchRule kis_infra는_컨텍스트_밖에서_KisClient만_쓴다 =
            noClasses().that().resideOutsideOfPackages("com.example.dart.kis..", "com.example.dart")
                    .should().dependOnClassesThat(
                            resideInAPackage("com.example.dart.kis.infra..")
                                    .and(not(name("com.example.dart.kis.infra.KisClient"))));

    /** §1 — 어댑터 컨텍스트(notify·llm)는 발신 전용: 다른 컨텍스트를 역참조하지 않는다. */
    @ArchTest
    static final ArchRule notify와_llm은_다른_컨텍스트를_모른다 =
            noClasses().that().resideInAnyPackage("com.example.dart.notify..", "com.example.dart.llm..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.example.dart.disclosure..", "com.example.dart.pricetrack..",
                            "com.example.dart.kis..", "com.example.dart.news..",
                            "com.example.dart.trade..");
}
