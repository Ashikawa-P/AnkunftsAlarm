package de.gabriel.ankunftsalarm;

/** Distance calculations are kept outside the service so they are easy to test. */
public final class DistanceCalculator {
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private DistanceCalculator() {
    }

    public static float metersBetween(
            double fromLatitude,
            double fromLongitude,
            double toLatitude,
            double toLongitude
    ) {
        double latitudeDelta = Math.toRadians(toLatitude - fromLatitude);
        double longitudeDelta = Math.toRadians(toLongitude - fromLongitude);
        double fromLatitudeRadians = Math.toRadians(fromLatitude);
        double toLatitudeRadians = Math.toRadians(toLatitude);
        double sinLatitude = Math.sin(latitudeDelta / 2.0);
        double sinLongitude = Math.sin(longitudeDelta / 2.0);
        double haversine = sinLatitude * sinLatitude
                + Math.cos(fromLatitudeRadians)
                * Math.cos(toLatitudeRadians)
                * sinLongitude * sinLongitude;
        double centralAngle = 2.0 * Math.atan2(
                Math.sqrt(haversine),
                Math.sqrt(Math.max(0.0, 1.0 - haversine))
        );
        return (float) (EARTH_RADIUS_METERS * centralAngle);
    }
}
