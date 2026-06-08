package org.dromara.semirisk.monolith;

import java.util.List;

public class PageResponse<T> {
    public int code = 200;
    public String msg = "success";
    public long total;
    public List<T> rows;

    public PageResponse(List<T> rows, long total) {
        this.rows = rows;
        this.total = total;
    }
}
