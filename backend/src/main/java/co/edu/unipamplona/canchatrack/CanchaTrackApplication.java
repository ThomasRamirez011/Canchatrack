package co.edu.unipamplona.canchatrack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CanchaTrack: monolito modular con arquitectura hexagonal (DDS secc. 2 y 3).
 *
 * <p>Cada subpaquete directo es un módulo ({@code identity}, {@code players}, ...) con la estructura
 * {@code api / domain / application / infrastructure}. {@code shared} es el núcleo transversal mínimo.
 */
@SpringBootApplication
public class CanchaTrackApplication {

    public static void main(String[] args) {
        SpringApplication.run(CanchaTrackApplication.class, args);
    }
}
