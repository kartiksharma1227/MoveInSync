package com.moveinsync.roster.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.moveinsync.roster.entity.Employee.HrProfile;

/**
 * Boundary validation for one incoming record. It reports every problem in the record, and each error names only
 * the field and a code, never the value, so personal data cannot leak into error reports.
 */
public class RecordValidator {

    public record FieldError(String field, String code) {
    }

    /** One validation rule. New rules are added as new classes, without touching the others. */
    public interface FieldRule {
        List<FieldError> check(HrProfile hr);
    }

    private final List<FieldRule> rules;

    /** @param officeExists tells whether an office code exists for the caller's tenant */
    public RecordValidator(Predicate<String> officeExists) {
        this.rules = List.of(new BasicFieldsRule(), new PhoneRule(), new EmailRule(), new OfficeCodeRule(officeExists));
    }

    /** @param hr the HR fields, or null for a deactivate event, which carries none */
    public List<FieldError> validate(String employeeId, long version, HrProfile hr) {
        List<FieldError> errors = new ArrayList<>();
        if (isBlank(employeeId)) {
            errors.add(new FieldError("employeeId", "REQUIRED"));
        } else if (employeeId.length() > 64) {
            errors.add(new FieldError("employeeId", "TOO_LONG"));
        }
        if (version <= 0) {
            errors.add(new FieldError("version", "INVALID_VERSION"));
        }
        if (hr != null) {
            for (FieldRule rule : rules) {
                errors.addAll(rule.check(hr));
            }
        }
        return errors;
    }

    /** Required fields are present, and no field is longer than its database column. */
    static class BasicFieldsRule implements FieldRule {
        @Override
        public List<FieldError> check(HrProfile hr) {
            List<FieldError> errors = new ArrayList<>();
            required(errors, "name", hr.name(), 200);
            required(errors, "email", hr.email(), 200);
            required(errors, "phone", hr.phone(), 20);
            required(errors, "officeCode", hr.officeCode(), 32);
            required(errors, "shift", hr.shift(), 32);
            if (hr.managerId() != null && hr.managerId().length() > 64) {
                errors.add(new FieldError("managerId", "TOO_LONG"));
            }
            return errors;
        }

        private void required(List<FieldError> errors, String field, String value, int maxLength) {
            if (isBlank(value)) {
                errors.add(new FieldError(field, "REQUIRED"));
            } else if (value.length() > maxLength) {
                errors.add(new FieldError(field, "TOO_LONG"));
            }
        }
    }

    /** International format, e.g. +919800001001. */
    static class PhoneRule implements FieldRule {
        private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{9,14}$");

        @Override
        public List<FieldError> check(HrProfile hr) {
            if (isBlank(hr.phone()) || E164.matcher(hr.phone()).matches()) {
                return List.of();
            }
            return List.of(new FieldError("phone", "INVALID_FORMAT"));
        }
    }

    static class EmailRule implements FieldRule {
        private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

        @Override
        public List<FieldError> check(HrProfile hr) {
            if (isBlank(hr.email()) || EMAIL.matcher(hr.email()).matches()) {
                return List.of();
            }
            return List.of(new FieldError("email", "INVALID_FORMAT"));
        }
    }

    static class OfficeCodeRule implements FieldRule {
        private final Predicate<String> officeExists;

        OfficeCodeRule(Predicate<String> officeExists) {
            this.officeExists = officeExists;
        }

        @Override
        public List<FieldError> check(HrProfile hr) {
            if (isBlank(hr.officeCode()) || officeExists.test(hr.officeCode())) {
                return List.of();
            }
            return List.of(new FieldError("officeCode", "UNKNOWN_OFFICE"));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
