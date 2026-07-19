package org.folio.factory.app.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

final class PageValidation {

    private static final int MAX_SIZE = 200;

    private PageValidation() {
    }

    static Pageable pageable(int page, int size, Sort sort) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }
        return PageRequest.of(page, size, sort);
    }

    static Pageable pageable(int page, int size) {
        return pageable(page, size, Sort.unsorted());
    }
}
