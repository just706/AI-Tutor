package com.aitutor.service;

import com.aitutor.dto.GenerateStudyPlanRequest;
import com.aitutor.vo.KnowledgePointProgressVO;
import com.aitutor.vo.LearningAnalysisOverviewVO;
import com.aitutor.vo.RecentAnswerAnalysisVO;
import com.aitutor.vo.StudyPlanVO;

import java.util.List;

public interface LearningAnalysisService {

    LearningAnalysisOverviewVO overview();

    List<KnowledgePointProgressVO> knowledgePointProgress();

    List<RecentAnswerAnalysisVO> recentAnswers(Integer limit);

    StudyPlanVO generateStudyPlan(GenerateStudyPlanRequest request);
}
