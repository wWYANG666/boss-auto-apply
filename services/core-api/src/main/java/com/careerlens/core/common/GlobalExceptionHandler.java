package com.careerlens.core.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException ex, HttpServletRequest request) {
        return problem(ex.getStatus(), ex.getCode(), ex.getMessage(), request, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList();
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数校验失败", request, errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> constraint(ConstraintViolationException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), request, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> duplicate(DataIntegrityViolationException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "DATA_CONFLICT", "数据已存在或当前状态发生冲突", request, null);
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> concurrentWrite(Exception ex, HttpServletRequest request) {
        return problem(HttpStatus.PRECONDITION_FAILED, "DRAFT_REVISION_CONFLICT",
                "草稿已被另一处更新，请重新加载并合并", request, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ex, HttpServletRequest request) {
        if (ex instanceof org.springframework.web.ErrorResponse error) {
            HttpStatus status = HttpStatus.resolve(error.getStatusCode().value());
            if (status != null && status.is4xxClientError())
                return problem(status, "INVALID_REQUEST", "请求路径、方法或参数不符合接口要求", request, null);
        }
        if (ex instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || ex instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException)
            return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求格式或参数类型错误", request, null);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务处理请求时发生错误", request, null);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail,
                                                   HttpServletRequest request, Object errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(code);
        problem.setType(URI.create("https://careerlens.dev/problems/" + code.toLowerCase().replace('_', '-')));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now());
        String requestId = request.getHeader("X-Request-Id");
        if (requestId != null) problem.setProperty("requestId", requestId);
        if (errors != null) problem.setProperty("errors", errors);
        return ResponseEntity.status(status).body(problem);
    }
}
