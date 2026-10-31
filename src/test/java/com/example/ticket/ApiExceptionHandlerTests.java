package com.example.ticket;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 不连接 MySQL：只检查 MVC 错误契约。真实权限链由 TicketSecurityTests 验证。 */
class ApiExceptionHandlerTests {
    private TicketService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(TicketService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TicketController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter()).build();
    }

    @Test
    void invisibleTicketHasStable404AndMatchingRequestId() throws Exception {
        when(service.find("999")).thenReturn(Optional.empty());
        var result = mvc.perform(get("/api/tickets/999").header("X-Request-Id", "untrusted-client-value"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.requestId").isString()).andReturn();
        String id = result.getResponse().getHeader("X-Request-Id");
        assertNotEquals("untrusted-client-value", id);
        assertEquals(id, com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.requestId"));
    }

    @Test
    void stateConflictRemains409() throws Exception {
        doThrow(new TicketConflictException()).when(service).claim("1");
        mvc.perform(post("/api/tickets/1/claim"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_CONFLICT"));
    }

    @Test
    void invalidPaginationAndNonNumericPageHave400Responses() throws Exception {
        when(service.list(anyInt(), anyInt())).thenThrow(new InvalidPaginationException());
        mvc.perform(get("/api/tickets?page=0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
        mvc.perform(get("/api/tickets?page=abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void malformedJsonAndBlankTitleNeverReachService() throws Exception {
        mvc.perform(post("/api/tickets").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/tickets").contentType("application/json").content("{\"title\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void unsupportedMethodPreservesAllowHeader() throws Exception {
        mvc.perform(put("/api/tickets/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void serviceAuthenticationAndAuthorizationFailuresKeepTheirStatus() throws Exception {
        when(service.find("1")).thenThrow(
                new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("internal"));
        mvc.perform(get("/api/tickets/1"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        when(service.find("2")).thenThrow(new org.springframework.security.access.AccessDeniedException("internal"));
        mvc.perform(get("/api/tickets/2"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void unsupportedMediaTypeRemains415() throws Exception {
        mvc.perform(post("/api/tickets").contentType("text/plain").content("plain text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void internalFailureDoesNotLeakExceptionDetails() throws Exception {
        when(service.create(any(), any())).thenThrow(new IllegalStateException("private SQL and credentials"));
        mvc.perform(post("/api/tickets").contentType("application/json").content("{\"title\":\"valid\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("private SQL"))))
                .andExpect(jsonPath("$.requestId").isString());
    }
}
