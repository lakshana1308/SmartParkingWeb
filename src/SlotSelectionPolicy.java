import model.ParkingSpace;
import model.Vehicle;

/**
 * Abstraction for parking-space selection.
 */
public interface SlotSelectionPolicy {

    boolean isEligible(
            ParkingSpace space,
            Vehicle vehicle,
            boolean reservationConflict
    );

    default boolean isEligibleForReservation(ParkingSpace space, Vehicle vehicle, boolean reservationConflict) {
        return space != null && vehicle != null && !reservationConflict && space.canAcceptForReservation(vehicle);
    }
}