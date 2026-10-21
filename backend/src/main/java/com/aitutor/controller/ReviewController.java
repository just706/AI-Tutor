package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.dto.SubmitAnswerRequest;
import com.aitutor.service.impl.ReviewService;
import com.aitutor.vo.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {
    private final ReviewService reviews;
    public ReviewController(ReviewService reviews) { this.reviews = reviews; }
    @GetMapping public Result<List<ReviewTaskVO>> list() { return Result.success(reviews.list()); }
    @GetMapping("/page") public Result<ReviewPageVO> page(
            @RequestParam(defaultValue = "pending") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(reviews.page(status, page, pageSize));
    }
    @PostMapping("/generate") public Result<List<ReviewTaskVO>> generate() { return Result.success(reviews.generate()); }
    @PostMapping("/{id}/answer") public Result<ReviewResultVO> answer(@PathVariable Long id,
            @Valid @RequestBody SubmitAnswerRequest request) {
        return Result.success(reviews.answer(id, request.getAnswer()));
    }
}
