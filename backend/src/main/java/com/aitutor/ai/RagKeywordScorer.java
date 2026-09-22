package com.aitutor.ai;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight lexical retrieval; scores measure text overlap, not answer correctness. */
public final class RagKeywordScorer {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9_$]+|[\\p{IsHan}]+");
    // Remove question boilerplate within Han runs only, without joining words across gaps.
    private static final List<String> QUESTION_WORDS = List.of(
            "应该选哪一种", "应该选哪种", "哪一种", "哪一个",
            "为什么", "怎么样", "有什么", "是什么", "是多少", "什么时候",
            "请问", "介绍", "解释", "说明", "一下", "经常", "应该", "哪种", "哪个", "哪些",
            "什么", "怎么", "如何", "这些", "那些", "这个", "那个", "怎样", "能否",
            "是否", "可以", "需要", "通常", "时候",
            "请", "的", "了", "吗", "呢", "和", "与", "或", "是", "按", "通过");
    private static final Set<String> ENGLISH_STOP_WORDS = Set.of(
            "the", "a", "an", "is", "are", "what", "which", "how", "why", "to", "of", "in", "on",
            "and", "or", "for", "does", "do", "please", "can", "should", "with", "when", "use");
    private static final double MIN_CHINESE_COVERAGE = 0.4;
    private static final double MIN_LATIN_COVERAGE = 0.5;

    private final Terms query;
    private final Map<String, Terms> documents = new HashMap<>();
    private final Map<String, Double> inverseDocumentFrequency = new HashMap<>();
    private final double averageLength;

    public RagKeywordScorer(String question, List<String> texts) {
        query = tokenize(question);
        double totalLength = 0;
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (String text : texts) {
            Terms terms = tokenize(text);
            documents.put(text, terms);
            totalLength += terms.length();
            for (String term : terms.frequency().keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        averageLength = Math.max(1, totalLength / Math.max(1, texts.size()));
        Set<String> queryTerms = new LinkedHashSet<>(query.latin());
        queryTerms.addAll(query.chinese());
        for (String term : queryTerms) {
            int frequency = documentFrequency.getOrDefault(term, 0);
            inverseDocumentFrequency.put(term,
                    Math.log1p((texts.size() - frequency + 0.5) / (frequency + 0.5)));
        }
    }

    public double score(String text) {
        Terms document = documents.get(text);
        if (document == null) {
            return 0;
        }
        if (query.latin().isEmpty() && query.chinese().isEmpty()) {
            return 0;
        }
        int latinMatches = overlap(query.latin(), document.latin());
        int chineseMatches = overlap(query.chinese(), document.chinese());
        double latinCoverage = coverage(latinMatches, query.latin().size());
        double chineseCoverage = coverage(chineseMatches, query.chinese().size());

        // A class name alone cannot support a missing Chinese topic (e.g. capacity growth).
        // Likewise a generic Chinese word cannot substitute for a missing class name.
        if (latinCoverage < MIN_LATIN_COVERAGE || chineseCoverage < MIN_CHINESE_COVERAGE) {
            return 0;
        }

        // BM25 term weighting favors distinguishing terms while saturating repetition.
        double relevance = weightedMatches(query.latin(), document)
                + weightedMatches(query.chinese(), document);
        return relevance * latinCoverage * chineseCoverage;
    }

    private double weightedMatches(Set<String> terms, Terms document) {
        double score = 0;
        double lengthNormalization = 1.2 * (0.25 + 0.75 * document.length() / averageLength);
        for (String term : terms) {
            int frequency = document.frequency().getOrDefault(term, 0);
            score += inverseDocumentFrequency.get(term) * frequency * 2.2
                    / (frequency + lengthNormalization);
        }
        return score;
    }

    private static Terms tokenize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        Set<String> latin = new LinkedHashSet<>();
        Set<String> chinese = new LinkedHashSet<>();
        Map<String, Integer> frequency = new HashMap<>();
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (Character.UnicodeScript.of(token.codePointAt(0)) != Character.UnicodeScript.HAN) {
                if (!ENGLISH_STOP_WORDS.contains(token)) {
                    latin.add(token);
                    frequency.merge(token, 1, Integer::sum);
                }
                continue;
            }
            // Keep the same normalization on the question and source text.
            token = token.replace("下标", "索引").replace("读取", "访问");
            for (String word : QUESTION_WORDS) {
                token = token.replace(word, " ");
            }
            for (String part : token.split("\\s+")) {
                int[] characters = part.codePoints().toArray();
                for (int index = 0; index + 1 < characters.length; index++) {
                    String term = new String(characters, index, 2);
                    chinese.add(term);
                    frequency.merge(term, 1, Integer::sum);
                }
            }
        }
        return new Terms(latin, chinese, frequency,
                frequency.values().stream().mapToInt(Integer::intValue).sum());
    }

    private static int overlap(Set<String> queryTerms, Set<String> documentTerms) {
        return (int) queryTerms.stream().filter(documentTerms::contains).count();
    }

    private static double coverage(int matches, int total) {
        return total == 0 ? 1.0 : (double) matches / total;
    }

    private record Terms(Set<String> latin, Set<String> chinese, Map<String, Integer> frequency, int length) {
    }
}
