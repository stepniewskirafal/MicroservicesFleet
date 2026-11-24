package com.galactic.fleet.routeplanning;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Route planning must stay a pure computation (ADR-0043).
 *
 * <p>{@code booking} calls this module synchronously, on the thread of the HTTP request, before it
 * opens its transaction. That is acceptable only while planning does no I/O: the first broker,
 * database or HTTP call in here would put a network round-trip — and its failures — back on the
 * reservation path. When planning genuinely needs I/O, this test is where that decision becomes
 * visible: the call has to become asynchronous first.
 */
@AnalyzeClasses(packages = "com.galactic.fleet.routeplanning", importOptions = ImportOption.DoNotIncludeTests.class)
class RoutePlanningPurityTest {

    @ArchTest
    static final ArchRule performs_no_io = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.cloud.stream..",
                    "org.springframework.kafka..",
                    "org.apache.kafka..",
                    "jakarta.persistence..",
                    "org.springframework.data..",
                    "org.springframework.jdbc..",
                    "java.sql..",
                    "org.springframework.web.client..",
                    "org.springframework.web.reactive.function.client..",
                    "java.net.http..")
            .because("booking calls route planning synchronously on the request thread (ADR-0043)");

    @ArchTest
    static final ArchRule publishes_no_events = noClasses()
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.context.ApplicationEventPublisher")
            .because("the route travels back as a return value; announcing it is the business of the caller");
}
