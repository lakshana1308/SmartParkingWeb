package model;

public class ParkingSpace {
    private String spaceId;
    private String spaceType;
    private boolean occupied;

    public ParkingSpace(String spaceId, String spaceType) {
        this.spaceId = spaceId;
        this.spaceType = spaceType;
        this.occupied = false;
    }

    public String getSpaceId() {
        return spaceId;
    }

    public String getSpaceType() {
        return spaceType;
    }

    public boolean isOccupied() {
        return occupied;
    }

    public void occupy() {
        occupied = true;
    }

    public void release() {
        occupied = false;
    }

    public boolean canAcceptForReservation(Vehicle vehicle) {
        if (vehicle == null) return false;
        if (spaceType.equalsIgnoreCase("CAR")) return vehicle.getVehicleType().equalsIgnoreCase("CAR");
        if (spaceType.equalsIgnoreCase("TWO_WHEELER")) return vehicle.getVehicleType().equalsIgnoreCase("TWO_WHEELER");
        if (spaceType.equalsIgnoreCase("ACCESSIBLE")) return vehicle.getVehicleType().equalsIgnoreCase("ACCESSIBLE") && vehicle.isPermitRequired() && vehicle.isPermitValid();
        return false;
    }

    public boolean canAccept(Vehicle vehicle) {
        if (vehicle == null || occupied) {
            return false;
        }

        if (spaceType.equalsIgnoreCase("CAR")) {
            return vehicle.getVehicleType().equalsIgnoreCase("CAR");
        }

        if (spaceType.equalsIgnoreCase("TWO_WHEELER")) {
            return vehicle.getVehicleType().equalsIgnoreCase("TWO_WHEELER");
        }

        if (spaceType.equalsIgnoreCase("ACCESSIBLE")) {
            return vehicle.getVehicleType().equalsIgnoreCase("ACCESSIBLE")
                    && vehicle.isPermitRequired()
                    && vehicle.isPermitValid();
        }

        return false;
    }
}