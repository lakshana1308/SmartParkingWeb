package model;

public class Reservation {
    private final String reservationId;
    private final String vehicleId;
    private final String spaceId;
    private final int startTime;
    private int endTime;
    private String status;
    public static final int GRACE_PERIOD_MINUTES = 15;

    public Reservation(String reservationId, String vehicleId, String spaceId, int startTime, int endTime) {
        if (reservationId == null || reservationId.trim().isEmpty()) throw new IllegalArgumentException("Reservation ID cannot be empty.");
        if (vehicleId == null || vehicleId.trim().isEmpty()) throw new IllegalArgumentException("Vehicle ID cannot be empty.");
        if (spaceId == null || spaceId.trim().isEmpty()) throw new IllegalArgumentException("Space ID cannot be empty.");
        if (startTime < 0 || startTime >= 1440 || endTime > 1440 || endTime <= startTime) throw new IllegalArgumentException("Invalid reservation time interval.");
        this.reservationId = reservationId;
        this.vehicleId = vehicleId;
        this.spaceId = spaceId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = "ACTIVE";
    }

    public String getReservationId() { return reservationId; }
    public String getVehicleId() { return vehicleId; }
    public String getSpaceId() { return spaceId; }
    public int getStartTime() { return startTime; }
    public int getEndTime() { return endTime; }
    public String getStatus() { return status; }
    /** Restore a status loaded from persistent storage. */
    public void restoreStatus(String status) {
        if (!java.util.Set.of("ACTIVE", "CANCELLED", "CHECKED_IN", "COMPLETED", "EXPIRED").contains(status)) {
            throw new IllegalArgumentException("Invalid reservation status: " + status);
        }
        this.status = status;
    }
    public boolean isActive() { return status.equals("ACTIVE"); }
    public boolean isCancelled() { return status.equals("CANCELLED"); }
    public boolean isCheckedIn() { return status.equals("CHECKED_IN"); }
    public boolean isCompleted() { return status.equals("COMPLETED"); }
    public boolean isExpired() { return status.equals("EXPIRED"); }

    public boolean extendTo(int newEndTime) {
        if (!(isActive() || isCheckedIn()) || newEndTime <= endTime || newEndTime > 1440) return false;
        endTime = newEndTime;
        return true;
    }

    public boolean overlaps(int otherStart, int otherEnd) {
        return startTime < otherEnd && otherStart < endTime;
    }

    public boolean isWithinCheckInWindow(int currentTime) {
        return isActive() && currentTime >= startTime && currentTime < endTime;
    }

    public boolean isGracePeriodExpired(int currentTime) {
        return isActive() && currentTime > startTime + GRACE_PERIOD_MINUTES;
    }

    public boolean isExpired(int currentTime) {
        return isActive() && currentTime >= endTime;
    }

    public boolean cancel() {
        if (!isActive()) return false;
        status = "CANCELLED";
        return true;
    }

    public boolean checkIn() {
        if (!isActive()) return false;
        status = "CHECKED_IN";
        return true;
    }

    public boolean checkOut() {
        if (!isCheckedIn()) return false;
        status = "COMPLETED";
        return true;
    }

    public boolean expire() {
        if (!isActive()) return false;
        status = "EXPIRED";
        return true;
    }

    public void displayReservation() {
        System.out.printf("%-15s %-15s %-12s %-10s %-10s %-12s%n", reservationId, vehicleId, spaceId, formatTime(startTime), formatTime(endTime), status);
    }

    private String formatTime(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
