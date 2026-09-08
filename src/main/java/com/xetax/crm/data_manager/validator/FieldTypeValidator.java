package com.xetax.crm.data_manager.validator;

import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.entity.FormField;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class FieldTypeValidator {

    public void validate(Map<String, Object> data,
                         List<FormField> fields) {

        for (FormField field : fields) {

            Object value = data.get(field.getFieldKey());

            if (value == null) {
                continue;
            }

            switch (field.getFieldType()) {

                case TEXT -> validateText(field, value);

                case NUMBER -> validateNumber(field, value);

                case BOOLEAN -> validateBoolean(field, value);

                case EMAIL -> validateEmail(field, value);

                case PHONE -> validatePhone(field, value);

                case DATE -> validateDate(field, value);

                case DATETIME -> validateDateTime(field, value);
            }

        }

    }

    private void validateText(FormField field, Object value) {

        if (!(value instanceof String)) {
            throw new BadRequestException(field.getLabel() + " must be text");
        }

    }

    private void validateNumber(FormField field, Object value) {

        if (!(value instanceof Number)) {
            throw new BadRequestException(field.getLabel() + " must be number");
        }

    }

    private void validateBoolean(FormField field, Object value) {

        if (!(value instanceof Boolean)) {
            throw new BadRequestException(field.getLabel() + " must be boolean");
        }

    }

    private void validateEmail(FormField field, Object value) {

        if (!(value instanceof String email)
                || !email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")) {

            throw new BadRequestException(
                    field.getLabel() + " must be valid email");

        }

    }

    private void validatePhone(FormField field, Object value) {

        if (!(value instanceof String phone)
                || !phone.matches("^[0-9]{10}$")) {

            throw new BadRequestException(
                    field.getLabel() + " must be valid phone number");

        }

    }

    private void validateDate(FormField field, Object value) {

        try {

            LocalDate.parse(value.toString());

        } catch (Exception ex) {

            throw new BadRequestException(
                    field.getLabel() + " must be valid date (yyyy-MM-dd)");

        }

    }

    private void validateDateTime(FormField field, Object value) {

        try {

            LocalDateTime.parse(value.toString());

        } catch (Exception ex) {

            throw new BadRequestException(
                    field.getLabel() + " must be valid datetime");

        }

    }

}
