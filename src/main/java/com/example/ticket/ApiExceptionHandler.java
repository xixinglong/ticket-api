package com.example.ticket;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Controller 抛出的异常在这里转换成稳定的 JSON。
 * 登录和 CSRF 在进入 Controller 前处理，因此 SecurityConfig 也要遵守同一响应契约。
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(TicketNotFoundException.class)
    ResponseEntity<Object> notFound(TicketNotFoundException exception, HttpServletRequest request) {
        return error(404, "TICKET_NOT_FOUND", "工单不存在或不可见", request);
    }

    @ExceptionHandler(TicketConflictException.class)
    ResponseEntity<Object> conflict(TicketConflictException exception, HttpServletRequest request) {
        return error(409, "TICKET_CONFLICT", "工单状态已变化，请重新查询后操作", request);
    }

    @ExceptionHandler(InvalidPaginationException.class)
    ResponseEntity<Object> pagination(InvalidPaginationException exception, HttpServletRequest request) {
        return error(400, "INVALID_PAGINATION", "page 必须大于等于 1，size 必须在 1 到 100 之间", request);
    }

    @ExceptionHandler(InvalidTicketException.class)
    ResponseEntity<Object> invalidTicket(InvalidTicketException exception, HttpServletRequest request) {
        return error(400, "INVALID_REQUEST", "标题不能为空，且不能超过 100 个字符", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> unauthenticated(AuthenticationException exception, HttpServletRequest request) {
        return error(401, "UNAUTHENTICATED", "请先登录", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> forbidden(AccessDeniedException exception, HttpServletRequest request) {
        return error(403, "FORBIDDEN", "无权执行本次操作", request);
    }

    // 保留框架给出的状态和响应头，例如 405 的 Allow；不返回原始解析异常。
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        String code = switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_REJECTED";
        };
        String message = switch (status.value()) {
            case 400 -> "请求参数不合法，请检查字段、JSON 格式和分页参数";
            case 404 -> "请求资源不存在";
            case 405 -> "请求方法不支持";
            case 415 -> "请求内容类型不支持，请使用接口要求的 Content-Type";
            default -> status.is5xxServerError() ? "服务暂时无法处理请求，请凭请求编号联系排查" : "请求未被接受";
        };
        if (status.is5xxServerError()) {
            log.error("requestId={} MVC request failed", RequestIdFilter.getOrCreate(servletRequest), exception);
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.set(RequestIdFilter.HEADER, RequestIdFilter.getOrCreate(servletRequest));
        return new ResponseEntity<>(new ApiError(code, message,
                RequestIdFilter.getOrCreate(servletRequest)), responseHeaders, status);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, HttpServletRequest request) {
        // 堆栈留在服务端；响应不包含 SQL、连接配置或底层异常文本。
        log.error("requestId={} API request failed", RequestIdFilter.getOrCreate(request), exception);
        return error(500, "INTERNAL_ERROR", "服务暂时无法处理请求，请凭请求编号联系排查", request);
    }

    private ResponseEntity<Object> error(int status, String code, String message,
            HttpServletRequest request) {
        String requestId = RequestIdFilter.getOrCreate(request);
        return ResponseEntity.status(status).header(RequestIdFilter.HEADER, requestId)
                .body(new ApiError(code, message, requestId));
    }
}
