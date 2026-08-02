package com.aitutor.service;

import com.aitutor.dto.ProfileRequest;
import com.aitutor.vo.ProfileVO;

public interface ProfileService {

    ProfileVO getCurrentProfile();

    Boolean saveCurrentProfile(ProfileRequest request);
}
