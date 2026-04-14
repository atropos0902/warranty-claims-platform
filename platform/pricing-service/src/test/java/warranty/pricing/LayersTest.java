package warranty.pricing;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import org.junit.jupiter.api.Test;

class LayersTest {
    private static final JavaClasses CLASSES =
            new ClassFileImporter().withImportOption(new DoNotIncludeTests()).importPackages("warranty.pricing");

    @Test
    void theRulesArePlainJava() {
        noClasses().that().resideInAPackage("..pricing.domain..")
                .should().dependOnClassesThat().resideOutsideOfPackages("java..", "warranty.pricing.domain..").check(CLASSES);
    }
}
