package com.aitutor.service;

import com.aitutor.dto.GenerateStudyPlanRequest;
import com.aitutor.vo.LearningAnalysisOverviewVO;
import com.aitutor.vo.StudyPlanVO;

public interface LearningAnalysisService {

    LearningAnalysisOverviewVO overview();

    StudyPlanVO generateStudyPlan(GenerateStudyPlanRequest request);
}
