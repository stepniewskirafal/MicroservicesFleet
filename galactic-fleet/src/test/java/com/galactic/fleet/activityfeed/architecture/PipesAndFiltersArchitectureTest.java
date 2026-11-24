package com.galactic.fleet.activityfeed.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Keeps the {@code activityfeed} module a Pipes &amp; Filters design (ADR-0022, ADR-0043): filters
 * are plain functions that know nothing about how they are composed or delivered, and the records
 * passed between them are plain data.
 */
@AnalyzeClasses(packages = "com.galactic.fleet.activityfeed", importOptions = ImportOption.DoNotIncludeTests.class)
class PipesAndFiltersArchitectureTest {

    @ArchTest
    static final ArchRule filters_must_not_use_persistence = noClasses()
            .that()
            .resideInAPackage("..filter..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..repository..", "jakarta.persistence..", "javax.persistence..");

    @ArchTest
    static final ArchRule filters_must_not_talk_to_the_broker = noClasses()
            .that()
            .resideInAPackage("..filter..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.cloud.stream..", "org.springframework.kafka..", "org.apache.kafka..")
            .because("a filter computes; sending the result is the job of the listener at the end of the pipe");

    @ArchTest
    static final ArchRule models_must_not_depend_on_spring = noClasses()
            .that()
            .resideInAPackage("..model..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule filters_must_implement_function = classes()
            .that()
            .resideInAPackage("..filter..")
            .and()
            .areTopLevelClasses()
            .should()
            .implement(java.util.function.Function.class);

    @ArchTest
    static final ArchRule filters_must_not_depend_on_pipeline_config = noClasses()
            .that()
            .resideInAPackage("..filter..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..pipeline..");

    @ArchTest
    static final ArchRule models_must_be_records = classes()
            .that()
            .resideInAPackage("..model..")
            .and()
            .areNotEnums()
            .and()
            .areNotInnerClasses()
            .should()
            .beRecords();
}
