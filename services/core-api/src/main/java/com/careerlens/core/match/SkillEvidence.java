package com.careerlens.core.match;

import java.util.List;
import java.util.regex.Pattern;
import java.util.Locale;

/** Conservative lexical checks, not model-based semantic similarity. */
public final class SkillEvidence {
    private SkillEvidence() {}
    private static final Pattern NEGATION = Pattern.compile(
            "(?:未|没有|没用|不熟悉|不了解|无.{0,6}经验|尚未|never|not\\b|no\\b|without\\b)",Pattern.CASE_INSENSITIVE);

    public static boolean contains(String text,String term) {
        String normalized=text.toLowerCase(Locale.ROOT);
        String needle=term.toLowerCase(Locale.ROOT);
        String left=Character.isLetterOrDigit(needle.charAt(0)) && needle.charAt(0)<128 ? "(?<![a-z0-9])" : "";
        String right=needle.charAt(needle.length()-1)<128 ? "(?![a-z0-9+#])" : "";
        return Pattern.compile(left+Pattern.quote(needle)+right).matcher(normalized).find();
    }

    public static boolean positive(String text,String term) {
        for(String clause:text.split("[。；;，,\\n]")) {
            if(contains(clause,term) && !NEGATION.matcher(clause).find()) return true;
        }
        return false;
    }
    public static boolean negated(String text) { return NEGATION.matcher(text).find(); }
}
