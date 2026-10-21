package com.aitutor.vo;

import java.util.List;

public record ReviewPageVO(List<ReviewTaskVO> items, long total, int page, int pageSize) {}
