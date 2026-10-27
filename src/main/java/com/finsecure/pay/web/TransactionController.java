package com.finsecure.pay.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.finsecure.pay.model.TransactionRequest;
import com.finsecure.pay.model.ValidationResult;
import com.finsecure.pay.service.ValidationService;

@RestController
public class TransactionController {

    private final ValidationService validationService;

    public TransactionController(ValidationService validationService) {
        this.validationService = validationService;
    }

    @PostMapping(path = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ValidationResult> validate(@RequestBody TransactionRequest request) {
        ValidationResult result = validationService.validate(request);
        int status = result.valid() ? 200 : 422;
        return ResponseEntity.status(status).body(result);
    }
}
