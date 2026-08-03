package com.aitutor.exception;

import com.aitutor.common.Result;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException ex) {
        // BusinessException already carries the API-level code expected by the frontend.
        return Result.failure(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class
    })
    public Result<Void> handleBadRequest(Exception ex) {
        return Result.failure(400, "参数错误");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception ex) {
        // Unexpected details stay in server logs; clients receive a stable generic message.
        log.error("Unexpected server error", ex);
        return Result.failure(500, "系统异常");
    }
}
