package model;

import java.util.ArrayList;

public class ParkingLot {
    private String lotId;
    private ArrayList<ParkingSpace> spaces;

    public ParkingLot(String lotId) {
        this.lotId = lotId;
        this.spaces = new ArrayList<>();
    }

    public String getLotId() {
        return lotId;
    }

    public void addSpace(ParkingSpace space) {
        if (space != null) {
            spaces.add(space);
        }
    }

    public ArrayList<ParkingSpace> getSpaces() {
        return spaces;
    }

    public ParkingSpace findSpace(String spaceId) {
        for (ParkingSpace space : spaces) {
            if (space.getSpaceId().equalsIgnoreCase(spaceId)) {
                return space;
            }
        }
        return null;
    }

    public ParkingSpace findCompatibleSpace(Vehicle vehicle) {
        for (ParkingSpace space : spaces) {
            if (space.canAccept(vehicle)) {
                return space;
            }
        }
        return null;
    }

    public int getTotalSpaces() {
        return spaces.size();
    }

    public int getAvailableSpaces() {
        int count = 0;
        for (ParkingSpace space : spaces) {
            if (!space.isOccupied()) {
                count++;
            }
        }
        return count;
    }

    public int getOccupiedSpaces() {
        return getTotalSpaces() - getAvailableSpaces();
    }

    public void displaySpaces() {
        System.out.println("\nParking Lot: " + lotId);
        System.out.printf("%-12s %-18s %-12s%n",
                "Space ID", "Type", "Status");

        for (ParkingSpace space : spaces) {
            System.out.printf("%-12s %-18s %-12s%n",
                    space.getSpaceId(),
                    space.getSpaceType(),
                    space.isOccupied() ? "OCCUPIED" : "AVAILABLE");
        }
    }

    public void displaySummary() {
        System.out.printf("%-12s %-10d %-12d %-10d%n",
                lotId,
                getTotalSpaces(),
                getAvailableSpaces(),
                getOccupiedSpaces());
    }
}