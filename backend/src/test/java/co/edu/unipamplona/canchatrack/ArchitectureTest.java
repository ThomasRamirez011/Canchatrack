package co.edu.unipamplona.canchatrack;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * Hace verificable la regla de dependencia (DDS 2.1 DIP y 3.1; DAD 6.3). Las reglas admiten paquetes vacíos
 * ({@code allowEmptyShould}) porque los módulos se van poblando incremento a incremento.
 */
class ArchitectureTest {

    private static final String BASE = "co.edu.unipamplona.canchatrack";

    /** Módulos del DDS secc. 3. */
    private static final List<String> MODULES = List.of(
            "identity", "players", "documents", "scheduling", "attendance", "evaluations",
            "competitions", "payments", "reports", "notifications", "audit");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(BASE);
    }

    @Test
    void domainDoesNotDependOnFrameworks() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta..", "org.hibernate..", "com.fasterxml..", "tools.jackson..")
                .because("el dominio no depende de frameworks, base de datos, correo ni PDF (DDS 2.1, DAD 6.3)")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void layersPointInward() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..application..", "..infrastructure..", "..api..")
                .because("el dominio es el centro de la arquitectura hexagonal")
                .allowEmptyShould(true)
                .check(classes);

        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
                .because("los casos de uso dependen de puertos, no de adaptadores (DIP)")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void modulesUseEachOtherOnlyThroughApi() {
        for (String module : MODULES) {
            String[] foreignInternals = MODULES.stream()
                    .filter(other -> !other.equals(module))
                    .flatMap(other -> Stream.of("domain", "application", "infrastructure")
                            .map(layer -> BASE + "." + other + "." + layer + ".."))
                    .toArray(String[]::new);

            noClasses().that().resideInAPackage(BASE + "." + module + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(foreignInternals)
                    .because("el módulo '" + module + "' solo puede usar la fachada .api de los demás (DDS 3.1)")
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }

    @Test
    void noCyclesBetweenModules() {
        // Siempre hay al menos dos "slices" (shared y audit), por lo que la regla nunca es vacía.
        slices().matching(BASE + ".(*)..")
                .should().beFreeOfCycles()
                .because("sin dependencias cíclicas entre módulos (CLAUDE.md, Arquitectura)")
                .check(classes);
    }

    @Test
    void sharedKernelDoesNotDependOnModules() {
        String[] modulePackages = MODULES.stream().map(m -> BASE + "." + m + "..").toArray(String[]::new);

        noClasses().that().resideInAPackage(BASE + ".shared..")
                .should().dependOnClassesThat().resideInAnyPackage(modulePackages)
                .because("el núcleo compartido no conoce a los módulos")
                .allowEmptyShould(true)
                .check(classes);
    }
}
