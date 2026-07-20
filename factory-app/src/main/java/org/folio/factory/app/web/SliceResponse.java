package org.folio.factory.app.web;

import org.springframework.data.domain.Slice;

import java.util.List;
import java.util.function.Function;

/** Pagination envelope without totals, for feeds where a count query is too expensive. */
public record SliceResponse<T>(List<T> items, int page, int size, boolean hasNext) {

    public static <S, T> SliceResponse<T> of(Slice<S> slice, Function<S, T> mapper) {
        return new SliceResponse<>(
                slice.getContent().stream().map(mapper).toList(),
                slice.getNumber(), slice.getSize(), slice.hasNext());
    }
}
