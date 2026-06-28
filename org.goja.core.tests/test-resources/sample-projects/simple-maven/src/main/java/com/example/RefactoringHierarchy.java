package com.example;

// Fixture for Sprint 11 Phase E pull_up / push_down tests. Two-class hierarchy
// purpose-built for the refactoring test trio:
//   - RefactoringBase#commonMethod  - exists only on the base; pushable down to
//     RefactoringDerived.
//   - RefactoringDerived#uniqueMethod  - exists only on the subtype; pullable up
//     to RefactoringBase.
//   - RefactoringDerived#fieldToEncapsulate  - public field with no existing
//     accessor; usable for encapsulate_field tests.
//
// Plain // comments instead of Javadoc {@link} references are deliberate:
// JDT's PushDownRefactoringProcessor.checkReferencesToPushedDownMembers does a
// search-by-references in the declaring type's scope, and Javadoc {@link}s
// land in that scope as the file's leading Javadoc - which the processor then
// flags as "Pushed down member is referenced by RefactoringBase". Plain
// comments stay outside the AST and aren't indexed.
class RefactoringBase {
    void commonMethod() {
        // Pushable down to derived — supertype only.
    }
}

class RefactoringDerived extends RefactoringBase {
    public int fieldToEncapsulate;

    void uniqueMethod() {
        // Pullable up to base — subtype only.
    }
}
