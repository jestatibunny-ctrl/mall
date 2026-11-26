package com.mallhub.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class CsvParsingTest {

    @Test
    void parsesPlainFields() {
        assertEquals(List.of("a", "b", "c"), StoreService.parseCsvLine("a,b,c"));
    }

    @Test
    void parsesQuotedFieldWithComma() {
        assertEquals(List.of("Orion Mall", "0", "Food, Drink", "Cafe"),
                StoreService.parseCsvLine("Orion Mall,0,\"Food, Drink\",Cafe"));
    }

    @Test
    void keepsEmptyTrailingField() {
        assertEquals(List.of("a", "b", ""), StoreService.parseCsvLine("a,b,"));
    }

    @Test
    void parsesFloors() {
        assertEquals(0, StoreService.parseFloor("G"));
        assertEquals(0, StoreService.parseFloor("Ground"));
        assertEquals(2, StoreService.parseFloor(" 2 "));
        assertEquals(-1, StoreService.parseFloor("-1"));
        assertThrows(IllegalArgumentException.class, () -> StoreService.parseFloor("roof"));
    }
}
