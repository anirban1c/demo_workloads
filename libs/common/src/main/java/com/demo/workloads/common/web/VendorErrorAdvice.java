package com.demo.workloads.common.web;

import com.demo.workloads.common.vendor.VendorRejectedException;
import com.demo.workloads.common.vendor.VendorUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Sync endpoints: vendor rejections keep a 4xx, transient vendor trouble becomes 503 + Retry-After. */
@RestControllerAdvice
public class VendorErrorAdvice {

    @ExceptionHandler(VendorRejectedException.class)
    ResponseEntity<ProblemDetail> rejected(VendorRejectedException e) {
        HttpStatus status = e.status() == 401 || e.status() == 403 ? HttpStatus.valueOf(e.status())
                : HttpStatus.UNPROCESSABLE_ENTITY;
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        pd.setProperty("vendor", e.vendor());
        return ResponseEntity.status(status).body(pd);
    }

    @ExceptionHandler(VendorUnavailableException.class)
    ResponseEntity<ProblemDetail> unavailable(VendorUnavailableException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        pd.setProperty("vendor", e.vendor());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5").body(pd);
    }
}
