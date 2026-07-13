package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.fqn.FqnResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Sprint 24 (D2) — <b>stale memory repairs itself</b>. The human loop is: go
 * where you think it is; on a miss, search; when the search finds it elsewhere,
 * REPLACE the stale memory with the finding. An agent addressing a symbol by
 * name (its stable memory key) should get that whole loop in ONE call, not a
 * bare "not found" that costs a second round trip and leaves the repair
 * undone.
 *
 * <p>So a name that no longer resolves is answered with the indexed best-guess
 * relocation in the SAME response — "not there; found here", explicitly flagged
 * as a correction ({@code SYMBOL_RELOCATED}).</p>
 *
 * <p><b>The correction is never acted upon.</b> The call still fails: silently
 * retargeting a refactoring at a symbol the caller did not name would be far
 * worse than a failed call. The agent re-issues once with the corrected name —
 * one hop, and its memory is now right.</p>
 */
public final class ResolveOrRelocate {

    private static final Logger log = LoggerFactory.getLogger(ResolveOrRelocate.class);

    /** Distinct from SYMBOL_NOT_FOUND: the symbol EXISTS, under another name. */
    public static final String RELOCATED = "SYMBOL_RELOCATED";

    private static final int MAX_CANDIDATES = 3;

    /** Below this, a "match" is just a shared `get`/`is` prefix — noise, not a lead. */
    private static final int MIN_AFFINITY = 4;

    /** A directory, not an inventory: a 200-member god class helps nobody. */
    private static final int MAX_MEMBERS_LISTED = 30;

    private ResolveOrRelocate() {
    }

    /** The type half of an FQN, or null when the name names no member. */
    private static String typePart(String name) {
        int hash = name.indexOf('#');
        return hash < 0 ? null : name.substring(0, hash);
    }

    /**
     * The honest answer for a name that did not resolve: a correction when the
     * index can find it elsewhere, a plain not-found when it genuinely cannot.
     */
    public static ToolResponse miss(IJdtService service, String name, String scopeLabel) {
        // Resolve the type ONCE and let that single fact drive every branch. Doing it
        // twice (here and inside relocate) let the two disagree — and when they did,
        // we told the caller a type was "gone" while it sat right there. A claim about
        // absence must rest on the same evidence as the search for a replacement.
        String typePart = typePart(name);
        boolean typeIsThere = typePart != null
            && FqnResolver.resolveWorkspace(typePart, service).isPresent();

        List<String> candidates = relocate(service, name, typePart, typeIsThere);
        if (candidates.isEmpty()) {
            // Dogfood (v2.11.0): two different truths were being told as one.
            //
            // If the TYPE is still there and only the member is wrong, "gone, not
            // moved" is simply false. And when no member is a CLOSE match, we do not
            // know what the old one became — a member renamed to an unrelated word
            // (multiply -> times) leaves no trace to follow. Claiming "Found: times"
            // would be a guess wearing a fact's clothes. So: say the member is not
            // there, and name the ones that ARE. The agent picks; we do not pretend.
            if (typeIsThere) {
                List<String> members = allMembers(service, typePart);
                String has = members.isEmpty()
                    ? ""
                    : " It has: " + String.join(", ", members)
                        // A truncated list must say so — a bounded list presented as a
                        // complete one is the same lie as a capped count presented as
                        // an exact one.
                        + (members.size() >= MAX_MEMBERS_LISTED ? ", …" : "") + ".";
                return ToolResponse.symbolNotFound(
                    "'" + typePart + "' exists, but it has no member '" + member(name)
                        + "' — the type did not move; that member is not on it." + has);
            }
            if (typePart != null) {
                // A member form whose TYPE would not resolve. We know we could not
                // find it; we do NOT know it is gone — a workspace mid-rebuild fails
                // this lookup too. Claiming "gone" here would be asserting an absence
                // we cannot prove, so state only what was actually observed.
                return ToolResponse.symbolNotFound(
                    "'" + name + "' did not resolve in " + scopeLabel + " scope: no type '"
                        + typePart + "' was found, and nothing similarly named either.");
            }
            return ToolResponse.symbolNotFound(
                "'" + name + "' not found in " + scopeLabel + " scope, and nothing "
                    + "similarly named exists — it is gone, not moved.");
        }
        String best = candidates.get(0);
        String others = candidates.size() > 1
            ? " Other candidates: " + String.join(", ", candidates.subList(1, candidates.size())) + "."
            : "";
        return ToolResponse.error(RELOCATED,
            "'" + name + "' is not there any more — it appears to have moved or been "
                + "renamed. Found: '" + best + "'.",
            "STALE MEMORY: re-issue with '" + best + "' and remember THAT name from now on."
                + others);
    }

    /**
     * Best-guess relocations for a name that no longer resolves, most likely
     * first. Empty when the index knows nothing by that name.
     */
    public static List<String> relocate(IJdtService service, String name) {
        String typePart = typePart(name);
        String wholeType = typePart != null ? typePart : name;
        boolean typeIsThere =
            FqnResolver.resolveWorkspace(wholeType, service).isPresent();
        return relocate(service, name, typePart, typeIsThere);
    }

