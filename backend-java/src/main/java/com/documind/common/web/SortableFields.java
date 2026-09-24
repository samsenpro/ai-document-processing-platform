package com.documind.common.web;

import com.documind.exception.ApiException;
import com.documind.exception.ErrorCode;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;
import java.util.Set;

/**
 * Lista blanca de campos por los que el cliente puede ordenar. Sin ella, el parámetro {@code sort}
 * llegaría tal cual a la consulta: un campo inexistente daría un 500 y un campo interno permitiría
 * ordenar (e inferir) datos que la API no expone.
 */
public final class SortableFields {

    private SortableFields() {
    }

    public static Pageable validate(Pageable pageable, Set<String> allowed) {
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Invalid sort property '" + order.getProperty() + "'", Map.of("allowedSortProperties", allowed));
            }
        }
        return pageable;
    }
}
