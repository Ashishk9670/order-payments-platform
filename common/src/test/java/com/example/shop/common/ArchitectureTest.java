package com.example.shop.common;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.junit.jupiter.api.Test;

public class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("com.example.shop.common");


    @Test
    void sharedLibraryKnowsNoService() {
        noClasses().that().resideInAPackage("com.example.shop.common..")
          .should().dependOnClassesThat().resideInAnyPackage("com.example.shop.order..", "com.example.shop.inventory..",
                  "com.example.shop.payment..", "com.example.shop.catalog..", "com.example.shop.notification..",
                  "com.example.shop.gateway..")
                .check(CLASSES);
    }

    /** The event contracts are plain data: no framework, no persistence, no messaging plumbing. */
    @Test
    void eventsArePlainData() {
        noClasses().that().resideInAPackage("..common.events..")
          .should().dependOnClassesThat().resideInAnyPackage("..common.outbox..", "..common.inbox..", "..common.kafka..",
                  "org.springframework..", "jakarta.persistence..")
                .check(CLASSES);
    }

    @Test
    void noPackageCycles() {
        slices().matching("com.example.shop.common.(*)..").should().beFreeOfCycles()
                .check(CLASSES);
    }

    @Test
    void noStandardStreams() {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
                .check(CLASSES);
    }
}
