package org.dromara.semirisk.monolith;

public class ApiResponse<T> {
    public int code;
    public String msg;
    public T data;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = 200;
        response.msg = "success";
        response.data = data;
        return response;
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }
}
