package com.aziz.stickeratolyesi;

import java.util.ArrayList;
import java.util.List;

/** Keeps every item once; rebalances a 1–2 item tail without duplicating stickers. */
public final class PackPlanner {
    private PackPlanner() {}
    public static List<Integer> sizes(int count) {
        if (count < 0) throw new IllegalArgumentException("count");
        List<Integer> result = new ArrayList<>();
        while (count > 30) { result.add(30); count -= 30; }
        if (count > 0) {
            if (count < 3 && !result.isEmpty()) {
                int last = result.size() - 1;
                result.set(last, result.get(last) - (3 - count));
                count = 3;
            }
            result.add(count);
        }
        return result;
    }
}
