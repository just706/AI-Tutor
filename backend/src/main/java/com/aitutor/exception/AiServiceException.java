package com.aitutor.exception;

public class AiServiceException extends BusinessException {

    public AiServiceException(String message) {
        super(600, message);
    }
}
