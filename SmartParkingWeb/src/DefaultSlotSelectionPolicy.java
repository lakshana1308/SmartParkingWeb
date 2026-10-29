import model.ParkingSpace;
import model.Vehicle;

/**
 * Default policy for checking parking-space eligibility.
 */
public class DefaultSlotSelectionPolicy
        implements SlotSelectionPolicy {

    @Override
    public boolean isEligible(
            ParkingSpace space,
            Vehicle vehicle,
            boolean reservationConflict) {

        if (space == null || vehicle == null) {
            return false;
        }

        if (space.isOccupied()) {
            return false;
        }

        if (reservationConflict) {
            return false;
        }

        return space.canAccept(vehicle);
    }
}