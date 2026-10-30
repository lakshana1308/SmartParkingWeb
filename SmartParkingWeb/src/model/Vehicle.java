package model;

public class Vehicle {
    private String vehicleId;
    private String vehicleType;
    private boolean permitRequired;
    private boolean permitValid;

    public Vehicle(String vehicleId, String vehicleType,
                   boolean permitRequired, boolean permitValid) {
        this.vehicleId = vehicleId;
        this.vehicleType = vehicleType;
        this.permitRequired = permitRequired;
        this.permitValid = permitValid;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public String getVehicleType() {
        return vehicleType;
    }

    public boolean isPermitRequired() {
        return permitRequired;
    }

    public boolean isPermitValid() {
        return permitValid;
    }

    public void displayVehicle() {
        System.out.println("Vehicle ID      : " + vehicleId);
        System.out.println("Vehicle Type    : " + vehicleType);
        System.out.println("Permit Required : " + (permitRequired ? "Yes" : "No"));
        System.out.println("Permit Valid    : " + (permitValid ? "Yes" : "No"));
    }
}