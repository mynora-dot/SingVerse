package com.mynora.singverse.media.controller;

import com.mynora.singverse.media.filename.FilenameRuleValidationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = {FilenameParserController.class, MediaSourceController.class})
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class FilenameParserExceptionHandler {
    @ExceptionHandler(com.mynora.singverse.media.repository.FilenameParserConfigRepository.InvalidConfigurationException.class)
    ResponseEntity<ProblemDetail> corrupt(com.mynora.singverse.media.repository.FilenameParserConfigRepository.InvalidConfigurationException error) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "已保存的解析配置损坏或版本不支持；原数据未被修改，请检查配置备份"));
    }
    @ExceptionHandler(com.mynora.singverse.media.service.FilenameParserConfigService.ReferencedRuleException.class)
    ResponseEntity<ProblemDetail> referenced(com.mynora.singverse.media.service.FilenameParserConfigService.ReferencedRuleException error) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, error.getReason());
        problem.setProperty("referencedBy", error.references());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
    @ExceptionHandler(FilenameRuleValidationException.class)
    ResponseEntity<ProblemDetail> validation(FilenameRuleValidationException error) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
        problem.setProperty("ruleId", error.ruleId());
        problem.setProperty("ruleIndex", error.ruleIndex());
        problem.setProperty("field", error.field());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> response(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(ProblemDetail.forStatusAndDetail(error.getStatusCode(), error.getReason()));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> invalid(Exception error) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求 JSON 或规则字段类型无效"));
    }
}
