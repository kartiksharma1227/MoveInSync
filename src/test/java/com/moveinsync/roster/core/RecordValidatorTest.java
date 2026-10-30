package com.moveinsync.roster.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.moveinsync.roster.core.RecordValidator.FieldError;
import com.moveinsync.roster.entity.Employee.HrProfile;

class RecordValidatorTest {

    final RecordValidator validator = new RecordValidator(Set.of("BLR-01", "HYD-01")::contains);

    static HrProfile valid() {
        return new HrProfile("Employee 1", "e1@acme.test", "+919800000001", "E9", "BLR-01", "S0900");
    }

    @Test
    void validRecordHasNoErrors() {
        assertThat(validator.validate("E1", 1, valid())).isEmpty();
    }

    @Test
    void malformedPhoneIsRejected() {
        HrProfile hr = new HrProfile("Employee 1", "e1@acme.test", "98000-00001", "E9", "BLR-01", "S0900");

        assertThat(validator.validate("E1", 1, hr)).containsExactly(new FieldError("phone", "INVALID_FORMAT"));
    }

    @Test
    void unknownOfficeIsRejected() {
        HrProfile hr = new HrProfile("Employee 1", "e1@acme.test", "+919800000001", "E9", "XXX-99", "S0900");

        assertThat(validator.validate("E1", 1, hr)).containsExactly(new FieldError("officeCode", "UNKNOWN_OFFICE"));
    }

    @Test
    void everyProblemInARecordIsReported() {
        HrProfile hr = new HrProfile("Employee 1", "not-an-email", "12345", "E9", "XXX-99", "S0900");

        List<FieldError> errors = validator.validate("E1", 1, hr);

        assertThat(errors).containsExactlyInAnyOrder(
                new FieldError("email", "INVALID_FORMAT"),
                new FieldError("phone", "INVALID_FORMAT"),
                new FieldError("officeCode", "UNKNOWN_OFFICE"));
    }

    @Test
    void blankAndOverlongFieldsAreRejected() {
        HrProfile hr = new HrProfile(" ", "e1@acme.test", "+919800000001", "E9", "BLR-01", "S".repeat(33));

        assertThat(validator.validate("E1", 1, hr)).containsExactlyInAnyOrder(
                new FieldError("name", "REQUIRED"),
                new FieldError("shift", "TOO_LONG"));
    }

    @Test
    void missingEmployeeIdAndNonPositiveVersionAreRejected() {
        assertThat(validator.validate("", 0, valid())).containsExactlyInAnyOrder(
                new FieldError("employeeId", "REQUIRED"),
                new FieldError("version", "INVALID_VERSION"));
    }

    @Test
    void employeeIdMustLookLikeAnId() {
        assertThat(validator.validate("emp-42.k_A", 1, valid())).isEmpty();
        for (String bad : new String[] {"Priya Sharma", "+919811111111", "x@acme.test", "E".repeat(65)}) {
            assertThat(validator.validate(bad, 1, valid())).as(bad)
                    .containsExactly(new FieldError("employeeId", "INVALID_FORMAT"));
        }
    }

    @Test
    void onlyIdShapedValuesAreEverEchoed() {
        assertThat(RecordValidator.safeId("E1001")).isEqualTo("E1001");
        assertThat(RecordValidator.safeId("Priya Sharma +919811111111")).isNull();
        assertThat(RecordValidator.safeId(null)).isNull();
    }

    @Test
    void deactivateWithoutHrFieldsOnlyChecksTheEnvelope() {
        assertThat(validator.validate("E1", 3, null)).isEmpty();
    }

    @Test
    void errorsNameTheFieldButNeverContainTheValue() {
        HrProfile hr = new HrProfile("Employee 1", "e1@acme.test", "98000-00001", "E9", "BLR-01", "S0900");

        FieldError error = validator.validate("E1", 1, hr).get(0);

        assertThat(error.toString()).doesNotContain("98000-00001");
    }
}
