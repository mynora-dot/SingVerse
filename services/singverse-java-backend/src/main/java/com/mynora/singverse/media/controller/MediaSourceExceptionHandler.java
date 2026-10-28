package com.mynora.singverse.media.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = {MediaSourceController.class, MediaSongController.class, MetadataController.class, com.mynora.singverse.media.webdav.ScanTaskController.class})
public class MediaSourceExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> handle(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(
                        ProblemDetail.forStatusAndDetail(
                                error.getStatusCode(), error.getReason()));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> uploadTooLarge(Exception error) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "封面上传超过大小限制，请选择 2 MB 以内的图片"));
    }
    @ExceptionHandler({
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ProblemDetail> invalid(Exception error) {
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求格式无效"));
    }
}
