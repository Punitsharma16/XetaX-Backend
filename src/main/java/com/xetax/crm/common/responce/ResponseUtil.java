package com.xetax.crm.common.responce;

public class ResponseUtil {


    private ResponseUtil() {}


    public static <T> ApiResponse<T> success(String message, T data) {

        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    public static ApiResponse<Void> success(String message) {

        return ApiResponse.<Void>builder()
                .success(true)
                .message(message)
                .build();
    }

    public static ApiResponse<Void> error(String message) {

        return ApiResponse.<Void>builder()
                .success(false)
                .message(message)
                .build();
    }
}
