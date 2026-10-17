package com.aitutor.ai;

import com.aitutor.exception.BusinessException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 模型返回区分标签目录、统一题目列表和证据编号；原文与阶段位置由服务端确定。 */
public final class TutorLessonPreparation {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);

    public record Prepared(Integer schemaVersion, Boolean supported, String explanation,
                           String remedialExplanation, List<PreparedDistinction> distinctions,
                           List<PreparedQuestion> questions) {}
    public record PreparedDistinction(String id, String label) {}
    public record PreparedQuestion(String prompt, List<String> options, String correctOption,
                                   String explanation, List<PreparedFeedback> wrongOptionFeedback) {}
    public record PreparedFeedback(String option, String distinctionId, String explanation, List<String> evidenceIds) {}

    private TutorLessonPreparation() {}

    public static List<TutorLearningFlow.SourceEvidence> evidence(String source) {
        if (source == null || source.isBlank() || source.length() > 4000) throw invalid();
        var units = new ArrayList<TutorLearningFlow.SourceEvidence>();
        int start=0;
        // 保留原始字符和顺序，不按英文句点切割小数、代码或文件名。最多64段，余文完整保留。
        for (int i=0;i<source.length() && units.size()<63;i++) {
            char c=source.charAt(i);
            boolean blankLine=c=='\n' && ((i>0 && source.charAt(i-1)=='\n')
                    || (i>1 && source.charAt(i-1)=='\r' && source.charAt(i-2)=='\n'));
            if ("。！？".indexOf(c)>=0 || blankLine) {
                String text=source.substring(start,i+1);
                if (!text.isBlank()) {
                    units.add(new TutorLearningFlow.SourceEvidence("E"+(units.size()+1),text));
                    start=i+1;
                }
            }
        }
        String tail=source.substring(start);
        if (!tail.isEmpty()) {
            if (tail.isBlank() && !units.isEmpty()) {
                var last=units.remove(units.size()-1);
                units.add(new TutorLearningFlow.SourceEvidence(last.id(),last.text()+tail));
            } else units.add(new TutorLearningFlow.SourceEvidence("E"+(units.size()+1),tail));
        }
        return List.copyOf(units);
    }

    public static TutorLearningFlow.Lesson parse(String response, String source) {
        try {
            if (response == null || response.length() > 60000) throw invalid();
            Prepared prepared=JSON.readValue(response,Prepared.class);
            if (prepared == null || prepared.supported() == null) throw invalid();
            if (!prepared.supported()) throw new BusinessException(400,"资料不足，请补充该主题的定义、例子或教材片段。");
            if (!Integer.valueOf(4).equals(prepared.schemaVersion()) || prepared.distinctions() == null
                    || prepared.distinctions().size() < 2 || prepared.distinctions().size() > 4
                    || prepared.questions() == null
                    || prepared.questions().size()!=5) throw invalid();
            var distinctions = new LinkedHashMap<String,String>();
            var distinctionLabels = new HashSet<String>();
            for (int i=0; i<prepared.distinctions().size(); i++) {
                PreparedDistinction distinction=prepared.distinctions().get(i);
                if (distinction == null || !String.valueOf("D"+(i+1)).equals(distinction.id())
                        || distinction.label() == null || distinction.label().isBlank() || distinction.label().length() > 120
                        || distinctions.putIfAbsent(distinction.id(),distinction.label().trim()) != null
                        || !distinctionLabels.add(distinction.label().trim())) throw invalid();
            }
            var usedDistinctions = new HashSet<String>();
            Map<String,TutorLearningFlow.SourceEvidence> catalog=evidence(source).stream()
                    .collect(Collectors.toMap(TutorLearningFlow.SourceEvidence::id,Function.identity()));
            var questions=new ArrayList<TutorLearningFlow.Question>();
            for (PreparedQuestion q : prepared.questions()) {
                if (q == null || q.wrongOptionFeedback() == null) throw invalid();
                var feedback=new ArrayList<TutorLearningFlow.OptionFeedback>();
                for (PreparedFeedback f : q.wrongOptionFeedback()) {
                    if (f == null || f.evidenceIds() == null || f.evidenceIds().isEmpty() || f.evidenceIds().size()>4
                            || new HashSet<>(f.evidenceIds()).size()!=f.evidenceIds().size()
                            || f.distinctionId() == null || !distinctions.containsKey(f.distinctionId())) throw invalid();
                    usedDistinctions.add(f.distinctionId());
                    var quotes=new ArrayList<TutorLearningFlow.SourceEvidence>();
                    for (String id : f.evidenceIds()) {
                        if (id == null || !catalog.containsKey(id)) throw invalid();
                        quotes.add(catalog.get(id));
                    }
                    feedback.add(new TutorLearningFlow.OptionFeedback(f.option(),distinctions.get(f.distinctionId()),f.explanation(),null,List.copyOf(quotes)));
                }
                questions.add(new TutorLearningFlow.Question(q.prompt(),q.options(),q.correctOption(),q.explanation(),List.copyOf(feedback)));
            }
            var lesson=new TutorLearningFlow.Lesson(prepared.explanation(),prepared.remedialExplanation(),questions.get(0),
                    List.copyOf(questions.subList(1,3)),List.copyOf(questions.subList(3,5)));
            if (!usedDistinctions.equals(distinctions.keySet())) throw invalid();
            TutorLearningFlow.validatePrepared(lesson,source);
            questions.forEach(TutorQuestionContext::validate);
            return TutorOptionOrder.arrange(lesson,java.util.concurrent.ThreadLocalRandom.current());
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw invalid(); }
    }

    private static BusinessException invalid() {
        return new BusinessException(600,"备课结构或证据编号无效，请重试；原学习进度已保留。");
    }
}
