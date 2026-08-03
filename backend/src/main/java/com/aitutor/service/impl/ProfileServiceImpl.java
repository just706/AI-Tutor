package com.aitutor.service.impl;

import com.aitutor.dto.ProfileRequest;
import com.aitutor.entity.StudentProfile;
import com.aitutor.mapper.StudentProfileMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.ProfileService;
import com.aitutor.vo.ProfileVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileServiceImpl implements ProfileService {

    private final StudentProfileMapper studentProfileMapper;

    public ProfileServiceImpl(StudentProfileMapper studentProfileMapper) {
        this.studentProfileMapper = studentProfileMapper;
    }

    @Override
    public ProfileVO getCurrentProfile() {
        Long userId = UserContext.getRequired().getId();
        StudentProfile profile = findByUserId(userId);
        return ProfileVO.from(profile);
    }

    @Override
    @Transactional
    public Boolean saveCurrentProfile(ProfileRequest request) {
        Long userId = UserContext.getRequired().getId();
        StudentProfile profile = findByUserId(userId);

        // Profile is one row per user, so save behaves as an idempotent upsert.
        if (profile == null) {
            profile = new StudentProfile();
            profile.setUserId(userId);
            fillProfile(profile, request);
            studentProfileMapper.insert(profile);
        } else {
            fillProfile(profile, request);
            studentProfileMapper.updateById(profile);
        }

        return true;
    }

    private StudentProfile findByUserId(Long userId) {
        return studentProfileMapper.selectOne(new LambdaQueryWrapper<StudentProfile>()
                .eq(StudentProfile::getUserId, userId)
                .last("LIMIT 1"));
    }

    private void fillProfile(StudentProfile profile, ProfileRequest request) {
        profile.setLearningDirection(trimToNull(request.getLearningDirection()));
        profile.setLearningGoal(trimToNull(request.getLearningGoal()));
        profile.setCurrentLevel(trimToNull(request.getCurrentLevel()));
        profile.setLearningPreference(trimToNull(request.getLearningPreference()));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
