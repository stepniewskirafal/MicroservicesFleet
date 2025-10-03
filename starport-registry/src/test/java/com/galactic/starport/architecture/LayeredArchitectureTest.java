package com.galactic.starport.architecture;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LayeredArchitectureTest extends ArchUnitSpecification {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void loadClasses() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.galactic.starport");
    }

    @Test
    void layered_dependencies_are_respected() {
        ArchRule rule = layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("API")
                .definedBy(PKG_API)
                .layer("Application")
                .definedBy(PKG_APPLICATION)
                .layer("Domain")
                .definedBy(PKG_DOMAIN)
                .layer("Infrastructure")
                .definedBy(PKG_INFRASTRUCTURE)

                // Which layers are allowed to access other layers
                .whereLayer("API")
                .mayOnlyAccessLayers("Application", "Domain")
                .whereLayer("Application")
                .mayOnlyAccessLayers("Domain")
                .whereLayer("Domain")
                .mayNotAccessAnyLayer()
                .whereLayer("Infrastructure")
                .mayOnlyAccessLayers("Domain")

                // Which layers are allowed to be accessed by others
                .whereLayer("API")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("Application")
                .mayOnlyBeAccessedByLayers("API")
                .whereLayer("Domain")
                .mayOnlyBeAccessedByLayers("API", "Application", "Infrastructure")
                .whereLayer("Infrastructure")
                .mayOnlyBeAccessedByLayers("Application")
                .allowEmptyShould(true);
        rule.check(importedClasses);
    }
}
