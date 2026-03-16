package com.galactic.telemetry.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.galactic.telemetry", importOptions = ImportOption.DoNotIncludeTests.class)
class PipesAndFiltersArchitectureTest {

    @ArchTest
    static final ArchRule filters_must_not_use_persistence = noClasses()
            .that()
            .resideInAPackage("..filter..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..repository..", "jakarta.persistence..", "javax.persistence..");

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
