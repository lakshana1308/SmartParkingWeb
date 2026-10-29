package model;

public class TwoWheelerSpace extends ParkingSpace {
    public TwoWheelerSpace(String spaceId) {
        super(spaceId, "TWO_WHEELER");
    }

    @Override
    public boolean canAccept(Vehicle vehicle) {
        return vehicle != null
                && !isOccupied()
                && vehicle.getVehicleType().equalsIgnoreCase("TWO_WHEELER");
    }
}