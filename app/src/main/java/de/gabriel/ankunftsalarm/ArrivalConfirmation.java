package de.gabriel.ankunftsalarm;

/** Prevents a single erroneous location fix from triggering the arrival alarm. */
final class ArrivalConfirmation {
    private static final int REQUIRED_CONSECUTIVE_FIXES = 2;

    private int consecutiveInsideFixes;

    boolean record(boolean insideRadius) {
        if (!insideRadius) {
            reset();
            return false;
        }
        consecutiveInsideFixes++;
        if (consecutiveInsideFixes >= REQUIRED_CONSECUTIVE_FIXES) {
            reset();
            return true;
        }
        return false;
    }

    void reset() {
        consecutiveInsideFixes = 0;
    }
}
