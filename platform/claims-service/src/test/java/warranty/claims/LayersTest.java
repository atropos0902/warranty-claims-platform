package warranty.claims;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import org.junit.jupiter.api.Test;

class LayersTest {
    private static final JavaClasses CLASSES =
            new ClassFileImporter().withImportOption(new DoNotIncludeTests()).importPackages("warranty.claims");

    @Test
    void controllersDoNotTouchTheDatabase() {
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..").check(CLASSES);
    }

    @Test
    void storesDoNotCallFlows() {
        noClasses().that().resideInAPackage("..data..")
                .should().dependOnClassesThat().resideInAPackage("..flow..").check(CLASSES);
    }

    @Test
    void onlyTheRelayTalksToTheQueue() {
        noClasses().that().resideInAnyPackage("..api..", "..data..").or().haveSimpleName("ClaimFlow")
                .should().dependOnClassesThat().resideInAPackage("software.amazon.awssdk..").check(CLASSES);
    }
}
