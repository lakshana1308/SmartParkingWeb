package model;

public class ParkingRequest {
    private String requestId;
    private Vehicle vehicle;
    private int requestTime;
    private int accessPriority;
    private String status;

    public ParkingRequest(String requestId, Vehicle vehicle,
                          int requestTime, int accessPriority) {
        this.requestId = requestId;
        this.vehicle = vehicle;
        this.requestTime = requestTime;
        this.accessPriority = accessPriority;
        this.status = "WAITING";
    }

    public String getRequestId() {
        return requestId;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public int getRequestTime() {
        return requestTime;
    }

    public int getAccessPriority() {
        return accessPriority;
    }

    public String getStatus() {
        return status;
    }

    public void setAccessPriority(int accessPriority) {
        this.accessPriority = accessPriority;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void displayRequest() {
        int hour = requestTime / 60;
        int minute = requestTime % 60;
        String arrival = String.format("%02d:%02d", hour, minute);

        System.out.printf("%-12s %-15s %-10d %-12s %-12s%n",
                requestId,
                vehicle.getVehicleId(),
                accessPriority,
                arrival,
                status);
    }
}