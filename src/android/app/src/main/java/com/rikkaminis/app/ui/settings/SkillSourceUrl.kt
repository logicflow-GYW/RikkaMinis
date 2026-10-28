package com.rikkaminis.app.ui.settings

/**
 * [meta-audit-1028 E5 / B06 F-11] GitHub source-URL normalization for the
 * skills browser (MinisSkillsBrowserScreen).
 *
 * The defect this exists for: the browser handed the raw URL — exactly as the
 * pasted/deep-linked page carried it — to its own `toRawGitHubUrl` pre-fetch,
 * which only did `replace("/tree/", "/")` + `trimEnd('/')`. A page anchor or
 * a query string therefore landed in the MIDDLE of the derived path:
 *
 *     https://github.com/o/r/tree/main/exa-search#install
 *       → https://raw.githubusercontent.com/o/r/main/exa-search#install/SKILL.md
 *
 * HTTP drops everything from `#` on, so the request went to the *directory*
 * `…/main/exa-search` → 404 → the screen aborted with "this folder has no
 * SKILL.md", which is the opposite of the truth. `?tab=readme` broke the same
 * way (`…/exa-search?tab=readme/SKILL.md` turned the path tail into the query).
 *
 * Note what is NOT broken: [com.rikkaminis.app.data.repository.SkillRepository]
 * derives its paths from `Uri.parse(…).pathSegments`, which already excludes
 * fragment and query — the repository would have imported the anchored URL
 * correctly. Only the screen-side pre-fetch (and the recorded `sourceURL`)
 * needed the normalization, which is why this lives next to the screen.
 *
 * Both entry points below normalize defensively even though the caller does it
 * once up front: a future call site cannot reintroduce the bug by forgetting.
 */

/**
 * Drop the `#fragment` / `?query` — everything from the EARLIEST separator on.
 *
 * Which separator wins does not depend on the order they are cut in (an
 * earlier mutation experiment swapping the two came out a fully equivalent
 * mutant, which is what settled this): both orders yield the prefix before
 * `min(first '?', first '#')`, and that is the only correct answer for a URL
 * that may carry a `?` inside its fragment or a `#` inside its query value.
 * Cutting at the LAST separator (the other obvious spelling) leaves the inner
 * one in place — see the arm that pins it.
 *
 * Pure string surgery on purpose: no `Uri`/`URL` parsing means no exceptions,
 * no scheme requirement, and nothing that behaves differently off-device.
 */
internal fun stripUrlAnchor(url: String): String {
    var cut = url.length
    val hash = url.indexOf('#')
    if (hash >= 0) cut = hash
    val question = url.indexOf('?')
    if (question >= 0 && question < cut) cut = question
    return if (cut < url.length) url.substring(0, cut) else url
}

/**
 * Check if the URL points to a specific skill directory (not repo root or
 * non-skill pages).
 * e.g. github.com/OpenMinis/MinisSkills/tree/main/exa-search → true
 *      github.com/OpenMinis/MinisSkills → false
 *      github.com/OpenMinis/MinisSkills/issues → false
 */
internal fun isSkillDirectoryUrl(url: String): Boolean {
    val target = stripUrlAnchor(url)
    if (!target.contains("github.com")) return true // Non-GitHub URL, let it try
    if (target.endsWith("SKILL.md")) return true

    // Must contain /tree/ or /blob/ with a path after the branch name
    // e.g. /tree/main/skill-name or /blob/main/skill-name/SKILL.md
    val treePattern = Regex("github\\.com/[^/]+/[^/]+/(tree|blob)/[^/]+/.+")
    if (treePattern.containsMatchIn(target)) return true

    // Reject known non-skill pages
    val nonSkillPages = listOf("/issues", "/pulls", "/pull/", "/actions", "/settings", "/wiki")
    if (nonSkillPages.any { target.contains(it) }) return false

    return false
}

/**
 * Derive the raw.githubusercontent.com SKILL.md URL for a GitHub page URL.
 *
 * Anchors/queries are stripped first (see the file KDoc): leaving them in puts
 * the anchor inside the path, which 404s and reports "no SKILL.md".
 */
internal fun toRawGitHubUrl(url: String): String {
    val target = stripUrlAnchor(url)
    // Handle github.com/user/repo/tree/branch/path → find SKILL.md
    if (target.contains("github.com") && target.contains("/blob/")) {
        return target.replace("github.com", "raw.githubusercontent.com")
            .replace("/blob/", "/")
    }
    if (target.contains("github.com") && !target.contains("raw.githubusercontent.com")) {
        val withFile = if (!target.endsWith("SKILL.md")) {
            // Try tree → add SKILL.md
            val cleaned = target.replace("/tree/", "/").trimEnd('/')
            "$cleaned/SKILL.md"
        } else {
            target
        }
        return withFile.replace("github.com", "raw.githubusercontent.com")
            .replace("/blob/", "/")
            .replace("/tree/", "/")
    }
    return target
}
