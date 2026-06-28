package com.example;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Fixture for apply_null_annotations migrate (Sprint 15b). Uses a default-scoping
 * annotation (@NonNullByDefault) whose semantics are NOT 1:1 across families —
 * migrate must refuse this rather than guess.
 */
@NonNullByDefault
class AmbiguousNull {
}
