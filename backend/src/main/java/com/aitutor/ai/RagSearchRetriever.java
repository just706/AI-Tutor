package com.aitutor.ai;

import java.util.*;
import java.util.function.Function;

/** 内部搜索返回候选证据；关键词命中不代表能回答问题，仍须引用核验及模型复核。 */
public final class RagSearchRetriever {
    private RagSearchRetriever() { }

    public static <T> List<T> retrieve(String query, List<T> chunks, Function<T,String> text, int limit) {
        List<String> texts=chunks.stream().map(text).toList();
        Set<String> queries=new LinkedHashSet<>();
        for (String part:query.split("[？?；;\\r\\n]+")) {
            if (part.isBlank()) continue;
            queries.add(part.trim());
            // 属性用词可能不同；搜索可返回该对象的上下文供下一步判读，不能视作已有答案。
            queries.addAll(RagQuestionParser.parse(part).subjects());
            // 搜索接口接受空格分隔关键词，不能把整个关键词列表当成一个教材对象名称。
            if (part.trim().matches("(?s).*\\s+.*"))
                Arrays.stream(part.trim().split("\\s+")).filter(term->term.codePointCount(0,term.length())>=2)
                        .forEach(queries::add);
        }
        List<List<T>> groups=new ArrayList<>();
        for (String term:queries.stream().limit(32).toList()) {
            var scorer=new RagKeywordScorer(term,texts);
            var ranked=chunks.stream().filter(chunk->scorer.score(text.apply(chunk))>0)
                    .sorted(Comparator.<T>comparingDouble(chunk->scorer.score(text.apply(chunk))).reversed()).toList();
            if (!ranked.isEmpty()) groups.add(ranked);
        }
        // 与教材多问题检索一致，先给每组保留一段，再轮流补充；使用输入顺序稳定消除并列。
        Set<T> selected=new LinkedHashSet<>();
        for (int position=0; selected.size()<limit; position++) {
            boolean added=false;
            for (var group:groups) {
                if (position>=group.size()) continue;
                added=true; selected.add(group.get(position));
                if (selected.size()==limit) break;
            }
            if (!added) break;
        }
        return List.copyOf(selected);
    }
}
