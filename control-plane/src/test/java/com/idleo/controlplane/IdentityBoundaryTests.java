package com.idleo.controlplane;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The identity provider's subject and issuer never leave the identity module (ADR 0009). They live
 * only in the module's internals and in the raw ID token, so nothing outside the module may touch
 * either.
 */
class IdentityBoundaryTests {

    @Test
    void onlyTheIdentityModuleSeesProviderIds() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.idleo.controlplane");

        noClasses()
                .that()
                .resideOutsideOfPackage("com.idleo.controlplane.identity..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.idleo.controlplane.identity.internal..")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.security.oauth2.jwt.Jwt")
                .because("the provider's subject and issuer must never leave the identity module")
                .check(classes);
    }
}
