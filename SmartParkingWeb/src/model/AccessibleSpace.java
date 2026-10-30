package model;

public class AccessibleSpace extends ParkingSpace {
    public AccessibleSpace(String spaceId) {
        super(spaceId, "ACCESSIBLE");
    }

    @Override
    public boolean canAccept(Vehicle vehicle) {
        return vehicle != null
                && !isOccupied()
                && vehicle.getVehicleType().equalsIgnoreCase("ACCESSIBLE")
                && vehicle.isPermitRequired()
                && vehicle.isPermitValid();
    }
}