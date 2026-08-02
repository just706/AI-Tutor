package com.aitutor;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.aitutor.mapper")
@SpringBootApplication
public class AiTutorApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiTutorApplication.class, args);
    }
}
