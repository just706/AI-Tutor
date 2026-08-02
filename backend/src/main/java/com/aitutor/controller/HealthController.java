package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.exception.BusinessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping
    public Result<Map<String, String>> health() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("service", "ai-tutor-backend");
        return Result.success(data);
    }

    @GetMapping("/db")
    public Result<Map<String, String>> databaseHealth() {
        try (Connection connection = dataSource.getConnection()) {
            Map<String, String> data = new LinkedHashMap<>();
            data.put("status", "UP");
            data.put("database", connection.getMetaData().getDatabaseProductName());
            return Result.success(data);
        } catch (SQLException ex) {
            throw new BusinessException(500, "数据库连接失败");
        }
    }
}
