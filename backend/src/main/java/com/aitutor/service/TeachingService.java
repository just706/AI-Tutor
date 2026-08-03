package com.aitutor.service;

import com.aitutor.dto.EvaluateTeachingRequest;
import com.aitutor.dto.StartTeachingRequest;
import com.aitutor.vo.LearningRecordVO;
import com.aitutor.vo.TeachingEvaluationVO;
import com.aitutor.vo.TeachingStartVO;

import java.util.List;

public interface TeachingService {

    TeachingStartVO start(StartTeachingRequest request);

    TeachingEvaluationVO evaluate(EvaluateTeachingRequest request);

    List<LearningRecordVO> listCurrentUserRecords();
}
