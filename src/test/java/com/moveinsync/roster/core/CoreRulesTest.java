package com.moveinsync.roster.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.moveinsync.roster.entity.Employee.HrProfile;

class CoreRulesTest {

    @Nested
    class VersionGateRules {

        @Test
        void higherVersionIsApplied() {
            assertThat(VersionGate.decide(5, 6)).isEqualTo(ApplyOutcome.APPLIED);
        }

        @Test
        void sameVersionIsADuplicate() {
            assertThat(VersionGate.decide(5, 5)).isEqualTo(ApplyOutcome.DUPLICATE);
        }

        @Test
        void lowerVersionIsStale() {
            assertThat(VersionGate.decide(7, 6)).isEqualTo(ApplyOutcome.STALE);
        }
    }

    @Nested
    class RowHasherRules {

        final HrProfile base = new HrProfile("Employee 1", "e1@acme.test", "+919800000001", "E9", "BLR-01", "S0900");

        @Test
        void sameInputGivesSameHash() {
            HrProfile copy = new HrProfile("Employee 1", "e1@acme.test", "+919800000001", "E9", "BLR-01", "S0900");

            assertThat(RowHasher.hash(copy)).isEqualTo(RowHasher.hash(base));
        }

        @Test
        void hashIsSha256Hex() {
            assertThat(RowHasher.hash(base)).hasSize(64).matches("[0-9a-f]+");
        }

        @Test
        void changingAnyHrFieldChangesTheHash() {
            String original = RowHasher.hash(base);

            assertThat(RowHasher.hash(new HrProfile("X", "e1@acme.test", "+919800000001", "E9", "BLR-01", "S0900"))).isNotEqualTo(original);
            assertThat(RowHasher.hash(new HrProfile("Employee 1", "x@acme.test", "+919800000001", "E9", "BLR-01", "S0900"))).isNotEqualTo(original);
            assertThat(RowHasher.hash(new HrProfile("Employee 1", "e1@acme.test", "+919800000002", "E9", "BLR-01", "S0900"))).isNotEqualTo(original);
            assertThat(RowHasher.hash(new HrProfile("Employee 1", "e1@acme.test", "+919800000001", "E8", "BLR-01", "S0900"))).isNotEqualTo(original);
            assertThat(RowHasher.hash(new HrProfile("Employee 1", "e1@acme.test", "+919800000001", "E9", "BLR-02", "S0900"))).isNotEqualTo(original);
            assertThat(RowHasher.hash(new HrProfile("Employee 1", "e1@acme.test", "+919800000001", "E9", "BLR-01", "S1400"))).isNotEqualTo(original);
        }

        @Test
        void missingManagerHashesLikeEmptyManager() {
            HrProfile noManager = new HrProfile("Employee 1", "e1@acme.test", "+919800000001", null, "BLR-01", "S0900");
            HrProfile emptyManager = new HrProfile("Employee 1", "e1@acme.test", "+919800000001", "", "BLR-01", "S0900");

            assertThat(RowHasher.hash(noManager)).isEqualTo(RowHasher.hash(emptyManager));
        }
    }

    @Nested
    class SafetyBrakeRules {

        final SafetyBrake brake = new SafetyBrake(0.30);

        /** The case study's example: the nightly file is cut off at 120,000 of 200,000 rows. */
        @Test
        void fileCutAt120kOf200kIsHeld() {
            SafetyBrake.Decision decision = brake.evaluate(200_000, 80_000);

            assertThat(decision.held()).isTrue();
            assertThat(decision.missingCount()).isEqualTo(80_000);
            assertThat(decision.missingRatio()).isCloseTo(0.40, within(1e-9));
        }

        @Test
        void normalChurnProceeds() {
            assertThat(brake.evaluate(200_000, 50_000).held()).isFalse(); // 25%
        }

        @Test
        void exactlyAtThresholdIsHeld() {
            assertThat(brake.evaluate(1_000, 300).held()).isTrue(); // 30%
        }

        @Test
        void firstEverLoadWithNoActiveEmployeesProceeds() {
            assertThat(brake.evaluate(0, 0).held()).isFalse();
        }

        @Test
        void thresholdIsConfigurable() {
            SafetyBrake lenient = new SafetyBrake(0.50);

            assertThat(lenient.evaluate(200_000, 80_000).held()).isFalse(); // 40% < 50%
        }

        @Test
        void invalidThresholdIsRejected() {
            assertThatThrownBy(() -> new SafetyBrake(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SafetyBrake(1.5)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
