package com.supportiq.api;
import org.springframework.http.*;import org.springframework.dao.DataIntegrityViolationException;import org.springframework.dao.EmptyResultDataAccessException;import org.springframework.security.access.AccessDeniedException;import org.springframework.web.bind.MethodArgumentNotValidException;import org.springframework.web.server.ResponseStatusException;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestControllerAdvice public class ApiExceptionHandler {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);
 @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
 @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Map> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",Objects.toString(e.getReason(),"Request failed.")));}
 @ExceptionHandler(AccessDeniedException.class) public ResponseEntity<Map> forbidden(AccessDeniedException e){return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error","You do not have permission to perform this action."));}
 @ExceptionHandler(DataIntegrityViolationException.class) public ResponseEntity<Map> conflict(DataIntegrityViolationException e){return ResponseEntity.status(409).body(Map.of("error","The request conflicts with an existing record."));}
 @ExceptionHandler(EmptyResultDataAccessException.class) public ResponseEntity<Map> missing(EmptyResultDataAccessException e){return ResponseEntity.status(404).body(Map.of("error","Record not found."));}
 @ExceptionHandler(MethodArgumentNotValidException.class) public ResponseEntity<Map> invalid(MethodArgumentNotValidException e){String message=e.getBindingResult().getFieldErrors().stream().findFirst().map(x->x.getDefaultMessage()).orElse("Request validation failed.");return ResponseEntity.badRequest().body(Map.of("error",message));}
 @ExceptionHandler(Exception.class) public ResponseEntity<Map> fail(Exception e,jakarta.servlet.http.HttpServletRequest request){log.error("Request failed on {} {}",request.getMethod(),request.getRequestURI(),e);return ResponseEntity.status(500).body(Map.of("error","Request failed. Check backend logs."));}
}
