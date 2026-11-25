package org.koitharu.kotatsu.core.network.webview.adblock

import androidx.annotation.CheckResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Very simple implementation of adblock list parser
 * Not all features are supported
 */
class RulesList {

    private val blockRules = RuleSet()
    private val allowRules = RuleSet()

    operator fun get(url: HttpUrl, baseUrl: HttpUrl?): Rule? {
        // Computed once per request: the public-suffix lookup behind it is far costlier than a map read.
        val domain = url.topPrivateDomain() ?: url.host
        val rule = blockRules.find(domain, url, baseUrl)
        return rule?.takeIf { allowRules.find(domain, url, baseUrl) == null }
    }

    fun add(line: String) {
        val parts = line.lowercase().trim().split('$')
        parts.first().addImpl(isWhitelist = false, modifiers = parts.getOrNull(1))
    }

    fun trimToSize() {
        blockRules.trimToSize()
        allowRules.trimToSize()
    }

    /**
     * Domain rules only ever match requests of their own domain, so they are looked up by it instead of
     * being tested one by one (a list holds tens of thousands of them).
     */
    private class RuleSet {

        private val byDomain = HashMap<String, ArrayList<Rule>>()
        private val others = ArrayList<Rule>()

        fun addDomain(domain: String, rule: Rule) {
            byDomain.getOrPut(domain) { ArrayList(1) }.add(rule)
        }

        fun add(rule: Rule) {
            others.add(rule)
        }

        fun find(domain: String, url: HttpUrl, baseUrl: HttpUrl?): Rule? =
            byDomain[domain]?.find { x -> x(url, baseUrl) } ?: others.find { x -> x(url, baseUrl) }

        fun trimToSize() {
            others.trimToSize()
        }
    }

    private fun String.addImpl(isWhitelist: Boolean, modifiers: String?) {
        val list = if (isWhitelist) allowRules else blockRules

        when {
            startsWith('!') || startsWith('[') -> {
                // Comment, do nothing
            }

            startsWith("||") -> {
                // domain
                val domain = substring(2).substringBefore('^').trim()
                list.addDomain(domain, Rule.Domain(domain).withModifiers(modifiers))
            }

            startsWith('|') -> {
                val url = substring(1).substringBefore('^').trim().toHttpUrlOrNull()
                if (url != null) {
                    list.add(Rule.ExactUrl(url).withModifiers(modifiers))
                }
            }

            startsWith("@@") -> {
                substring(2).substringBefore('^').trim().addImpl(!isWhitelist, modifiers)
            }

            startsWith("##") -> {
                // TODO css rules
            }

            else -> {
                if (endsWith('*')) {
                    list.add(Rule.Path(this.dropLast(1), contains = true).withModifiers(modifiers))
                } else if (!contains('*')) { // wildcards is not supported yet
                    list.add(Rule.Path(this, contains = false).withModifiers(modifiers))
                }
            }
        }
    }

    @CheckResult
    private fun Rule.withModifiers(options: String?): Rule {
        if (options.isNullOrEmpty()) {
            return this
        }
        var script: Boolean? = null
        var thirdParty: Boolean? = null
        options.split(',').forEach {
            val isNot = it.startsWith('~')
            when (it.removePrefix("~")) {
                "script" -> script = !isNot
                "third-party" -> thirdParty = !isNot
            }
        }
        return Rule.WithModifiers(
            baseRule = this,
            script = script,
            thirdParty = thirdParty,
            domains = null, // TODO
            domainsNot = null, // TODO
        )
    }
}
