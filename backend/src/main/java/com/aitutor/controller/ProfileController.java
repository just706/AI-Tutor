package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.dto.ProfileRequest;
import com.aitutor.service.ProfileService;
import com.aitutor.vo.ProfileVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public Result<ProfileVO> getProfile() {
        return Result.success(profileService.getCurrentProfile());
    }

    @PutMapping
    public Result<Boolean> saveProfile(@Valid @RequestBody ProfileRequest request) {
        return Result.success("保存成功", profileService.saveCurrentProfile(request));
    }
}
