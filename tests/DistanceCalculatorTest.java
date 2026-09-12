package de.gabriel.ankunftsalarm;

public final class DistanceCalculatorTest {
    public static void main(String[] args) {
        assertNear(0f, DistanceCalculator.metersBetween(52.5, 13.4, 52.5, 13.4), 0.01f);
        assertNear(111.2f, DistanceCalculator.metersBetween(0.0, 0.0, 0.0, 0.001), 0.5f);
        assertNear(
                1_128f,
                DistanceCalculator.metersBetween(52.5251, 13.3694, 52.5163, 13.3777),
                30f
        );
        System.out.println("DistanceCalculator: 3 Tests bestanden");
    }

    private static void assertNear(float expected, float actual, float tolerance) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(
                    "Erwartet " + expected + ", erhalten " + actual
            );
        }
    }
}