    /** The same search, told what is already known about the type — never re-asking. */
    private static List<String> relocate(IJdtService service, String name,
                                         String typePart, boolean typeIsThere) {
        try {
            int hash = name.indexOf('#');
            String memberPart = hash < 0 ? null : name.substring(hash + 1);
            int paren = memberPart == null ? -1 : memberPart.indexOf('(');
            String memberName = paren < 0 ? memberPart : memberPart.substring(0, paren);
            String wholeType = typePart != null ? typePart : name;

            // Did the TYPE move? (The common case: a class was moved or renamed.)
            if (!typeIsThere) {
                return relocatedTypes(service, simpleName(wholeType), memberName);
            }
            // The type is there, so the MEMBER is what changed. Offer only members
            // that are plausibly what was meant.
            return siblingMembers(service, wholeType, memberName);
        } catch (Exception e) {
            log.debug("Relocation search for '{}' failed: {}", name, e.getMessage());
            return List.of();
        }
    }

    /** Types now living under a different FQN but the same simple name. */
    private static List<String> relocatedTypes(IJdtService service, String simpleName,
                                               String memberName) throws Exception {
        Set<String> found = new LinkedHashSet<>();
        List<SearchMatch> matches = new ArrayList<>(service.getSearchService()
            .searchSymbolsInSource(simpleName, IJavaSearchConstants.TYPE, MAX_CANDIDATES * 3));
        for (SearchMatch match : matches) {
            if (!(match.getElement() instanceof IType type)) {
                continue;
            }
            String fqn = type.getFullyQualifiedName();
            // Only offer the member suffix when that member really is on the new type.
            if (memberName != null && !memberName.isBlank()) {
                if (FqnResolver.resolveWorkspace(fqn + "#" + memberName, service).isEmpty()) {
                    continue;
                }
                fqn = fqn + "#" + memberName;
            }
            found.add(fqn);
            if (found.size() >= MAX_CANDIDATES) {
                break;
            }
        }
        return new ArrayList<>(found);
    }

    /**
     * The names a caller could actually ADDRESS on this type — a directory, not a
     * guess. Second dogfood round (v2.11.1, live): the first cut listed the
     * constructor, repeated every overload ({@code success, success}), and then
     * repeated the method names again as fields ({@code success, data, error,
     * meta}) — twenty-five entries of which half were noise, in the very message
     * whose job is a clean, actionable correction. An addressable name is what
     * matters here, and {@code Type#name} addresses every overload at once, so a
     * name is worth saying exactly once.
     */
    static List<String> allMembers(IJdtService service, String typeFqn) {
        Optional<IJavaElement> typeEl = FqnResolver.resolveWorkspace(typeFqn, service);
        if (typeEl.isEmpty() || !(typeEl.get() instanceof IType type)) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        try {
            String simpleName = type.getElementName();
            for (IMethod m : type.getMethods()) {
                // The constructor is not addressable as `Type#member`.
                if (m.isConstructor() || simpleName.equals(m.getElementName())) {
                    continue;
                }
                names.add(m.getElementName());
            }
            for (IField f : type.getFields()) {
                names.add(f.getElementName());
            }
        } catch (Exception e) {
            log.debug("Listing members of {} failed: {}", typeFqn, e.getMessage());
        }
        List<String> members = new ArrayList<>(names);
        return members.size() > MAX_MEMBERS_LISTED
            ? members.subList(0, MAX_MEMBERS_LISTED)
            : members;
    }

    /**
     * Members of a still-present type that are PLAUSIBLY the one that was meant —
     * a typo, a near-name. Deliberately empty when nothing is close: a member
     * renamed to an unrelated word leaves no trace, and a confident-sounding wrong
     * answer is worse than an honest "not there, here is what is".
     */
    private static List<String> siblingMembers(IJdtService service, String typeFqn,
                                               String memberName) {
        if (memberName == null || memberName.isBlank()) {
            return List.of();
        }
        Optional<IJavaElement> typeEl = FqnResolver.resolveWorkspace(typeFqn, service);
        if (typeEl.isEmpty() || !(typeEl.get() instanceof IType type)) {
            return List.of();
        }
        List<String> siblings = new ArrayList<>();
        try {
            for (IMethod m : type.getMethods()) {
                siblings.add(typeFqn + "#" + m.getElementName());
            }
            for (IField f : type.getFields()) {
                siblings.add(typeFqn + "#" + f.getElementName());
            }
        } catch (Exception e) {
            log.debug("Listing members of {} failed: {}", typeFqn, e.getMessage());
        }
        // Closest by name first — a rename usually keeps most of the word.
        siblings.sort((a, b) -> Integer.compare(
            distance(memberName, member(b)), distance(memberName, member(a))));

        // Dogfood (v2.11.0): only offer members that are PLAUSIBLY what was meant.
        // A typo'd `getLineNumberr` was correctly answered with `getLineNumber` —
        // but `getPathUtils` and `getProjectRoot` rode along, sharing nothing but a
        // "get" prefix. Noise dilutes a correction; a wrong suggestion is worse than
        // one fewer suggestion.
        int floor = Math.max(MIN_AFFINITY, memberName.length() / 2);
        List<String> plausible = new ArrayList<>();
        for (String sibling : siblings) {
            if (distance(memberName, member(sibling)) >= floor) {
                plausible.add(sibling);
            }
            if (plausible.size() >= MAX_CANDIDATES) {
                break;
            }
        }
        return plausible;
    }

    private static String member(String fqn) {
        int hash = fqn.indexOf('#');
        return hash < 0 ? fqn : fqn.substring(hash + 1);
    }

    /** Crude shared-prefix/substring affinity — enough to rank a rename first. */
    private static int distance(String wanted, String candidate) {
        String a = wanted.toLowerCase();
        String b = candidate.toLowerCase();
        if (a.equals(b)) return 100;
        if (b.contains(a) || a.contains(b)) return 50;
        int common = 0;
        while (common < a.length() && common < b.length() && a.charAt(common) == b.charAt(common)) {
            common++;
        }
        return common;
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
