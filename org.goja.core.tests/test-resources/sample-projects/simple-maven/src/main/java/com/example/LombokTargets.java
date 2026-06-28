package com.example;

import lombok.Data;
import lombok.Getter;

/**
 * Fixture for find_modernization Lombok-removal kinds (Sprint 15 B5b). Lombok is
 * NOT on the fixture classpath — detection is source-only (import + annotation
 * simple-name), so the unresolved imports here are intentional and harmless.
 */
public class LombokTargets {
}

/** lombok_to_record + delombok candidate: @Data data carrier. */
@Data
class LombokPoint {
    private final int x;
    private final int y;
}

/** delombok candidate only: @Getter is not a class-level data annotation. */
@Getter
class LombokBox {
    private int width;
    private int height;
}
