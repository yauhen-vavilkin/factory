package org.folio.factory.app.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.NoSuchElementException;

/**
 * HTML error handling for the server-rendered pages. REST controllers are
 * covered first by the higher-precedence {@link ApiExceptionHandler}, so this
 * advice only ever renders for browser-facing {@code @Controller} views.
 */
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class UiExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NoSuchElementException e, Model model) {
        model.addAttribute("message", e.getMessage());
        return "error";
    }
}
