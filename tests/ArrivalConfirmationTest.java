package de.gabriel.ankunftsalarm;

public final class ArrivalConfirmationTest {
    public static void main(String[] args) {
        ArrivalConfirmation confirmation = new ArrivalConfirmation();

        assertFalse(confirmation.record(true), "Erste Innenmessung darf nicht auslösen");
        assertTrue(confirmation.record(true), "Zweite Innenmessung muss auslösen");

        assertFalse(confirmation.record(true), "Zähler muss nach Alarm zurückgesetzt sein");
        assertFalse(confirmation.record(false), "Außenmessung darf nicht auslösen");
        assertFalse(confirmation.record(true), "Außenmessung muss Innenfolge unterbrechen");
        assertTrue(confirmation.record(true), "Neue zweite Innenmessung muss auslösen");

        System.out.println("ArrivalConfirmation: 6 Tests bestanden");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        if (value) {
            throw new AssertionError(message);
        }
    }
}
