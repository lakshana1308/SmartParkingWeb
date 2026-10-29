package model;

public class CarSpace extends ParkingSpace {
    public CarSpace(String spaceId) {
        super(spaceId, "CAR");
    }

    @Override
    public boolean canAccept(Vehicle vehicle) {
        return vehicle != null&& !isOccupied()&& vehicle.getVehicleType().equalsIgnoreCase("CAR");
    }
}