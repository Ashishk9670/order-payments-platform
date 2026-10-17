package com.example.shop.catalog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Executable architecture rules: the boundaries described in the README fail the build if someone crosses them. */
public class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("com.example.shop.catalog");


    /** Each service owns its data and talks to others only through Kafka events or HTTP, never by importing their code. */
    @Test
    void servicesDoNotDependOnEachOther() {
        noClasses().that().resideInAPackage("com.example.shop.catalog..")
          .should().dependOnClassesThat().resideInAnyPackage("com.example.shop.order..", "com.example.shop.inventory..", "com.example.shop.payment..", "com.example.shop.notification..", "com.example.shop.gateway..")
                .check(CLASSES);
    }

    @Test
    void constructorInjectionOnly() {
        fields().should().notBeAnnotatedWith(Autowired.class)
          .because("constructor injection keeps dependencies explicit and objects testable")
                .check(CLASSES);
    }

    @Test
    void noStandardStreams() {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
          .because("use the logger")
                .check(CLASSES);
    }

    @Test
    void noJavaUtilLogging() {
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING
                .check(CLASSES);
    }
}
